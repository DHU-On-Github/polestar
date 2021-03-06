package com.dianping.polestar.jobs;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;

public class ProcessJob extends AbstractJob {
	public final static Logger LOG = Logger.getLogger(ProcessJob.class);

	private final static String KRB5CCNAME = "KRB5CCNAME";
	private final static String TICKET_CACHE_EXTENTION = ".ticketcache";

	protected final Map<String, String> envMap;
	protected volatile Process process;

	public ProcessJob(JobContext jobContext) {
		super(jobContext);
		envMap = new HashMap<String, String>(System.getenv());
	}

	@Override
	public Integer run() throws Exception {
		File workDir = new File(jobContext.getWorkDir());
		if (!workDir.exists()) {
			workDir.mkdirs();
		}
		final File dataFile = new File(workDir,
				EnvironmentConstants.DATA_FILE_NAME);
		dataFile.createNewFile();
		jobContext.setLocalDataPath(dataFile.getAbsolutePath());

		String ticketCachePath = workDir.getAbsolutePath() + File.separator
				+ jobContext.getUsername() + TICKET_CACHE_EXTENTION;
		Utilities.hadoopKerberosLogin(jobContext.getUsername(),
				jobContext.getPasswd(), ticketCachePath);
		jobContext.getProperties().setProperty(KRB5CCNAME, ticketCachePath);

		setEnvironmentVariables();

		final Boolean storeResult = jobContext.isStoreResult();
		final int resultLimit = storeResult ? Math.max(
				jobContext.getResLimitNum(),
				EnvironmentConstants.MAX_RESULT_DATA_NUMBER) : Math.min(
				jobContext.getResLimitNum(),
				EnvironmentConstants.DEFAULT_RESULT_DATA_NUMBER);

		ProcessBuilder builder = new ProcessBuilder(jobContext.getCommands());
		builder.directory(workDir);
		builder.environment().putAll(envMap);
		process = builder.start();

		final InputStream inputStream = process.getInputStream();
		final InputStream errorStream = process.getErrorStream();
		String threadName = "job-" + jobContext.getId();
		new Thread(new Runnable() {

			@Override
			public void run() {
				OutputStream os = null;
				BufferedOutputStream bos = null;
				try {
					BufferedReader reader = new BufferedReader(
							new InputStreamReader(inputStream));
					if (storeResult) {
						os = Utilities.openOutputStream(dataFile, false, true);
						bos = new BufferedOutputStream(os);
					}
					String line;
					int currLineNum = 0;
					while ((line = reader.readLine()) != null
							&& currLineNum++ < resultLimit) {
						System.out.println("stdout:" + line);
						if (storeResult) {
							bos.write(line.getBytes());
							bos.write(10);
						} else {
							jobContext.appendStdout(line);
						}
					}

					if (currLineNum >= resultLimit) {
						LOG.info("result limit exceed max value, start to destroy query process");
						process.destroy();
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					try {
						if (bos != null) {
							bos.flush();
							bos.close();
						}
						IOUtils.closeQuietly(os);
						IOUtils.closeQuietly(inputStream);
					} catch (IOException ex) {
					}
				}
			}
		}, threadName).start();

		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					BufferedReader reader = new BufferedReader(
							new InputStreamReader(errorStream));
					String line;
					while ((line = reader.readLine()) != null) {
						jobContext.getStderr().append(line).append('\n');
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					IOUtils.closeQuietly(errorStream);
				}
			}
		}, threadName).start();

		int exitCode = -999;
		try {
			exitCode = process.waitFor();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			process = null;
		}
		return exitCode;
	}

	@Override
	public void cancel() {
		process.destroy();
		canceled = true;
	}

	@Override
	public boolean isCanceled() {
		return canceled;
	}

	private void setEnvironmentVariables() {
		// set environment variables
		for (Object key : jobContext.getProperties().keySet()) {
			envMap.put((String) key,
					(String) jobContext.getProperties().get(key));
		}
	}
}

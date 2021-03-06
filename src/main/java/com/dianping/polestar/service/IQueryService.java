package com.dianping.polestar.service;

import com.dianping.polestar.entity.Query;
import com.dianping.polestar.entity.QueryResult;
import com.dianping.polestar.entity.QueryStatus;

public interface IQueryService {
	
	QueryResult postQuery(Query query);
	
	QueryStatus getStatusInfo(String id);
}

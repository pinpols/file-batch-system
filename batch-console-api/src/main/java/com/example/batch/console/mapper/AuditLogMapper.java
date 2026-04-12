package com.example.batch.console.mapper;

import com.example.batch.console.domain.query.AuditLogQuery;
import java.util.List;
import java.util.Map;

public interface AuditLogMapper {

  List<Map<String, Object>> selectByQuery(AuditLogQuery query);

  long countByQuery(AuditLogQuery query);
}

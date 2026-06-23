package io.github.pinpols.batch.console.mapper;

import io.github.pinpols.batch.console.domain.query.AuditLogQuery;
import java.util.List;
import java.util.Map;

public interface AuditLogMapper {

  List<Map<String, Object>> selectByQuery(AuditLogQuery query);

  long countByQuery(AuditLogQuery query);
}

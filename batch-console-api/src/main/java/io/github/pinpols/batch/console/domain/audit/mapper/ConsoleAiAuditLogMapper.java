package io.github.pinpols.batch.console.domain.audit.mapper;

import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiAuditLogEntity;
import io.github.pinpols.batch.console.domain.audit.query.ConsoleAiAuditLogQuery;
import java.util.List;

public interface ConsoleAiAuditLogMapper {

  int insert(ConsoleAiAuditLogEntity entity);

  List<ConsoleAiAuditLogEntity> selectByQuery(ConsoleAiAuditLogQuery query);

  long countByQuery(ConsoleAiAuditLogQuery query);
}

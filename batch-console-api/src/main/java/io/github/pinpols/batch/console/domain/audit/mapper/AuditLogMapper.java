package io.github.pinpols.batch.console.domain.audit.mapper;

import io.github.pinpols.batch.console.domain.audit.query.AuditLogQuery;
import io.github.pinpols.batch.console.domain.audit.view.FileAuditLogRow;
import java.util.List;

public interface AuditLogMapper {

  List<FileAuditLogRow> selectByQuery(AuditLogQuery query);

  long countByQuery(AuditLogQuery query);
}

package io.github.pinpols.batch.console.mapper;

import io.github.pinpols.batch.console.domain.audit.view.FileAuditLogRow;
import io.github.pinpols.batch.console.domain.query.AuditLogQuery;
import java.util.List;

public interface AuditLogMapper {

  List<FileAuditLogRow> selectByQuery(AuditLogQuery query);

  long countByQuery(AuditLogQuery query);
}

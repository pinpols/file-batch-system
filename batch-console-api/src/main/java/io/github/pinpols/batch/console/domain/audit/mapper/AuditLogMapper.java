package io.github.pinpols.batch.console.domain.audit.mapper;

import java.util.List;

import io.github.pinpols.batch.console.domain.audit.query.AuditLogQuery;
import io.github.pinpols.batch.console.domain.audit.view.FileAuditLogRow;

public interface AuditLogMapper {

    List<FileAuditLogRow> selectByQuery(AuditLogQuery query);

    long countByQuery(AuditLogQuery query);
}

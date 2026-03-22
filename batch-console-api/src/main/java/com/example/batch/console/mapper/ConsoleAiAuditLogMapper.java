package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.ConsoleAiAuditLogEntity;
import com.example.batch.console.domain.query.ConsoleAiAuditLogQuery;
import java.util.List;

public interface ConsoleAiAuditLogMapper {

    int insert(ConsoleAiAuditLogEntity entity);

    List<ConsoleAiAuditLogEntity> selectByQuery(ConsoleAiAuditLogQuery query);
}

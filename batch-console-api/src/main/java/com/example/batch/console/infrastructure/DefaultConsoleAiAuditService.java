package com.example.batch.console.infrastructure;

import com.example.batch.console.domain.command.AiAuditCommand;
import com.example.batch.console.support.ConsoleAiAuditService;
import com.example.batch.console.domain.entity.ConsoleAiAuditLogEntity;
import com.example.batch.console.mapper.ConsoleAiAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultConsoleAiAuditService implements ConsoleAiAuditService {

    private final ConsoleAiAuditLogMapper consoleAiAuditLogMapper;

    @Override
    public void record(AiAuditCommand command) {
        ConsoleAiAuditLogEntity entity = new ConsoleAiAuditLogEntity();
        entity.setTenantId(command.tenantId());
        entity.setRequestId(command.requestId());
        entity.setTraceId(command.traceId());
        entity.setSessionId(command.sessionId());
        entity.setOperatorId(command.operatorId());
        entity.setPromptCategory(command.promptCategory());
        entity.setPromptDecision(command.promptDecision());
        entity.setModelName(command.modelName());
        entity.setPromptHash(command.promptHash());
        entity.setPromptPreview(command.promptPreview());
        entity.setResponseHash(command.responseHash());
        entity.setResponsePreview(command.responsePreview());
        entity.setRefusalReason(command.refusalReason());
        consoleAiAuditLogMapper.insert(entity);
    }
}

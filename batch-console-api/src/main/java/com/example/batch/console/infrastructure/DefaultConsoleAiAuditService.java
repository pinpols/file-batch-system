package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.AiPromptCategory;
import com.example.batch.console.domain.command.AiAuditCommand;
import com.example.batch.console.domain.entity.ConsoleAiAuditLogEntity;
import com.example.batch.console.mapper.ConsoleAiAuditLogMapper;
import com.example.batch.console.support.ConsoleAiAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link com.example.batch.console.support.ConsoleAiAuditService} 的默认实现：写入 AI 审计表。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleAiAuditService implements ConsoleAiAuditService {

  private final ConsoleAiAuditLogMapper consoleAiAuditLogMapper;

  /** 持久化 AI 审计实体。 */
  @Override
  public void record(AiAuditCommand command) {
    ConsoleAiAuditLogEntity entity = new ConsoleAiAuditLogEntity();
    entity.setTenantId(command.tenantId());
    entity.setRequestId(command.requestId());
    entity.setTraceId(command.traceId());
    entity.setSessionId(command.sessionId());
    entity.setOperatorId(command.operatorId());
    entity.setPromptCategory(resolvePromptCategory(command.promptCategory()));
    entity.setPromptDecision(command.promptDecision());
    entity.setModelName(command.modelName());
    entity.setPromptHash(command.promptHash());
    entity.setPromptPreview(command.promptPreview());
    entity.setResponseHash(command.responseHash());
    entity.setResponsePreview(command.responsePreview());
    entity.setRefusalReason(command.refusalReason());
    consoleAiAuditLogMapper.insert(entity);
  }

  private String resolvePromptCategory(String promptCategory) {
    if (promptCategory == null || promptCategory.isBlank()) {
      return AiPromptCategory.OUT_OF_SCOPE.code();
    }
    return promptCategory;
  }
}

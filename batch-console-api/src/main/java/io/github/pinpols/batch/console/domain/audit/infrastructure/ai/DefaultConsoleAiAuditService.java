package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.common.enums.AiPromptCategory;
import io.github.pinpols.batch.console.domain.audit.command.AiAuditCommand;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiAuditLogEntity;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiAuditLogMapper;
import io.github.pinpols.batch.console.domain.audit.support.ConsoleAiAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link io.github.pinpols.batch.console.domain.audit.support.ConsoleAiAuditService} 的默认实现：写入 AI
 * 审计表。
 */
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

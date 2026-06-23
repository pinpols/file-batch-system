package io.github.pinpols.batch.console.domain.audit.support;

import io.github.pinpols.batch.console.domain.audit.command.AiAuditCommand;

/** AI 对话审计持久化（提示词摘要、决策、模型等）。 */
public interface ConsoleAiAuditService {

  /** 写入数据库一条 AI 调用审计记录。 */
  void record(AiAuditCommand command);
}

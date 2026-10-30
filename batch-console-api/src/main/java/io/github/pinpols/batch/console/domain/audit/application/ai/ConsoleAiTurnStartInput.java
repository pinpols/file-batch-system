package io.github.pinpols.batch.console.domain.audit.application.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/** 会话开始的原始输入；开关、所有者和版本仍由服务按原顺序校验，空提示仍由原清洗器处理。 */
@Builder
public record ConsoleAiTurnStartInput(
    String tenantId,
    String ownerUserId,
    String requestedConversationId,
    String contextVersion,
    String prompt,
    UUID clientTurnId,
    List<UUID> attachmentIds) {

  public ConsoleAiTurnStartInput {
    // 保留 null 与空列表的既有分支，同时防止调用方在用例执行期间修改附件身份。
    if (attachmentIds != null) {
      attachmentIds = Collections.unmodifiableList(new ArrayList<>(attachmentIds));
    }
  }

  @Override
  public String toString() {
    return "ConsoleAiTurnStartInput[redacted]";
  }
}

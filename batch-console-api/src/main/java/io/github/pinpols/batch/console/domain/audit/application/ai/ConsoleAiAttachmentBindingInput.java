package io.github.pinpols.batch.console.domain.audit.application.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/** 单轮附件绑定输入；空附件保持无需绑定的语义，非空附件仍由原归属与有效期校验处理。 */
@Builder
public record ConsoleAiAttachmentBindingInput(
    String tenantId,
    String ownerUserId,
    List<UUID> ids,
    String conversationId,
    long turnNo,
    Instant expiresAt) {

  public ConsoleAiAttachmentBindingInput {
    // 保留 null 与空列表的既有分支，同时防止调用方在用例执行期间修改附件身份。
    if (ids != null) {
      ids = Collections.unmodifiableList(new ArrayList<>(ids));
    }
  }

  @Override
  public String toString() {
    return "ConsoleAiAttachmentBindingInput[redacted]";
  }
}

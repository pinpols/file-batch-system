package io.github.pinpols.batch.console.domain.audit.param;

import java.util.UUID;
import lombok.Builder;

/** 绑定一次markDraft操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleAiAttachmentMarkDraftParam(
    String tenantId,
    UUID id,
    String mediaType,
    long byteSize,
    int width,
    int height) {}

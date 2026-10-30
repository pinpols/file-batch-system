package io.github.pinpols.batch.console.domain.rbac.service;

import lombok.Builder;

/**
 * 一次标签写入的原始输入，尚未解析租户或规范化资源类型。
 *
 * <p>可空值和非法资源类型仍交服务按原顺序校验，以保留租户授权优先级与业务错误契约。
 */
@Builder
public record ResourceTagUpsertInput(
    String tenantId,
    String resourceType,
    String resourceCode,
    String tagKey,
    String tagValue,
    String operator) {}

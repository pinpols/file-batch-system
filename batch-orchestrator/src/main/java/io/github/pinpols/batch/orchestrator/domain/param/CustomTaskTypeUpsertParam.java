package io.github.pinpols.batch.orchestrator.domain.param;

import lombok.Builder;
import lombok.Getter;

/**
 * {@code custom_task_type_registry} 的 SDK 上报 upsert 参数(SDK Phase 3 M3.1)。
 *
 * <p>{@code descriptor} 为 SdkTaskTypeDescriptor 序列化后的 JSON 文本,mapper 端 {@code ::jsonb} 入库。 冲突键
 * {@code (tenant_id, task_type_code)};命中即刷新 descriptor / 版本 / 上报 worker / last_declared_at。
 */
@Getter
@Builder
public class CustomTaskTypeUpsertParam {
  private final String tenantId;
  private final String taskTypeCode;
  private final String displayName;
  private final String descriptor;
  private final String descriptorVersion;
  private final String declaredByWorkerCode;
}

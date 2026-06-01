package com.example.batch.console.domain.ops.entity;

import java.time.Instant;
import lombok.Data;

/**
 * console-api 只读视图：SDK 声明的自定义 taskType（{@code custom_task_type_registry}，SDK Phase 3 M3.1）。
 *
 * <p>{@code descriptor} 为 JSONB 全文，mapper 端 {@code ::text} 读出为字符串原样透传给 FE。读侧实体，console-api
 * 走读写分离只读路径，不回写该表（注册由 orchestrator register 上报维护）。
 */
@Data
public class CustomTaskTypeEntity {

  private Long id;
  private String tenantId;
  private String taskTypeCode;
  private String displayName;
  private String descriptor;
  private String descriptorVersion;
  private String source;
  private String declaredByWorkerCode;
  private String status;
  private Instant firstDeclaredAt;
  private Instant lastDeclaredAt;
}

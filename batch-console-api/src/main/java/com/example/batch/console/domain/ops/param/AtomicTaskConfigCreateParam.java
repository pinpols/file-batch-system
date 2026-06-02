package com.example.batch.console.domain.ops.param;

import lombok.Data;

/**
 * R3-5 — 创建 {@code batch.atomic_task_config} 入参(service 内部用,Controller 收到的 request 已映射到此)。
 *
 * <p>{@code parametersJson} 是 {@code parameters} JSONB 的字符串载荷,XML 端 {@code ::jsonb} cast 入库;
 * service 在 INSERT 前已用 SensitiveDataValidator + AtomicTaskTypeSchema 完成校验。
 */
@Data
public class AtomicTaskConfigCreateParam {

  // INSERT 后 MyBatis 用 useGeneratedKeys 回写自增 id 到此字段;字段缺失 → "No setter found"
  private Long id;
  private String tenantId;
  private String taskType;
  private String name;
  private String parametersJson;
  private String createdBy;
}

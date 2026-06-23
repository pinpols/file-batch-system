package io.github.pinpols.batch.console.domain.ops.entity;

import java.time.Instant;
import lombok.Data;

/**
 * R3-5 / Round-1 TOP-8 — 租户保存的 atomic 节点配置({@code batch.atomic_task_config},V165)。
 *
 * <p>{@code parameters} 为 JSONB 全文,mapper 端 {@code ::text} 读出为字符串原样透传给 FE / service 层。 同租户同 {@code
 * task_type} 内 {@code name} 唯一(DB UNIQUE 约束)。
 */
@Data
public class AtomicTaskConfigEntity {

  private Long id;
  private String tenantId;
  private String taskType;
  private String name;

  /** parameters JSONB 全文(::text 读出),序列化由调用方负责。 */
  private String parameters;

  private String createdBy;
  private Instant createdAt;
  private Instant updatedAt;
}

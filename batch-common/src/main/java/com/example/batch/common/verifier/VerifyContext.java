package com.example.batch.common.verifier;

import com.example.batch.common.enums.JobType;
import java.util.Map;
import lombok.Builder;

/**
 * ContentVerifier 接收的执行上下文，描述"刚结束的这一步"是什么。
 *
 * <p>不持有领域对象——只传 ID + 必要的属性快照，让 verifier 自己回 DB / 对象存储拿真值， 避免 SPI 接口与领域模型耦合。
 *
 * @param tenantId 租户 ID
 * @param jobType job 类型（IMPORT/EXPORT/PROCESS/DISPATCH/WORKFLOW）
 * @param jobInstanceId job_instance.id
 * @param taskId job_task.id（可空，stage 级 verifier 可能在 task 创建前就跑）
 * @param stageCode stage 业务码（如 EXPORT_FETCH / DISPATCH_DISPATCH），与 worker 内部 step 对齐
 * @param payload 业务侧自由属性（fileId / objectName / rowCount / checksum 等），key 与 worker output schema
 *     对齐（详见 CLAUDE.md §Workflow 节点参数 DSL 规范）
 */
@Builder
public record VerifyContext(
    String tenantId,
    JobType jobType,
    Long jobInstanceId,
    Long taskId,
    String stageCode,
    Map<String, Object> payload) {

  public Object property(String key) {
    return payload == null ? null : payload.get(key);
  }
}

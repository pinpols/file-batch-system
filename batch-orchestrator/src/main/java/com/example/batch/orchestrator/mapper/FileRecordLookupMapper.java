package com.example.batch.orchestrator.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * Workflow 派发回退反查 file_record id 的单一职责 Mapper。
 *
 * <p>当 partition.output_summary 不含 fileId 时，{@code WorkflowNodePayloadBuilder} 通过两条独立线索反查：
 *
 * <ul>
 *   <li>{@link #selectIdByTenantAndTraceId} — 本次 run 期间 EXPORT worker 新建的 file_record 会打同一 trace_id
 *   <li>{@link #selectIdByTenantAndSourceRef} — 文件按 batchNo 幂等复用时 trace_id 不更新但 source_ref 一致
 * </ul>
 *
 * <p>仅查 {@code source_type='GENERATED'} 的 file_record，避免跨场景串号。
 */
public interface FileRecordLookupMapper {

  /** 按 (tenantId, traceId) 反查最新的 GENERATED file_record id；未命中返 null。 */
  Long selectIdByTenantAndTraceId(
      @Param("tenantId") String tenantId, @Param("traceId") String traceId);

  /** 按 (tenantId, sourceRef) 反查最新的 GENERATED file_record id；未命中返 null。 */
  Long selectIdByTenantAndSourceRef(
      @Param("tenantId") String tenantId, @Param("sourceRef") String sourceRef);
}

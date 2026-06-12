package com.example.batch.orchestrator.domain.value;

/**
 * 归档清扫的可归档实例引用：{@code (tenantId, id)} 二元组。
 *
 * <p>Citus 适配:原 {@code selectArchivableInstanceIds} 仅返回全局 {@code id} 列表,后续跨表级联 归档/删除按 id 关联两张
 * distributed 表(非分布列),触发 "complex joins ... co-located" 报错。 改为带租户的引用后,{@link
 * com.example.batch.orchestrator.application.archive.SuccessInstanceArchiveService} 按 {@code
 * tenantId} 分组、逐租户路由清扫,所有语句落在单分片、子查询在分布列(tenant_id)上共址。
 */
public record ArchivableInstanceRef(String tenantId, Long id) {}

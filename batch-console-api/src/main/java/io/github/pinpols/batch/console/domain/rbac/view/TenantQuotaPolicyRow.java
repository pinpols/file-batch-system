package io.github.pinpols.batch.console.domain.rbac.view;

import java.time.Instant;

/**
 * batch.tenant_quota_policy 行投影（TenantQuotaPolicyMapper 全表 17 列具名 record）。
 *
 * <p>selectByQuery 的 list 查询只选择响应需要的 11 列，其余 6 调度列（fair_share_group、burst_limit、
 * partition_burst_limit、quota_reset_policy、group_shared_max_running_jobs、exceeded_strategy）以 {@code
 * cast(null as ...)} 补齐构造器参数，由调用方按 null 处理；selectById / selectByUniqueKey 为全列。
 */
public record TenantQuotaPolicyRow(
    Long id,
    String tenantId,
    String policyCode,
    Integer maxRunningJobsPerTenant,
    Integer maxPartitionsPerTenant,
    Integer maxQpsPerTenant,
    Integer fairShareWeight,
    Boolean enabled,
    String description,
    Instant createdAt,
    Instant updatedAt,
    String fairShareGroup,
    Integer burstLimit,
    Integer partitionBurstLimit,
    String quotaResetPolicy,
    Integer groupSharedMaxRunningJobs,
    String exceededStrategy) {}

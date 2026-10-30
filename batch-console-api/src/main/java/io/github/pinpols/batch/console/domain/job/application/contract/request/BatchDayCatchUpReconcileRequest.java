package io.github.pinpols.batch.console.domain.job.application.contract.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 只指定租户和作业，来源键由服务端冻结记录确定。 */
public record BatchDayCatchUpReconcileRequest(
    @NotBlank @Size(max = 128) String tenantId,
    @NotBlank @Size(max = 128) String jobCode) {}

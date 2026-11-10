package io.github.pinpols.batch.console.domain.job.infrastructure;

import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDefinitionCopyRequest;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionView;

/** 根据已复制的作业和请求生成维护更新参数。 */
final class JobDefinitionCopyOverrideMapper {
  private JobDefinitionCopyOverrideMapper() {}

  static JobDefinitionMaintenanceUpdateParam create(
      JobDefinitionCopyRequest request,
      JobDefinitionView copied,
      String resolved,
      String operator) {
    JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
    param.setTenantId(resolved);
    param.setJobCode(request.getNewJobCode());
    param.setDependsOnJobCode(copied.getDependsOnJobCode());
    param.setJobName(
        EmptyChecks.isNotNull(request.getJobName()) ? request.getJobName() : copied.getJobName());
    param.setQueueCode(
        EmptyChecks.isNotNull(request.getQueueCode())
            ? CodeNormalizer.toConfigFormOrNull(request.getQueueCode())
            : copied.getQueueCode());
    param.setWorkerGroup(
        EmptyChecks.isNotNull(request.getWorkerGroup())
            ? CodeNormalizer.toUpperOrNull(request.getWorkerGroup())
            : copied.getWorkerGroup());
    param.setScheduleExpr(
        EmptyChecks.isNotNull(request.getScheduleExpr())
            ? request.getScheduleExpr()
            : copied.getScheduleExpr());
    param.setTriggerMode(copied.getTriggerMode());
    param.setCalendarCode(
        EmptyChecks.isNotNull(request.getCalendarCode())
            ? CodeNormalizer.toConfigFormOrNull(request.getCalendarCode())
            : copied.getCalendarCode());
    param.setWindowCode(
        EmptyChecks.isNotNull(request.getWindowCode())
            ? CodeNormalizer.toConfigFormOrNull(request.getWindowCode())
            : copied.getWindowCode());
    param.setRetryPolicy(
        EmptyChecks.isNotNull(request.getRetryPolicy())
            ? request.getRetryPolicy()
            : copied.getRetryPolicy());
    param.setRetryMaxCount(
        EmptyChecks.isNotNull(request.getRetryMaxCount())
            ? request.getRetryMaxCount()
            : copied.getRetryMaxCount());
    param.setTimeoutSeconds(
        EmptyChecks.isNotNull(request.getTimeoutSeconds())
            ? request.getTimeoutSeconds()
            : copied.getTimeoutSeconds());
    param.setShardStrategy(copied.getShardStrategy());
    param.setExecutionMode(copied.getExecutionMode());
    param.setWatermarkField(copied.getWatermarkField());
    // 维护更新会无条件写这三列。克隆请求没有对应覆盖项，必须带回刚复制的值，
    // 否则 priority 被写成 null 触发非空约束，执行器和参数模板也会被清空。
    param.setExecutionHandler(copied.getExecutionHandler());
    param.setParamSchema(copied.getParamSchema());
    param.setPriority(EmptyChecks.isNull(copied.getPriority()) ? 5 : copied.getPriority());
    param.setDefaultParams(copied.getDefaultParams());
    param.setEnabled(
        EmptyChecks.isNotNull(request.getEnabled()) ? request.getEnabled() : copied.getEnabled());
    param.setDescription(
        EmptyChecks.isNotNull(request.getDescription())
            ? request.getDescription()
            : copied.getDescription());
    param.setUpdatedBy(operator);
    return param;
  }
}

package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.view.JobPushDelivery;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDecisionInsertParam;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDeliveryClaimParam;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDeliveryFinishParam;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDeliveryReconcileStoppedParam;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDeviceInsertParam;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Job 回执和冻结的设备通知意图在调用方的租户事务中共同提交。 */
public interface JobPushDeliveryMapper {
  String bindTenant(@Param("tenantId") String tenantId);

  List<String> tenantPage(@Param("after") String after, @Param("limit") int limit);

  default int reconcileStopped(String tenantId, Instant now, int maxAttempts, int limit) {
    final String exhaustedStatus = "EXHAUSTED";
    JobPushDeliveryReconcileStoppedParam param = JobPushDeliveryReconcileStoppedParam.builder()
        .tenantId(tenantId)
        .now(now)
        .maxAttempts(maxAttempts)
        .limit(limit)
        .targetStatus(exhaustedStatus)
        .build();
    return reconcileStoppedWithStatus(param);
  }

  int reconcileStoppedWithStatus(JobPushDeliveryReconcileStoppedParam param);

  List<JobPushDelivery> pending(
      @Param("tenantId") String tenantId, @Param("now") Instant now, @Param("limit") int limit);

  int claim(JobPushDeliveryClaimParam param);

  int finish(JobPushDeliveryFinishParam param);

  int insertDecision(JobPushDecisionInsertParam param);

  int insertDevice(JobPushDeviceInsertParam param);
}

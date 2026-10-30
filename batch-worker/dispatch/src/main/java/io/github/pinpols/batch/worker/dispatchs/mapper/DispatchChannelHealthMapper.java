package io.github.pinpols.batch.worker.dispatchs.mapper;

import io.github.pinpols.batch.worker.dispatchs.param.DispatchChannelHealthBackoffParam;
import io.github.pinpols.batch.worker.dispatchs.param.DispatchChannelHealthFailureParam;
import io.github.pinpols.batch.worker.dispatchs.param.DispatchChannelHealthProbeClaimParam;
import io.github.pinpols.batch.worker.dispatchs.param.DispatchChannelHealthSuccessParam;
import io.github.pinpols.batch.worker.dispatchs.param.DispatchChannelHealthUpsertParam;
import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface DispatchChannelHealthMapper {

  enum HealthStatus {
    HEALTHY,
    DEGRADED,
    UNHEALTHY
  }

  List<DispatchChannelConfigView> findEnabledProbeChannels(
      @Param("types") List<String> types, @Param("limit") int limit);

  DispatchChannelHealthView findHealth(
      @Param("tenantId") String tenantId, @Param("channelCode") String channelCode);

  int upsertHealth(DispatchChannelHealthUpsertParam params);

  /**
   * A-3.9：CAS 抢占半开探针机会。仅当 next_probe_at &lt;= now 且 health_status 非 HEALTHY 时 将 next_probe_at 更新为
   * newNextProbeAt；返回受影响行数。
   *
   * @return 1=本线程抢到半开通行证；0=另一线程已抢走或已恢复 HEALTHY
   */
  int tryClaimHalfOpenProbe(DispatchChannelHealthProbeClaimParam params);

  /** P2：成功结果原子 UPSERT —— 置 HEALTHY + consecutive_failures=0。 */
  default int upsertSuccess(DispatchChannelHealthSuccessParam params) {
    return upsertSuccessWithStatus(params, HealthStatus.HEALTHY.name());
  }

  int upsertSuccessWithStatus(
      @Param("params") DispatchChannelHealthSuccessParam params,
      @Param("healthyStatus") String healthyStatus);

  /**
   * P2：失败结果原子 UPSERT —— consecutive_failures 按数据库 COALESCE+1 递增， 消除 Java 侧读-改-写的
   * lost-update；health_status 按 failureThreshold 动态判。
   */
  default int upsertFailureAndBump(DispatchChannelHealthFailureParam params) {
    return upsertFailureAndBumpWithStatuses(
        params, HealthStatus.DEGRADED.name(), HealthStatus.UNHEALTHY.name());
  }

  int upsertFailureAndBumpWithStatuses(
      @Param("params") DispatchChannelHealthFailureParam params,
      @Param("degradedStatus") String degradedStatus,
      @Param("unhealthyStatus") String unhealthyStatus);

  /** P1：仅在失败次数仍等于本次递增结果时回写退避时间，避免并发旧结果覆盖新结果。 */
  int recalcBackoff(DispatchChannelHealthBackoffParam params);

  long countByHealthStatus(@Param("healthStatus") String healthStatus);

  long countProbeOverdue(@Param("now") Timestamp now);
}

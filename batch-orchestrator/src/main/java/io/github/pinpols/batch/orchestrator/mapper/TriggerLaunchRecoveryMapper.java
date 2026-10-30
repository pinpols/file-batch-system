package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.TriggerLaunchRecoveryEntity;
import io.github.pinpols.batch.orchestrator.domain.param.TriggerLaunchRecoveryAttemptParam;
import io.github.pinpols.batch.orchestrator.domain.param.TriggerLaunchRecoveryRetryParam;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 启动恢复锚点：消费端重试耗尽时落原始 envelope 密文，周期扫描幂等重投。 */
public interface TriggerLaunchRecoveryMapper {

  /** 首次登记，不覆盖已有请求的冻结载荷。 */
  int insertPending(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("envelopeCiphertext") String envelopeCiphertext,
      @Param("traceId") String traceId);

  TriggerLaunchRecoveryEntity selectByIdentityForUpdate(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  int requeueDead(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("nextStatus") String nextStatus);

  List<TriggerLaunchRecoveryEntity> selectDue(@Param("now") Instant now, @Param("limit") int limit);

  int markDone(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("status") String status);

  /** 推进一次尝试：attempts+1、写状态/下一次时间/最后错误码；仅对 PENDING 生效（CAS）。 */
  int markAttempt(TriggerLaunchRecoveryAttemptParam parameters);

  /** 原子累计失败并返回实际状态；已终态时返回 null。 */
  String markRetryOrDead(TriggerLaunchRecoveryRetryParam parameters);
}

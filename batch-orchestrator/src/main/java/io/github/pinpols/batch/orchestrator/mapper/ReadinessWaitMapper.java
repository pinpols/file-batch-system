package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.ReadinessWaitEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ReadinessWaitReleaseParam;
import io.github.pinpols.batch.orchestrator.domain.param.ReadinessWaitTerminalParam;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Orchestrator 接管的 readiness 持久等待。 */
public interface ReadinessWaitMapper {

  /** 幂等接管登记；同一 (tenant, workflowDefinitionId, scheduledFireTime) 只保留首次。 */
  int insertIgnore(ReadinessWaitEntity wait);

  /** PENDING 等待; 优先扫描最久未探测的记录, 同一水位再按截止时间与身份排序。 */
  List<ReadinessWaitEntity> selectDue(@Param("limit") int limit);

  /** 更新仍待处理记录的探测水位; 不改变等待状态。 */
  int recordProbe(@Param("tenantId") String tenantId, @Param("id") Long id);

  /**
   * 按原 fire 身份查 PENDING 等待；与 V351 唯一键 (tenant, workflowDefinitionId,
   * scheduledFireTime) 对齐，供正常 fire 后的取消回调定位，避免全量加载。
   */
  List<ReadinessWaitEntity> selectByFireIdentity(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("scheduledFireTime") Instant scheduledFireTime);

  /** 就绪后标记已释放（CAS：仅 PENDING 可推进）。 */
  int markReleased(ReadinessWaitReleaseParam parameters);

  /** 超窗/取消失败等终态（CAS：仅 PENDING 可推进）。 */
  int markTerminal(ReadinessWaitTerminalParam parameters);
}

package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.param.BatchDayCatchupEventParam;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayCatchupPauseStateParam;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayCatchupControlEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;

/** 批次日自动补扫的租户级控制及不可变审计历史。 */
public interface BatchDayCatchupControlMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  BatchDayCatchupControlEntity selectForUpdate(
      @Param("tenantId") String tenantId, @Param("calendarCode") String calendarCode);

  BatchDayCatchupControlEntity selectCurrent(
      @Param("tenantId") String tenantId, @Param("calendarCode") String calendarCode);

  int insertIfMissing(
      @Param("tenantId") String tenantId, @Param("calendarCode") String calendarCode);

  int updatePauseState(BatchDayCatchupPauseStateParam param);

  int insertEvent(BatchDayCatchupEventParam param);
}

package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.query.SensorFileArrivalLatestArrivalQuery;
import io.github.pinpols.batch.orchestrator.domain.view.SensorFileArrivalMatchView;
import io.github.pinpols.batch.orchestrator.domain.view.SensorFileArrivalView;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * ADR-028 FILE_ARRIVAL sensor 反查 batch.file_record。
 *
 * <p>策略：按 (tenant_id, file_category=INPUT, file_name LIKE pattern, created_at &gt;= since) 取最近一条。
 * 命中即 sensor.MATCHED 推进。
 */
public interface SensorFileArrivalMapper {

  /**
   * 查最新到达的 file_record。
   *
   * @param parameters 租户、SQL LIKE 模式（使用 {@code !} 转义）、可选来源及到达时间等筛选条件
   * @return 最新命中投影，未命中返 null
   */
  SensorFileArrivalView selectLatestArrival(SensorFileArrivalLatestArrivalQuery parameters);

  /** 调试用：返回最近 N 条匹配，方便排查 sensor 误判。 */
  List<SensorFileArrivalMatchView> recentMatches(
      @Param("tenantId") String tenantId,
      @Param("namePattern") String namePattern,
      @Param("limit") int limit);
}

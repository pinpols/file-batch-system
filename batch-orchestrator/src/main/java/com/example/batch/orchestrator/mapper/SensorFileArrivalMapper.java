package com.example.batch.orchestrator.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
   * @param tenantId 租户
   * @param namePattern SQL LIKE 模式（调用方将 {@code *} 转 {@code %}）
   * @param sourceType 可选 source_type 过滤（SFTP / UPLOAD / API），传 null 不过滤
   * @param since created_at &gt;= since（按 maxAgeSeconds 计算）
   * @return 命中行 {@code {fileId, fileName, arrivalTime}}，未命中返 null
   */
  Map<String, Object> selectLatestArrival(
      @Param("tenantId") String tenantId,
      @Param("namePattern") String namePattern,
      @Param("sourceType") String sourceType,
      @Param("since") OffsetDateTime since);

  /** 调试用：返回最近 N 条匹配，方便排查 sensor 误判。 */
  List<Map<String, Object>> recentMatches(
      @Param("tenantId") String tenantId,
      @Param("namePattern") String namePattern,
      @Param("limit") int limit);
}

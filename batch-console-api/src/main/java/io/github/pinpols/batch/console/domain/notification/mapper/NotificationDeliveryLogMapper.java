package io.github.pinpols.batch.console.domain.notification.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface NotificationDeliveryLogMapper {

  List<Map<String, Object>> selectByTenant(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  int insert(Map<String, Object> params);

  /**
   * 按主键更新一条投递日志状态。租户隔离:{@code params} 必须同时携带 {@code id} 与 {@code tenantId} —— SQL 的 WHERE 子句
   * 除主键外强制带 {@code tenant_id} 谓词(见 mapper XML),防未来接线用可枚举 id 跨租户改写别人的日志行。
   */
  int updateStatus(Map<String, Object> params);
}

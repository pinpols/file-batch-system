package io.github.pinpols.batch.console.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface ConfigSyncLogMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  List<Map<String, Object>> selectByTenant(
      @Param("tenantId") String tenantId, @Param("offset") long offset, @Param("limit") int limit);

  Map<String, Object> selectByIdForUpdate(
      @Param("tenantId") String tenantId, @Param("logId") Long logId);

  Long selectIdByRequestKey(
      @Param("tenantId") String tenantId, @Param("requestKeyHash") String requestKeyHash);

  long countByTenant(@Param("tenantId") String tenantId);

  int insert(Map<String, Object> params);

  int updateResult(Map<String, Object> params);
}

package io.github.pinpols.batch.console.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface ConfigSyncLogMapper {

  List<Map<String, Object>> selectByTenant(
      @Param("tenantId") String tenantId, @Param("offset") long offset, @Param("limit") int limit);

  long countByTenant(@Param("tenantId") String tenantId);

  int insert(Map<String, Object> params);

  int updateResult(Map<String, Object> params);
}

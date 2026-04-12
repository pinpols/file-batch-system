package com.example.batch.console.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface ConfigSyncLogMapper {

  List<Map<String, Object>> selectByTenant(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  int insert(Map<String, Object> params);

  int updateResult(Map<String, Object> params);
}

package com.example.batch.console.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface NotificationDeliveryLogMapper {

  List<Map<String, Object>> selectByTenant(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  int insert(Map<String, Object> params);

  int updateStatus(Map<String, Object> params);
}

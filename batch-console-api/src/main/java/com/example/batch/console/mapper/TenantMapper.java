package com.example.batch.console.mapper;

import com.example.batch.common.model.PageRequest;
import com.example.batch.console.mapper.param.TenantUpsertParam;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface TenantMapper {

  List<Map<String, Object>> selectByQuery(
      @Param("keyword") String keyword,
      @Param("status") String status,
      @Param("pageRequest") PageRequest pageRequest);

  long countByQuery(@Param("keyword") String keyword, @Param("status") String status);

  Map<String, Object> selectByTenantId(@Param("tenantId") String tenantId);

  int insert(TenantUpsertParam param);

  int update(
      @Param("tenantId") String tenantId,
      @Param("tenantName") String tenantName,
      @Param("description") String description);

  int updateStatus(@Param("tenantId") String tenantId, @Param("status") String status);
}

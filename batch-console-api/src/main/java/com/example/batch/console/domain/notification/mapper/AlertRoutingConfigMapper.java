package com.example.batch.console.domain.notification.mapper;

import com.example.batch.common.model.PageRequest;
import com.example.batch.console.domain.param.AlertRoutingConfigUpsertParam;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface AlertRoutingConfigMapper {

  List<Map<String, Object>> selectByQuery(
      @Param("tenantId") String tenantId,
      @Param("routeCode") String routeCode,
      @Param("team") String team,
      @Param("severity") String severity,
      @Param("enabled") Boolean enabled,
      @Param("pageRequest") PageRequest pageRequest);

  long countByQuery(
      @Param("tenantId") String tenantId,
      @Param("routeCode") String routeCode,
      @Param("team") String team,
      @Param("severity") String severity,
      @Param("enabled") Boolean enabled);

  Map<String, Object> selectByUniqueKey(
      @Param("tenantId") String tenantId, @Param("routeCode") String routeCode);

  Map<String, Object> selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int upsertAlertRoutingConfig(@Param("p") AlertRoutingConfigUpsertParam param);

  int updateById(@Param("p") AlertRoutingConfigUpsertParam param);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);
}

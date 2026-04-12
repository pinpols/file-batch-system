package com.example.batch.console.mapper;

import com.example.batch.common.model.PageRequest;
import com.example.batch.console.mapper.param.AlertRoutingConfigUpsertParam;
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

  int upsertAlertRoutingConfig(@Param("p") AlertRoutingConfigUpsertParam param);
}

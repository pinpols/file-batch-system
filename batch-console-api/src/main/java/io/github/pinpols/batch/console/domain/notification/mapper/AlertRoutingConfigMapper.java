package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.query.AlertRoutingConfigCountQuery;
import io.github.pinpols.batch.console.domain.notification.query.AlertRoutingConfigQuery;


import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.param.AlertRoutingConfigUpsertParam;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface AlertRoutingConfigMapper {

  List<Map<String, Object>> selectByQuery(
      AlertRoutingConfigQuery parameters);

  long countByQuery(
      AlertRoutingConfigCountQuery parameters);

  Map<String, Object> selectByUniqueKey(
      @Param("tenantId") String tenantId, @Param("routeCode") String routeCode);

  Map<String, Object> selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int upsertAlertRoutingConfig(@Param("p") AlertRoutingConfigUpsertParam param);

  int updateById(@Param("p") AlertRoutingConfigUpsertParam param);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);
}

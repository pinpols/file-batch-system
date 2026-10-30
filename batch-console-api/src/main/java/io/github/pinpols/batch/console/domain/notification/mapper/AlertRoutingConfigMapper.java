package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.query.AlertRoutingConfigCountQuery;
import io.github.pinpols.batch.console.domain.notification.query.AlertRoutingConfigQuery;
import io.github.pinpols.batch.console.domain.notification.view.AlertRoutingConfigRow;
import io.github.pinpols.batch.console.domain.param.AlertRoutingConfigUpsertParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AlertRoutingConfigMapper {

  List<AlertRoutingConfigRow> selectByQuery(AlertRoutingConfigQuery parameters);

  long countByQuery(AlertRoutingConfigCountQuery parameters);

  AlertRoutingConfigRow selectByUniqueKey(
      @Param("tenantId") String tenantId, @Param("routeCode") String routeCode);

  AlertRoutingConfigRow selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int upsertAlertRoutingConfig(@Param("p") AlertRoutingConfigUpsertParam param);

  int updateById(@Param("p") AlertRoutingConfigUpsertParam param);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);
}

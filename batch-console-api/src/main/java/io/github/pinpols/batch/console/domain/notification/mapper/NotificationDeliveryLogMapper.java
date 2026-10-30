package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.param.NotificationDeliveryLogInsertParam;
import io.github.pinpols.batch.console.domain.notification.view.NotificationDeliveryLogRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface NotificationDeliveryLogMapper {

  /** 在当前事务中绑定已授权租户，供热冷历史查询共用。 */
  String bindTenant(@Param("tenantId") String tenantId);

  List<NotificationDeliveryLogRow> selectByTenant(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  int insert(NotificationDeliveryLogInsertParam params);
}

package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.query.AlertNotificationReceiptCommand;
import org.apache.ibatis.annotations.Param;

/** 最终通知成功后的租户隔离回执写入。 */
public interface AlertNotificationReceiptMapper {
  String bindTenant(@Param("tenantId") String tenantId);

  int recordDelivered(AlertNotificationReceiptCommand command);
}

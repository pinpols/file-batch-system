package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.param.NotificationChannelInsertParam;
import io.github.pinpols.batch.console.domain.notification.param.NotificationChannelUpdateParam;
import io.github.pinpols.batch.console.domain.notification.view.NotificationChannelRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface NotificationChannelMapper {

  List<NotificationChannelRow> selectByTenant(@Param("tenantId") String tenantId);

  NotificationChannelRow selectByCode(
      @Param("tenantId") String tenantId, @Param("channelCode") String channelCode);

  int insert(NotificationChannelInsertParam params);

  int update(NotificationChannelUpdateParam params);

  int deleteByCode(@Param("tenantId") String tenantId, @Param("channelCode") String channelCode);
}

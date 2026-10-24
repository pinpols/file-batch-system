package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.param.AlertEscalationNotificationOutboxMarkFailedParam;
import io.github.pinpols.batch.console.domain.notification.param.AlertEscalationNotificationOutboxMarkGiveUpParam;
import io.github.pinpols.batch.console.domain.notification.param.AlertEscalationNotificationOutboxMarkPublishingParam;


import io.github.pinpols.batch.console.domain.notification.entity.AlertEscalationNotificationOutboxEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AlertEscalationNotificationOutboxMapper {

  int insert(AlertEscalationNotificationOutboxEntity entity);

  List<AlertEscalationNotificationOutboxEntity> selectPending(
      @Param("now") Instant now,
      @Param("limit") int limit,
      @Param("pendingStatus1") String pendingStatus1,
      @Param("pendingStatus2") String pendingStatus2);

  List<AlertEscalationNotificationOutboxEntity> selectStalePublishing(
      @Param("limit") int limit,
      @Param("publishingStatus") String publishingStatus,
      @Param("timeoutSeconds") long timeoutSeconds);

  int markPublishing(
      AlertEscalationNotificationOutboxMarkPublishingParam parameters);

  int markPublished(
      @Param("id") Long id,
      @Param("tenantId") String tenantId,
      @Param("publishedStatus") String publishedStatus,
      @Param("publishingStatus") String publishingStatus);

  int markFailed(
      AlertEscalationNotificationOutboxMarkFailedParam parameters);

  int markGiveUp(
      AlertEscalationNotificationOutboxMarkGiveUpParam parameters);

  int resetStalePublishing(
      @Param("id") Long id,
      @Param("tenantId") String tenantId,
      @Param("failedStatus") String failedStatus,
      @Param("publishingStatus") String publishingStatus);
}

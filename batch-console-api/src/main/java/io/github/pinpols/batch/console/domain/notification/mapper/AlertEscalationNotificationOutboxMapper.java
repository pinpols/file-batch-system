package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.entity.AlertEscalationNotificationOutboxEntity;
import io.github.pinpols.batch.console.domain.notification.param.AlertEscalationNotificationOutboxMarkFailedParam;
import io.github.pinpols.batch.console.domain.notification.param.AlertEscalationNotificationOutboxMarkGiveUpParam;
import io.github.pinpols.batch.console.domain.notification.param.AlertEscalationNotificationOutboxMarkPublishedParam;
import io.github.pinpols.batch.console.domain.notification.param.AlertEscalationNotificationOutboxMarkPublishingParam;
import io.github.pinpols.batch.console.domain.notification.param.AlertEscalationNotificationOutboxResetStaleParam;
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

    int markPublishing(AlertEscalationNotificationOutboxMarkPublishingParam parameters);

    int markPublished(AlertEscalationNotificationOutboxMarkPublishedParam parameters);

    int markFailed(AlertEscalationNotificationOutboxMarkFailedParam parameters);

    int markGiveUp(AlertEscalationNotificationOutboxMarkGiveUpParam parameters);

    /**
     * 超时复位：只回收扫描时看到的那一轮凭据，并再次校验状态与超时条件，避免误回收刚被重新领取的通知。
     */
    int resetStalePublishing(AlertEscalationNotificationOutboxResetStaleParam parameters);
}

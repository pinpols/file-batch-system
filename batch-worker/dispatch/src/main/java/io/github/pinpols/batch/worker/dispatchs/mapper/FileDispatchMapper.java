package io.github.pinpols.batch.worker.dispatchs.mapper;

import io.github.pinpols.batch.worker.core.mapper.PlatformFileRecordView;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchRecordSnapshot;
import io.github.pinpols.batch.worker.dispatchs.domain.PendingReceiptPollRow;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchBeginAttemptParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchBeginVerifiedAttemptParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchInsertParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchMarkAckedParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchMarkAttemptFailedParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchMarkCompensatedParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchMarkFailedParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchMarkSentParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 文件分发 MyBatis Mapper 接口，对应 file_dispatch_record 及 file_channel_config 相关 SQL 操作。 */
public interface FileDispatchMapper {

  PlatformFileRecordView selectFileRecord(
      @Param("tenantId") String tenantId, @Param("fileId") Long fileId);

  DispatchChannelConfigView selectChannelConfig(
      @Param("tenantId") String tenantId, @Param("channelCode") String channelCode);

  boolean isChannelActive(
      @Param("tenantId") String tenantId, @Param("channelCode") String channelCode);

  DispatchRecordSnapshot selectLatestDispatchRecord(
      @Param("tenantId") String tenantId,
      @Param("fileId") Long fileId,
      @Param("channelCode") String channelCode);

  int insertDispatchRecord(FileDispatchInsertParam params);

  int beginAttempt(FileDispatchBeginAttemptParam params);

  int beginVerifiedExternalOutcomeAttempt(FileDispatchBeginVerifiedAttemptParam params);

  int markAttemptFailed(FileDispatchMarkAttemptFailedParam params);

  int markSent(FileDispatchMarkSentParam params);

  int markAcked(FileDispatchMarkAckedParam params);

  int markFailed(FileDispatchMarkFailedParam params);

  int markCompensated(FileDispatchMarkCompensatedParam params);

  List<PendingReceiptPollRow> listPendingReceiptPolls(
      @Param("limit") int limit,
      @Param("dispatchStatus") String dispatchStatus,
      @Param("receiptStatus") String receiptStatus,
      @Param("maxAgeSeconds") long maxAgeSeconds);
}

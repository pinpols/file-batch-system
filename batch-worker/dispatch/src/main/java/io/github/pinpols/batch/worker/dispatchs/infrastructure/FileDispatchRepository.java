package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import io.github.pinpols.batch.common.enums.FileDispatchStatus;
import io.github.pinpols.batch.common.enums.FileReceiptStatus;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRecordView;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchRecordSnapshot;
import io.github.pinpols.batch.worker.dispatchs.domain.PendingReceiptPollRow;
import io.github.pinpols.batch.worker.dispatchs.mapper.DispatchChannelConfigView;
import io.github.pinpols.batch.worker.dispatchs.mapper.FileDispatchMapper;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchBeginAttemptParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchBeginVerifiedAttemptParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchInsertParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchMarkAckedParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchMarkAttemptFailedParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchMarkCompensatedParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchMarkFailedParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchMarkSentParam;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 文件分发数据仓库，封装分发记录、渠道配置的增删改查操作。 */
@Repository
@RequiredArgsConstructor
public class FileDispatchRepository {

  private static final int MAX_DISPATCH_BATCH_SIZE = 500;

  private final FileDispatchMapper fileDispatchMapper;

  public Map<String, Object> loadFile(String tenantId, String fileId) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(fileId)) {
      return Map.of();
    }
    Long parsedFileId = Long.valueOf(fileId);
    return loadFile(tenantId, parsedFileId);
  }

  public Map<String, Object> loadFile(String tenantId, Long fileId) {
    if (!Texts.hasText(tenantId) || fileId == null) {
      return Map.of();
    }
    PlatformFileRecordView fileRecord = fileDispatchMapper.selectFileRecord(tenantId, fileId);
    return EmptyChecks.isNull(fileRecord) ? Map.of() : fileRecord.toPluginValues();
  }

  public Map<String, Object> loadChannel(String tenantId, String channelCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(channelCode)) {
      return Map.of();
    }
    DispatchChannelConfigView channelConfig =
        fileDispatchMapper.selectChannelConfig(tenantId, channelCode);
    return EmptyChecks.isNull(channelConfig) ? Map.of() : channelConfig.toPluginValues();
  }

  public boolean isChannelActive(String tenantId, String channelCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(channelCode)) {
      return false;
    }
    return fileDispatchMapper.isChannelActive(tenantId, channelCode);
  }

  public Optional<DispatchRecordSnapshot> latestDispatchRecord(
      String tenantId, Long fileId, String channelCode) {
    if (!Texts.hasText(tenantId) || EmptyChecks.isNull(fileId) || !Texts.hasText(channelCode)) {
      return Optional.empty();
    }
    DispatchRecordSnapshot latestRecord =
        fileDispatchMapper.selectLatestDispatchRecord(tenantId, fileId, channelCode);
    return Optional.ofNullable(latestRecord);
  }

  public record InsertDispatchParam(
      String tenantId,
      Long fileId,
      Long pipelineInstanceId,
      String channelCode,
      String dispatchTarget,
      String receiptCode,
      String receiptStatus,
      String externalRequestId) {}

  public int insertDispatchRecord(InsertDispatchParam p) {
    String initialDispatchStatus = FileDispatchStatus.CREATED.name();
    FileDispatchInsertParam insert = FileDispatchInsertParam.builder()
        .tenantId(p.tenantId())
        .fileId(p.fileId())
        .pipelineInstanceId(p.pipelineInstanceId())
        .channelCode(p.channelCode())
        .dispatchTarget(p.dispatchTarget())
        .dispatchStatus(initialDispatchStatus)
        .receiptCode(p.receiptCode())
        .receiptStatus(p.receiptStatus())
        .externalRequestId(p.externalRequestId())
        .build();
    return fileDispatchMapper.insertDispatchRecord(insert);
  }

  public int beginAttempt(String tenantId, Long fileId, String channelCode) {
    String dispatchStatus = FileDispatchStatus.CREATED.code();
    String receiptStatus = FileReceiptStatus.NONE.code();
    FileDispatchBeginAttemptParam attemptParams = FileDispatchBeginAttemptParam.builder()
        .tenantId(tenantId)
        .fileId(fileId)
        .channelCode(channelCode)
        .unknownOutcomeCode(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN)
        .dispatchStatus(dispatchStatus)
        .receiptStatus(receiptStatus)
        .build();
    return fileDispatchMapper.beginAttempt(attemptParams);
  }

  public int beginVerifiedExternalOutcomeAttempt(
      String tenantId,
      Long fileId,
      String channelCode,
      Long jobInstanceId,
      Long pipelineInstanceId) {
    boolean contextMissing = !Texts.hasText(tenantId)
        || EmptyChecks.isNull(fileId)
        || !Texts.hasText(channelCode)
        || EmptyChecks.isNull(jobInstanceId)
        || EmptyChecks.isNull(pipelineInstanceId);
    if (contextMissing) {
      return 0;
    }
    String dispatchStatus = FileDispatchStatus.CREATED.code();
    String receiptStatus = FileReceiptStatus.NONE.code();
    FileDispatchBeginVerifiedAttemptParam attemptParams =
        FileDispatchBeginVerifiedAttemptParam.builder()
            .tenantId(tenantId)
            .fileId(fileId)
            .channelCode(channelCode)
            .jobInstanceId(jobInstanceId)
            .pipelineInstanceId(pipelineInstanceId)
            .unknownOutcomeCode(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN)
            .dispatchStatus(dispatchStatus)
            .receiptStatus(receiptStatus)
            .build();
    return fileDispatchMapper.beginVerifiedExternalOutcomeAttempt(attemptParams);
  }

  public int markAttemptFailed(
      String tenantId, Long fileId, String channelCode, String errorCode, String errorMessage) {
    FileDispatchMarkAttemptFailedParam update = FileDispatchMarkAttemptFailedParam.builder()
        .tenantId(tenantId)
        .fileId(fileId)
        .channelCode(channelCode)
        .dispatchStatus(FileDispatchStatus.FAILED.name())
        .receiptStatus(FileReceiptStatus.FAILED.name())
        .expectedDispatchStatus(FileDispatchStatus.CREATED.name())
        .errorCode(errorCode)
        .errorMessage(errorMessage)
        .build();
    return fileDispatchMapper.markAttemptFailed(update);
  }

  public int markSent(
      String tenantId,
      Long fileId,
      String channelCode,
      String externalRequestId,
      String receiptCode,
      String receiptStatus) {
    FileDispatchMarkSentParam update = FileDispatchMarkSentParam.builder()
        .tenantId(tenantId)
        .fileId(fileId)
        .channelCode(channelCode)
        .dispatchStatus(FileDispatchStatus.SENT.name())
        .externalRequestId(externalRequestId)
        .receiptCode(receiptCode)
        .receiptStatus(receiptStatus)
        .expectedDispatchStatus(FileDispatchStatus.CREATED.name())
        .build();
    return fileDispatchMapper.markSent(update);
  }

  public int markAcked(String tenantId, Long fileId, String channelCode, String receiptCode) {
    String acknowledgedDispatchStatus = FileDispatchStatus.ACKED.name();
    String successfulReceiptStatus = FileReceiptStatus.SUCCESS.name();
    String expectedDispatchStatus = FileDispatchStatus.SENT.name();
    // 行级 CAS 只匹配 SENT；同步确认已写 SUCCESS 的回执仍须允许 ACK。
    FileDispatchMarkAckedParam update = FileDispatchMarkAckedParam.builder()
        .tenantId(tenantId)
        .fileId(fileId)
        .channelCode(channelCode)
        .dispatchStatus(acknowledgedDispatchStatus)
        .receiptStatus(successfulReceiptStatus)
        .receiptCode(receiptCode)
        .expectedDispatchStatus(expectedDispatchStatus)
        .build();
    return fileDispatchMapper.markAcked(update);
  }

  public int markFailed(
      String tenantId, Long fileId, String channelCode, String errorCode, String errorMessage) {
    String failedDispatchStatus = FileDispatchStatus.FAILED.name();
    String failedReceiptStatus = FileReceiptStatus.FAILED.name();
    FileDispatchMarkFailedParam update = FileDispatchMarkFailedParam.builder()
        .tenantId(tenantId)
        .fileId(fileId)
        .channelCode(channelCode)
        .dispatchStatus(failedDispatchStatus)
        .receiptStatus(failedReceiptStatus)
        .errorCode(errorCode)
        .errorMessage(errorMessage)
        .build();
    return fileDispatchMapper.markFailed(update);
  }

  public int markCompensated(
      String tenantId, Long fileId, String channelCode, String errorCode, String errorMessage) {
    String compensatedDispatchStatus = FileDispatchStatus.COMPENSATED.name();
    String successfulReceiptStatus = FileReceiptStatus.SUCCESS.name();
    String failedReceiptStatus = FileReceiptStatus.FAILED.name();
    FileDispatchMarkCompensatedParam update = FileDispatchMarkCompensatedParam.builder()
        .tenantId(tenantId)
        .fileId(fileId)
        .channelCode(channelCode)
        .dispatchStatus(compensatedDispatchStatus)
        .receiptStatusSuccess(successfulReceiptStatus)
        .receiptStatusFailed(failedReceiptStatus)
        .errorCode(errorCode)
        .errorMessage(errorMessage)
        .build();
    return fileDispatchMapper.markCompensated(update);
  }

  public List<PendingReceiptPollRow> listPendingReceiptPolls(int limit, long maxAgeSeconds) {
    int safe = Math.max(1, Math.min(limit, MAX_DISPATCH_BATCH_SIZE));
    String sentDispatchStatus = FileDispatchStatus.SENT.name();
    String pendingReceiptStatus = FileReceiptStatus.PENDING.name();
    return fileDispatchMapper.listPendingReceiptPolls(
        safe, sentDispatchStatus, pendingReceiptStatus, maxAgeSeconds);
  }
}

package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import io.github.pinpols.batch.common.enums.FileDispatchStatus;
import io.github.pinpols.batch.common.enums.FileReceiptStatus;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchRecordSnapshot;
import io.github.pinpols.batch.worker.dispatchs.domain.PendingReceiptPollRow;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.mapper.FileDispatchMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 文件分发数据仓库，封装分发记录、渠道配置的增删改查操作。 */
@Repository
@RequiredArgsConstructor
public class FileDispatchRepository {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_TENANT_ID = "tenantId";
  private static final String KEY_FILE_ID = "fileId";
  private static final String KEY_CHANNEL_CODE = "channelCode";
  private static final String KEY_JOB_INSTANCE_ID = PipelineRuntimeKeys.JOB_INSTANCE_ID;
  private static final String KEY_PIPELINE_INSTANCE_ID = PipelineRuntimeKeys.PIPELINE_INSTANCE_ID;
  private static final String KEY_DISPATCH_STATUS = "dispatchStatus";
  private static final String KEY_RECEIPT_STATUS = DispatchRuntimeKeys.RECEIPT_STATUS;

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
    Map<String, Object> fileRecord =
        fileDispatchMapper.selectFileRecord(params(KEY_TENANT_ID, tenantId, KEY_FILE_ID, fileId));
    return fileRecord == null ? Map.of() : fileRecord;
  }

  public Map<String, Object> loadChannel(String tenantId, String channelCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(channelCode)) {
      return Map.of();
    }
    Map<String, Object> channelConfig = fileDispatchMapper.selectChannelConfig(
        params(KEY_TENANT_ID, tenantId, KEY_CHANNEL_CODE, channelCode));
    return channelConfig == null ? Map.of() : channelConfig;
  }

  public boolean isChannelActive(String tenantId, String channelCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(channelCode)) {
      return false;
    }
    return fileDispatchMapper.isChannelActive(
        params(KEY_TENANT_ID, tenantId, KEY_CHANNEL_CODE, channelCode));
  }

  public Optional<DispatchRecordSnapshot> latestDispatchRecord(
      String tenantId, Long fileId, String channelCode) {
    if (!Texts.hasText(tenantId) || EmptyChecks.isNull(fileId) || !Texts.hasText(channelCode)) {
      return Optional.empty();
    }
    Map<String, Object> query =
        params(KEY_TENANT_ID, tenantId, KEY_FILE_ID, fileId, KEY_CHANNEL_CODE, channelCode);
    DispatchRecordSnapshot latestRecord = fileDispatchMapper.selectLatestDispatchRecord(query);
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
    Map<String, Object> insert = params(
        KEY_TENANT_ID,
        p.tenantId(),
        KEY_FILE_ID,
        p.fileId(),
        "pipelineInstanceId",
        p.pipelineInstanceId(),
        KEY_CHANNEL_CODE,
        p.channelCode(),
        "dispatchTarget",
        p.dispatchTarget(),
        KEY_DISPATCH_STATUS,
        initialDispatchStatus,
        DispatchRuntimeKeys.RECEIPT_CODE,
        p.receiptCode(),
        KEY_RECEIPT_STATUS,
        p.receiptStatus(),
        DispatchRuntimeKeys.EXTERNAL_REQUEST_ID,
        p.externalRequestId());
    return fileDispatchMapper.insertDispatchRecord(insert);
  }

  public int beginAttempt(String tenantId, Long fileId, String channelCode) {
    Map<String, Object> attemptParams = params(
        KEY_TENANT_ID,
        tenantId,
        KEY_FILE_ID,
        fileId,
        KEY_CHANNEL_CODE,
        channelCode,
        "unknownOutcomeCode",
        TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN);
    return fileDispatchMapper.beginAttempt(attemptParams);
  }

  public int beginVerifiedExternalOutcomeAttempt(
      String tenantId,
      Long fileId,
      String channelCode,
      Long jobInstanceId,
      Long pipelineInstanceId) {
    boolean contextMissing =
        !Texts.hasText(tenantId)
            || EmptyChecks.isNull(fileId)
            || !Texts.hasText(channelCode)
            || EmptyChecks.isNull(jobInstanceId)
            || EmptyChecks.isNull(pipelineInstanceId);
    if (contextMissing) {
      return 0;
    }
    Map<String, Object> attemptParams = params(
        KEY_TENANT_ID,
        tenantId,
        KEY_FILE_ID,
        fileId,
        KEY_CHANNEL_CODE,
        channelCode,
        KEY_JOB_INSTANCE_ID,
        jobInstanceId,
        KEY_PIPELINE_INSTANCE_ID,
        pipelineInstanceId,
        "unknownOutcomeCode",
        TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN);
    return fileDispatchMapper.beginVerifiedExternalOutcomeAttempt(attemptParams);
  }

  public int markAttemptFailed(
      String tenantId, Long fileId, String channelCode, String errorCode, String errorMessage) {
    Map<String, Object> update = params(
        KEY_TENANT_ID,
        tenantId,
        KEY_FILE_ID,
        fileId,
        KEY_CHANNEL_CODE,
        channelCode,
        KEY_DISPATCH_STATUS,
        FileDispatchStatus.FAILED.name(),
        KEY_RECEIPT_STATUS,
        FileReceiptStatus.FAILED.name(),
        "expectedDispatchStatus",
        FileDispatchStatus.CREATED.name(),
        "errorCode",
        errorCode,
        "errorMessage",
        errorMessage);
    return fileDispatchMapper.markAttemptFailed(update);
  }

  public int markSent(
      String tenantId,
      Long fileId,
      String channelCode,
      String externalRequestId,
      String receiptCode,
      String receiptStatus) {
    Map<String, Object> update = params(
        KEY_TENANT_ID,
        tenantId,
        KEY_FILE_ID,
        fileId,
        KEY_CHANNEL_CODE,
        channelCode,
        KEY_DISPATCH_STATUS,
        FileDispatchStatus.SENT.name(),
        DispatchRuntimeKeys.EXTERNAL_REQUEST_ID,
        externalRequestId,
        DispatchRuntimeKeys.RECEIPT_CODE,
        receiptCode,
        KEY_RECEIPT_STATUS,
        receiptStatus,
        "expectedDispatchStatus",
        FileDispatchStatus.CREATED.name());
    return fileDispatchMapper.markSent(update);
  }

  public int markAcked(String tenantId, Long fileId, String channelCode, String receiptCode) {
    String acknowledgedDispatchStatus = FileDispatchStatus.ACKED.name();
    String successfulReceiptStatus = FileReceiptStatus.SUCCESS.name();
    String expectedDispatchStatus = FileDispatchStatus.SENT.name();
    Map<String, Object> update = params(
        KEY_TENANT_ID,
        tenantId,
        KEY_FILE_ID,
        fileId,
        KEY_CHANNEL_CODE,
        channelCode,
        KEY_DISPATCH_STATUS,
        acknowledgedDispatchStatus,
        KEY_RECEIPT_STATUS,
        successfulReceiptStatus,
        DispatchRuntimeKeys.RECEIPT_CODE,
        receiptCode,
        // 行级 CAS 期望前态:只有仍 SENT 才 ACK(防状态倒流)。不卡 receipt_status——
        // 即时确认渠道在 markSent 已把它写成 SUCCESS,卡 PENDING 会让同步 ACK 落空。
        "expectedDispatchStatus",
        expectedDispatchStatus);
    return fileDispatchMapper.markAcked(update);
  }

  public int markFailed(
      String tenantId, Long fileId, String channelCode, String errorCode, String errorMessage) {
    String failedDispatchStatus = FileDispatchStatus.FAILED.name();
    String failedReceiptStatus = FileReceiptStatus.FAILED.name();
    Map<String, Object> update = params(
        KEY_TENANT_ID,
        tenantId,
        KEY_FILE_ID,
        fileId,
        KEY_CHANNEL_CODE,
        channelCode,
        KEY_DISPATCH_STATUS,
        failedDispatchStatus,
        KEY_RECEIPT_STATUS,
        failedReceiptStatus,
        "errorCode",
        errorCode,
        "errorMessage",
        errorMessage);
    return fileDispatchMapper.markFailed(update);
  }

  public int markCompensated(
      String tenantId, Long fileId, String channelCode, String errorCode, String errorMessage) {
    String compensatedDispatchStatus = FileDispatchStatus.COMPENSATED.name();
    String successfulReceiptStatus = FileReceiptStatus.SUCCESS.name();
    String failedReceiptStatus = FileReceiptStatus.FAILED.name();
    Map<String, Object> update = params(
        KEY_TENANT_ID,
        tenantId,
        KEY_FILE_ID,
        fileId,
        KEY_CHANNEL_CODE,
        channelCode,
        KEY_DISPATCH_STATUS,
        compensatedDispatchStatus,
        "receiptStatusSuccess",
        successfulReceiptStatus,
        "receiptStatusFailed",
        failedReceiptStatus,
        "errorCode",
        errorCode,
        "errorMessage",
        errorMessage);
    return fileDispatchMapper.markCompensated(update);
  }

  public List<PendingReceiptPollRow> listPendingReceiptPolls(int limit, long maxAgeSeconds) {
    int safe = Math.max(1, Math.min(limit, MAX_DISPATCH_BATCH_SIZE));
    String sentDispatchStatus = FileDispatchStatus.SENT.name();
    String pendingReceiptStatus = FileReceiptStatus.PENDING.name();
    Map<String, Object> query = params(
        "limit",
        safe,
        KEY_DISPATCH_STATUS,
        sentDispatchStatus,
        KEY_RECEIPT_STATUS,
        pendingReceiptStatus,
        "maxAgeSeconds",
        maxAgeSeconds);
    return fileDispatchMapper.listPendingReceiptPolls(query);
  }

  private Map<String, Object> params(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return values;
  }
}

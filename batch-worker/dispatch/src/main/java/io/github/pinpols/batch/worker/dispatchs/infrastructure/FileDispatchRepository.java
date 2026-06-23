package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import io.github.pinpols.batch.common.enums.FileDispatchStatus;
import io.github.pinpols.batch.common.enums.FileReceiptStatus;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.dispatchs.mapper.FileDispatchMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private static final String KEY_DISPATCH_STATUS = "dispatchStatus";
  private static final String KEY_RECEIPT_STATUS = "receiptStatus";

  private static final int MAX_DISPATCH_BATCH_SIZE = 500;

  private final FileDispatchMapper fileDispatchMapper;

  public Map<String, Object> loadFile(String tenantId, String fileId) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(fileId)) {
      return Map.of();
    }
    return loadFile(tenantId, Long.valueOf(fileId));
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
    Map<String, Object> channelConfig =
        fileDispatchMapper.selectChannelConfig(
            params(KEY_TENANT_ID, tenantId, KEY_CHANNEL_CODE, channelCode));
    return channelConfig == null ? Map.of() : channelConfig;
  }

  public Map<String, Object> loadLatestDispatchRecord(
      String tenantId, Long fileId, String channelCode) {
    if (!Texts.hasText(tenantId) || fileId == null || !Texts.hasText(channelCode)) {
      return Map.of();
    }
    Map<String, Object> dispatchRecord =
        fileDispatchMapper.selectLatestDispatchRecord(
            params(KEY_TENANT_ID, tenantId, KEY_FILE_ID, fileId, KEY_CHANNEL_CODE, channelCode));
    return dispatchRecord == null ? Map.of() : dispatchRecord;
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
    return fileDispatchMapper.insertDispatchRecord(
        params(
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
            FileDispatchStatus.CREATED.name(),
            "receiptCode",
            p.receiptCode(),
            KEY_RECEIPT_STATUS,
            p.receiptStatus(),
            "externalRequestId",
            p.externalRequestId()));
  }

  public int incrementAttempt(String tenantId, Long fileId, String channelCode) {
    return fileDispatchMapper.incrementAttempt(
        params(KEY_TENANT_ID, tenantId, KEY_FILE_ID, fileId, KEY_CHANNEL_CODE, channelCode));
  }

  public int markSent(
      String tenantId,
      Long fileId,
      String channelCode,
      String externalRequestId,
      String receiptCode,
      String receiptStatus) {
    return fileDispatchMapper.markSent(
        params(
            KEY_TENANT_ID,
            tenantId,
            KEY_FILE_ID,
            fileId,
            KEY_CHANNEL_CODE,
            channelCode,
            KEY_DISPATCH_STATUS,
            FileDispatchStatus.SENT.name(),
            "externalRequestId",
            externalRequestId,
            "receiptCode",
            receiptCode,
            KEY_RECEIPT_STATUS,
            receiptStatus));
  }

  public int markAcked(String tenantId, Long fileId, String channelCode, String receiptCode) {
    return fileDispatchMapper.markAcked(
        params(
            KEY_TENANT_ID,
            tenantId,
            KEY_FILE_ID,
            fileId,
            KEY_CHANNEL_CODE,
            channelCode,
            KEY_DISPATCH_STATUS,
            FileDispatchStatus.ACKED.name(),
            KEY_RECEIPT_STATUS,
            FileReceiptStatus.SUCCESS.name(),
            "receiptCode",
            receiptCode,
            // 行级 CAS 期望前态:只有仍 SENT 才 ACK(防状态倒流)。不卡 receipt_status——
            // 即时确认渠道在 markSent 已把它写成 SUCCESS,卡 PENDING 会让同步 ACK 落空。
            "expectedDispatchStatus",
            FileDispatchStatus.SENT.name()));
  }

  public int markFailed(
      String tenantId, Long fileId, String channelCode, String errorCode, String errorMessage) {
    return fileDispatchMapper.markFailed(
        params(
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
            "errorCode",
            errorCode,
            "errorMessage",
            errorMessage));
  }

  public int markCompensated(
      String tenantId, Long fileId, String channelCode, String errorCode, String errorMessage) {
    return fileDispatchMapper.markCompensated(
        params(
            KEY_TENANT_ID,
            tenantId,
            KEY_FILE_ID,
            fileId,
            KEY_CHANNEL_CODE,
            channelCode,
            KEY_DISPATCH_STATUS,
            FileDispatchStatus.COMPENSATED.name(),
            "receiptStatusSuccess",
            FileReceiptStatus.SUCCESS.name(),
            "receiptStatusFailed",
            FileReceiptStatus.FAILED.name(),
            "errorCode",
            errorCode,
            "errorMessage",
            errorMessage));
  }

  public List<Map<String, Object>> listPendingReceiptPolls(int limit, long maxAgeSeconds) {
    int safe = Math.max(1, Math.min(limit, MAX_DISPATCH_BATCH_SIZE));
    return fileDispatchMapper.listPendingReceiptPolls(
        params(
            "limit",
            safe,
            KEY_DISPATCH_STATUS,
            FileDispatchStatus.SENT.name(),
            KEY_RECEIPT_STATUS,
            FileReceiptStatus.PENDING.name(),
            "maxAgeSeconds",
            maxAgeSeconds));
  }

  private Map<String, Object> params(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return values;
  }
}

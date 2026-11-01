package io.github.pinpols.batch.orchestrator.application.service.forensic;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.security.CryptoAlgorithms;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.OwnerOnlyFiles;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayOperationAuditEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ForensicExportLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ForensicExportLogMarkCompletedParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobInstanceForensicByBizDateRangeQuery;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayOperationAuditMapper;
import io.github.pinpols.batch.orchestrator.mapper.ForensicExportLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ADR-022 v0.1 forensic 取证导出服务（精简版 / 同步打包）。
 *
 * <p>v0.1 范围：
 *
 * <ul>
 *   <li>同步落本地 fs（`batch.forensic.storage-dir`），不接 OSS（v0.2）；
 *   <li>snapshot 当前数据，不依赖 *_history 影子表（v0.2）；
 *   <li>覆盖 3 类核心运行证据：job_instances / batch_day_operation_audits（按 calendar+bizDate 范围）/ manifest
 *       元数据；
 *   <li>SHA-256 attestation 当场算入 zip 写出流。
 * </ul>
 *
 * <p>不做（边界已在 ADR-022 §不会做 写明）：
 *
 * <ul>
 *   <li>合规调查工作流 / 法律证据保全；
 *   <li>跨系统统一日志平台 / Splunk / SIEM 对接；
 *   <li>交互式历史浏览 UI；
 *   <li>v0.1 不写运行态表 *_history。
 * </ul>
 *
 * <p>主链路影响 = 0：本服务不在 trigger / claim / report 路径调用，仅由运维 ops 通过 console 主动 pull。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForensicExportService {

  private static final String EXPORT_PREFIX = "fex";

  // Bundle 被 SQL/CLI 读取，业务日和时间必须保持 ISO 文本，不能写 Jackson 数组/数值日期。
  private static final ObjectWriter BUNDLE_JSON = JsonUtils.newDefaultMapper()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .writer();

  private final ForensicExportLogMapper forensicExportLogMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final BatchDayOperationAuditMapper batchDayOperationAuditMapper;
  private final ForensicExportProperties properties;
  private final BatchDateTimeSupport dateTimeSupport;
  private final ForensicExportLogTransactionService transactionService;

  /** 校验导出已完成且文件存在，返回 HTTP 下载适配所需的文件元数据。 */
  public FileDownloadArtifact loadDownloadArtifact(String tenantId, String exportId)
      throws IOException {
    ForensicExportLogEntity exportLog =
        forensicExportLogMapper.selectByExportId(tenantId, exportId);
    if (exportLog == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.forensic.export_not_found");
    }
    if (!"COMPLETED".equals(exportLog.status()) || exportLog.storagePath() == null) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.forensic.export_not_ready");
    }
    Path file = Path.of(exportLog.storagePath());
    if (!Files.exists(file)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.forensic.export_file_missing");
    }
    return new FileDownloadArtifact(file, exportLog.sha256(), Files.size(file));
  }

  public record FileDownloadArtifact(Path path, String sha256, long contentLength) {}

  /** v0.1 同步打包：插 PROCESSING → 落盘 + sha256 → markCompleted；失败时 markFailed 后 rethrow。 */
  public ForensicExportResponse export(ForensicExportRequest request) {
    if (!properties.isEnabled()) {
      throw BizException.of(ResultCode.SERVICE_UNAVAILABLE, "error.forensic.disabled");
    }
    validate(request);

    String exportId = IdGenerator.newBusinessNo(EXPORT_PREFIX);
    Instant requestedAt = dateTimeSupport.nowInstant();
    String format = Texts.hasText(request.exportFormat()) ? request.exportFormat() : "BUNDLE";

    transactionService.insertProcessingRow(request, exportId, format, requestedAt);

    try {
      ExportResult result = doExport(request, exportId);
      String tenantId = request.tenantId();
      String exportPath = result.path().toString();
      long sizeBytes = result.sizeBytes();
      String sha256 = result.sha256();
      String rowCountsJson = JsonUtils.toJson(result.rowCounts());
      Instant completedAt = dateTimeSupport.nowInstant();
      ForensicExportLogMarkCompletedParam forensicExportLogMarkCompletedParam =
          ForensicExportLogMarkCompletedParam.builder()
              .tenantId(tenantId)
              .exportId(exportId)
              .storagePath(exportPath)
              .fileSizeBytes(sizeBytes)
              .sha256(sha256)
              .rowCountsJson(rowCountsJson)
              .completedAt(completedAt)
              .build();
      forensicExportLogMapper.markCompleted(forensicExportLogMarkCompletedParam);
      return new ForensicExportResponse(exportId, "COMPLETED", exportPath, sizeBytes, sha256, null);
    } catch (RuntimeException | IOException | NoSuchAlgorithmException e) {
      log.warn("forensic export failed exportId={}", exportId, e);
      String tenantId = request.tenantId();
      String failureMessage = e.getMessage();
      String truncatedFailureMessage = truncate(failureMessage, 2000);
      Instant failedAt = dateTimeSupport.nowInstant();
      forensicExportLogMapper.markFailed(tenantId, exportId, truncatedFailureMessage, failedAt);
      if (e instanceof RuntimeException re) {
        throw re;
      }
      throw BizException.of(
          ResultCode.SYSTEM_ERROR, "error.forensic.export_failed", e.getMessage());
    }
  }

  private void validate(ForensicExportRequest request) {
    boolean exportRequestIsIncomplete = EmptyChecks.isNull(request)
        || !Texts.hasText(request.tenantId())
        || request.bizDateFrom() == null
        || EmptyChecks.isNull(request.bizDateTo());
    if (exportRequestIsIncomplete) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.forensic.invalid_argument");
    }
    if (request.bizDateFrom().isAfter(request.bizDateTo())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.forensic.invalid_date_range");
    }
    long dateRangeDays = ChronoUnit.DAYS.between(request.bizDateFrom(), request.bizDateTo()) + 1;
    if (dateRangeDays > properties.getMaxDateRangeDays()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.forensic.date_range_too_large");
    }
  }

  private ExportResult doExport(ForensicExportRequest request, String exportId)
      throws IOException, NoSuchAlgorithmException {
    // 取证包包含租户运行证据;私有权限必须在写入前生效,不能依赖宿主机 umask。
    Path storageDir = OwnerOnlyFiles.createDirectories(Path.of(properties.getStorageDir()));

    Map<String, Integer> rowCounts = new LinkedHashMap<>();
    MessageDigest digest = MessageDigest.getInstance(CryptoAlgorithms.SHA_256);

    Path outFile = OwnerOnlyFiles.createFile(storageDir.resolve(exportId + ".zip"));
    try (OutputStream raw =
            Files.newOutputStream(outFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        DigestOutputStream digesting = new DigestOutputStream(raw, digest);
        ZipOutputStream zip = new ZipOutputStream(digesting, StandardCharsets.UTF_8)) {

      // 1) job_instances
      JobInstanceForensicByBizDateRangeQuery jobInstanceForensicByBizDateRangeQuery =
          JobInstanceForensicByBizDateRangeQuery.builder()
              .tenantId(request.tenantId())
              .bizDateFrom(request.bizDateFrom())
              .bizDateTo(request.bizDateTo())
              .jobCodes(request.jobCodes())
              .limit(properties.getInstanceRowCap())
              .build();
      List<JobInstanceEntity> instances =
          jobInstanceMapper.selectForensicByBizDateRange(jobInstanceForensicByBizDateRangeQuery);
      String instancesJson = BUNDLE_JSON.writeValueAsString(instances);
      writeEntry(zip, "job-instances.json", instancesJson);
      rowCounts.put("job_instances", instances.size());

      // 2) batch_day_operation_audits（按 bizDate 拆 — 只对 calendar 跨度内每天捞一次）
      List<BatchDayOperationAuditEntity> allAudits = new ArrayList<>();
      for (LocalDate cursor = request.bizDateFrom();
          !cursor.isAfter(request.bizDateTo());
          cursor = cursor.plusDays(1)) {
        int remaining = properties.getAuditRowCap() - allAudits.size();
        if (remaining <= 0) {
          break;
        }
        // calendar_code 在 v0.1 不参与过滤 — 同 tenant 同 bizDate 下所有 calendar 的治理动作一并取
        // limit 1000 / 天足以覆盖正常运维节奏；总量再受 auditRowCap 保护
        int dailyAuditLimit = Math.min(1000, remaining);
        List<BatchDayOperationAuditEntity> dailyAudits =
            batchDayOperationAuditMapper.selectByCalendarBizDate(
                request.tenantId(), null, cursor, dailyAuditLimit);
        allAudits.addAll(dailyAudits);
      }
      String auditsJson = BUNDLE_JSON.writeValueAsString(allAudits);
      writeEntry(zip, "batch-day-operation-audits.json", auditsJson);
      rowCounts.put("batch_day_operation_audits", allAudits.size());

      // 3) manifest（最后写，含 sha256 占位 — sha256 是 zip 整体哈希含 manifest 自身，
      //    所以 manifest 里只放 metadata，不预先哈希自身）
      Map<String, Object> manifest = new LinkedHashMap<>();
      manifest.put("adr", "ADR-022 v0.1");
      manifest.put("exportId", exportId);
      manifest.put("tenantId", request.tenantId());
      manifest.put("bizDateFrom", request.bizDateFrom().toString());
      manifest.put("bizDateTo", request.bizDateTo().toString());
      manifest.put("jobCodes", request.jobCodes());
      manifest.put("rowCap", properties.getInstanceRowCap());
      manifest.put("auditRowCap", properties.getAuditRowCap());
      manifest.put("maxDateRangeDays", properties.getMaxDateRangeDays());
      manifest.put("rowCounts", rowCounts);
      Instant generatedAt = dateTimeSupport.nowInstant();
      String generatedAtText = generatedAt.toString();
      manifest.put("generatedAt", generatedAtText);
      manifest.put("requestedBy", request.requestedBy());
      manifest.put("traceId", request.traceId());
      String manifestJson = BUNDLE_JSON.writeValueAsString(manifest);
      writeEntry(zip, "manifest.json", manifestJson);
    }

    long size = Files.size(outFile);
    byte[] digestBytes = digest.digest();
    String sha = HexFormat.of().formatHex(digestBytes);
    return new ExportResult(outFile, size, sha, rowCounts);
  }

  private void writeEntry(ZipOutputStream zip, String entryName, String content)
      throws IOException {
    zip.putNextEntry(new ZipEntry(entryName));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  /** 内部值对象：zip 落盘后 path / size / sha256 / 各 scope 行数。 */
  public record ExportResult(
      Path path, long sizeBytes, String sha256, Map<String, Integer> rowCounts) {}
}

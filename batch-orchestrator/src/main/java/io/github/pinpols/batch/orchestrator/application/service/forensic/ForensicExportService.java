package io.github.pinpols.batch.orchestrator.application.service.forensic;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayOperationAuditEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ForensicExportLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayOperationAuditMapper;
import io.github.pinpols.batch.orchestrator.mapper.ForensicExportLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

  private final ForensicExportLogMapper forensicExportLogMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final BatchDayOperationAuditMapper batchDayOperationAuditMapper;
  private final ForensicExportProperties properties;
  private final BatchDateTimeSupport dateTimeSupport;

  /** 查询 forensic export 日志 — Controller 不直调 Mapper(分层约束)。 */
  public ForensicExportLogEntity findLog(String tenantId, String exportId) {
    return forensicExportLogMapper.selectByExportId(tenantId, exportId);
  }

  /** v0.1 同步打包：插 PROCESSING → 落盘 + sha256 → markCompleted；失败时 markFailed 后 rethrow。 */
  public ForensicExportResponse export(ForensicExportRequest request) {
    if (!properties.isEnabled()) {
      throw BizException.of(ResultCode.SERVICE_UNAVAILABLE, "error.forensic.disabled");
    }
    validate(request);

    String exportId = IdGenerator.newBusinessNo(EXPORT_PREFIX);
    Instant requestedAt = dateTimeSupport.nowInstant();
    String format = Texts.hasText(request.exportFormat()) ? request.exportFormat() : "BUNDLE";

    insertProcessingRow(request, exportId, format, requestedAt);

    try {
      ExportResult result = doExport(request, exportId);
      forensicExportLogMapper.markCompleted(
          request.tenantId(),
          exportId,
          result.path().toString(),
          result.sizeBytes(),
          result.sha256(),
          JsonUtils.toJson(result.rowCounts()),
          dateTimeSupport.nowInstant());
      return new ForensicExportResponse(
          exportId,
          "COMPLETED",
          result.path().toString(),
          result.sizeBytes(),
          result.sha256(),
          null);
    } catch (RuntimeException | IOException | NoSuchAlgorithmException e) {
      log.warn("forensic export failed exportId={}: {}", exportId, e.getMessage(), e);
      forensicExportLogMapper.markFailed(
          request.tenantId(),
          exportId,
          truncate(e.getMessage(), 2000),
          dateTimeSupport.nowInstant());
      if (e instanceof RuntimeException re) {
        throw re;
      }
      throw BizException.of(
          ResultCode.SYSTEM_ERROR, "error.forensic.export_failed", e.getMessage());
    }
  }

  /** insert PROCESSING 行使用独立事务，确保即使后续打包失败也有 audit 痕迹。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  protected void insertProcessingRow(
      ForensicExportRequest request, String exportId, String format, Instant requestedAt) {
    String scopeJson =
        JsonUtils.toJson(List.of("job_instances", "batch_day_operation_audits", "manifest"));
    String jobCodesJson =
        request.jobCodes() == null || request.jobCodes().isEmpty()
            ? null
            : JsonUtils.toJson(request.jobCodes());
    forensicExportLogMapper.insert(
        ForensicExportLogEntity.builder()
            .tenantId(request.tenantId())
            .exportId(exportId)
            .bizDateFrom(request.bizDateFrom())
            .bizDateTo(request.bizDateTo())
            .jobCodesJson(jobCodesJson)
            .scopeJson(scopeJson)
            .exportFormat(format)
            .status("PROCESSING")
            .requestedBy(Texts.hasText(request.requestedBy()) ? request.requestedBy() : "UNKNOWN")
            .requestedAt(requestedAt)
            .traceId(request.traceId())
            .build());
  }

  private void validate(ForensicExportRequest request) {
    if (request == null
        || !Texts.hasText(request.tenantId())
        || request.bizDateFrom() == null
        || request.bizDateTo() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.forensic.invalid_argument");
    }
    if (request.bizDateFrom().isAfter(request.bizDateTo())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.forensic.invalid_date_range");
    }
  }

  private ExportResult doExport(ForensicExportRequest request, String exportId)
      throws IOException, NoSuchAlgorithmException {
    Path storageDir = Path.of(properties.getStorageDir());
    Files.createDirectories(storageDir);
    Path outFile = storageDir.resolve(exportId + ".zip");

    Map<String, Integer> rowCounts = new LinkedHashMap<>();
    MessageDigest digest = MessageDigest.getInstance("SHA-256");

    try (OutputStream raw = Files.newOutputStream(outFile);
        DigestOutputStream digesting = new DigestOutputStream(raw, digest);
        ZipOutputStream zip = new ZipOutputStream(digesting, StandardCharsets.UTF_8)) {

      // 1) job_instances
      List<JobInstanceEntity> instances =
          jobInstanceMapper.selectForensicByBizDateRange(
              request.tenantId(),
              request.bizDateFrom(),
              request.bizDateTo(),
              request.jobCodes(),
              properties.getInstanceRowCap());
      writeEntry(zip, "job-instances.json", JsonUtils.toJson(instances));
      rowCounts.put("job_instances", instances.size());

      // 2) batch_day_operation_audits（按 bizDate 拆 — 只对 calendar 跨度内每天捞一次）
      List<BatchDayOperationAuditEntity> allAudits = new ArrayList<>();
      for (LocalDate cursor = request.bizDateFrom();
          !cursor.isAfter(request.bizDateTo());
          cursor = cursor.plusDays(1)) {
        // calendar_code 在 v0.1 不参与过滤 — 同 tenant 同 bizDate 下所有 calendar 的治理动作一并取
        // limit 1000 / 天足以覆盖正常运维节奏；超出表示有人在批量手动操作 → 单独审计
        allAudits.addAll(
            batchDayOperationAuditMapper.selectByCalendarBizDate(
                request.tenantId(), null, cursor, 1000));
      }
      writeEntry(zip, "batch-day-operation-audits.json", JsonUtils.toJson(allAudits));
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
      manifest.put("rowCounts", rowCounts);
      manifest.put("generatedAt", dateTimeSupport.nowInstant().toString());
      manifest.put("requestedBy", request.requestedBy());
      manifest.put("traceId", request.traceId());
      writeEntry(zip, "manifest.json", JsonUtils.toJson(manifest));
    }

    long size = Files.size(outFile);
    String sha = HexFormat.of().formatHex(digest.digest());
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

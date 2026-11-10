package io.github.pinpols.batch.orchestrator.infrastructure.sensor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.enums.SensorType;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipIdentity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.common.validation.FileArrivalChannelCode;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorContext;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorPolicy;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorProbeResult;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorSpecs;
import io.github.pinpols.batch.orchestrator.domain.query.SensorFileArrivalLatestArrivalQuery;
import io.github.pinpols.batch.orchestrator.domain.view.SensorFileArrivalView;
import io.github.pinpols.batch.orchestrator.mapper.SensorFileArrivalMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * FILE_ARRIVAL sensor：等待 batch.file_record 出现匹配 (file_name LIKE pattern, age &lt;= maxAgeSeconds) 的
 * INPUT 行。
 *
 * <p>sensor_spec：
 *
 * <pre>{@code
 * {
 *   "channelCode": "sftp_bank_in",   // 可选，匹配可信渠道身份；迁移前历史记录兼容旧来源类型
 *   "pattern": "settle-*.csv",       // 必填，glob 风格，* → SQL LIKE %
 *   "maxAgeSeconds": 3600            // 必填，文件 created_at 必须在 now - maxAgeSeconds 之内
 * }
 * }</pre>
 *
 * <p>output：{@code fileId / fileName / arrivalTime / storagePath}，命中后下游可走 {@code
 * $.nodes.<WAIT_CODE>.output.fileId}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileArrivalSensorPolicy implements SensorPolicy {
  private static final String SENSOR_SPEC_INVALID = "error.workflow.sensor_spec_invalid";

  private static final String SENSOR_TYPE_CODE = SensorType.FILE_ARRIVAL.code();
  private static final ObjectMapper OWNERSHIP_IDENTITY_MAPPER = JsonUtils.newDefaultMapper();

  private final SensorFileArrivalMapper mapper;
  private final ImportIngressOwnershipIdentity ownershipIdentity;
  private final S3StorageProperties storageProperties;

  @Override
  public SensorType type() {
    return SensorType.FILE_ARRIVAL;
  }

  @Override
  public SensorProbeResult probe(SensorContext ctx) {
    Map<String, Object> spec = ctx.sensorSpec();
    String pattern = SensorSpecs.string(spec, "pattern");
    Long maxAgeSeconds = SensorSpecs.longValue(spec, "maxAgeSeconds");
    String fileGroupCode = SensorSpecs.string(spec, "fileGroupCode");
    boolean requireCompleteGroup = Boolean.TRUE.equals(spec.get("requireCompleteGroup"));
    boolean bindWorkflowBizDate = Boolean.TRUE.equals(spec.get("bindWorkflowBizDate"));
    boolean fileArrivalFreshnessCheckIsUnavailable =
        !Texts.hasText(pattern) || EmptyChecks.isNull(maxAgeSeconds) || maxAgeSeconds <= 0;
    if (fileArrivalFreshnessCheckIsUnavailable) {
      return SensorProbeResult.error(
          SENSOR_SPEC_INVALID, List.of(SENSOR_TYPE_CODE, "pattern/maxAgeSeconds required"));
    }

    String configuredBizDate = SensorSpecs.string(spec, "bizDate");
    if (bindWorkflowBizDate && Texts.hasText(configuredBizDate)) {
      return SensorProbeResult.error(
          SENSOR_SPEC_INVALID,
          List.of(SENSOR_TYPE_CODE, "configure either bizDate or bindWorkflowBizDate"));
    }
    String workflowBizDate =
        bindWorkflowBizDate ? Objects.toString(ctx.workflowRunVars().get("bizDate"), null) : null;
    if (bindWorkflowBizDate && !Texts.hasText(workflowBizDate)) {
      return SensorProbeResult.error(
          SENSOR_SPEC_INVALID, List.of(SENSOR_TYPE_CODE, "Workflow run bizDate is required"));
    }
    LocalDate bizDate;
    try {
      String effectiveBizDate =
          Texts.hasText(configuredBizDate) ? configuredBizDate : workflowBizDate;
      bizDate = Texts.hasText(effectiveBizDate) ? LocalDate.parse(effectiveBizDate) : null;
    } catch (DateTimeParseException invalidDate) {
      return SensorProbeResult.error(
          SENSOR_SPEC_INVALID, List.of(SENSOR_TYPE_CODE, "bizDate must be ISO-8601"));
    }
    if (requireCompleteGroup && !Texts.hasText(fileGroupCode)) {
      return SensorProbeResult.error(
          SENSOR_SPEC_INVALID,
          List.of(SENSOR_TYPE_CODE, "fileGroupCode required when requireCompleteGroup=true"));
    }
    if (requireCompleteGroup && EmptyChecks.isNull(bizDate)) {
      return SensorProbeResult.error(
          SENSOR_SPEC_INVALID,
          List.of(
              SENSOR_TYPE_CODE,
              "bizDate or bindWorkflowBizDate required when requireCompleteGroup=true"));
    }
    if (requireCompleteGroup && EmptyChecks.isNull(ctx.workflowDefinitionId())) {
      return SensorProbeResult.error(
          SENSOR_SPEC_INVALID,
          List.of(SENSOR_TYPE_CODE, "Workflow definition is required for a complete file group"));
    }

    // SQL LIKE 的通配符和转义符必须按字面处理,只有 sensor 的星号表示任意字符。
    String sqlPattern =
        pattern.replace("!", "!!").replace("%", "!%").replace("_", "!_").replace('*', '%');
    Object configuredChannelCode = spec.get("channelCode");
    if (!FileArrivalChannelCode.isValid(configuredChannelCode)) {
      return SensorProbeResult.error(
          SENSOR_SPEC_INVALID,
          List.of(SENSOR_TYPE_CODE, "channelCode must be a string up to 128 characters"));
    }
    String channelCode = (String) configuredChannelCode;
    channelCode = Texts.hasText(channelCode) ? channelCode : null;
    String sourceType = mapChannelToSourceType(channelCode);
    OffsetDateTime since =
        BatchDateTimeSupport.utcNow().minusSeconds(maxAgeSeconds).atOffset(ZoneOffset.UTC);

    try {
      SensorFileArrivalLatestArrivalQuery sensorFileArrivalLatestArrivalQuery =
          SensorFileArrivalLatestArrivalQuery.builder()
              .tenantId(ctx.tenantId())
              .namePattern(sqlPattern)
              .sourceType(sourceType)
              .channelCode(channelCode)
              .since(since)
              .bizDate(bizDate)
              .fileGroupCode(fileGroupCode)
              .workflowDefinitionId(ctx.workflowDefinitionId())
              .storageId(ownershipIdentity.storageId(storageProperties, OWNERSHIP_IDENTITY_MAPPER))
              .requireCompleteGroup(requireCompleteGroup)
              .build();
      SensorFileArrivalView hit = mapper.selectLatestArrival(sensorFileArrivalLatestArrivalQuery);
      if (hit == null) {
        return SensorProbeResult.notYet();
      }
      Map<String, Object> output = hit.toProbeOutput();
      if (EmptyChecks.isNotNull(channelCode) && Boolean.TRUE.equals(hit.legacyChannelFallback())) {
        log.warn(
            "FILE_ARRIVAL matched a legacy file without verified channel tenant={} fileId={}",
            ctx.tenantId(),
            hit.fileId());
        output.put("legacyChannelFallback", true);
      }
      return SensorProbeResult.matched(output);
    } catch (Exception e) {
      log.warn(
          "FILE_ARRIVAL probe error tenant={} pattern={} err={}",
          ctx.tenantId(),
          pattern,
          SwallowedExceptionLogger.summary(e));
      String failureDetail =
          Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
      return SensorProbeResult.error(
          "error.workflow.sensor_probe_failed", List.of(SENSOR_TYPE_CODE, failureDetail));
    }
  }

  /** 仅供迁移前历史文件沿用原来源类型回退语义。 */
  private static String mapChannelToSourceType(String channelCode) {
    if (!Texts.hasText(channelCode)) {
      return null;
    }
    String lower = channelCode.toLowerCase(Locale.ROOT);
    if (lower.startsWith("sftp")) {
      return "SFTP";
    }
    if (lower.startsWith("api")) {
      return "API";
    }
    if (lower.startsWith("upload")) {
      return "UPLOAD";
    }
    return null;
  }
}

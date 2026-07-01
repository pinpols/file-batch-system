package io.github.pinpols.batch.orchestrator.infrastructure.sensor;

import io.github.pinpols.batch.common.enums.SensorType;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorContext;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorPolicy;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorProbeResult;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorSpecs;
import io.github.pinpols.batch.orchestrator.mapper.SensorFileArrivalMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *   "channelCode": "sftp_bank_in",   // 可选，对应 source_type；当前实现取该字段第一段映射 (SFTP/API/...)
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

  private final SensorFileArrivalMapper mapper;

  @Override
  public SensorType type() {
    return SensorType.FILE_ARRIVAL;
  }

  @Override
  public SensorProbeResult probe(SensorContext ctx) {
    Map<String, Object> spec = ctx.sensorSpec();
    String pattern = SensorSpecs.string(spec, "pattern");
    Long maxAgeSeconds = SensorSpecs.longValue(spec, "maxAgeSeconds");
    if (!Texts.hasText(pattern) || maxAgeSeconds == null || maxAgeSeconds <= 0) {
      return SensorProbeResult.error(
          "error.workflow.sensor_spec_invalid",
          List.of("FILE_ARRIVAL", "pattern/maxAgeSeconds required"));
    }

    String sqlPattern = pattern.replace('*', '%');
    String sourceType = mapChannelToSourceType(SensorSpecs.string(spec, "channelCode"));
    OffsetDateTime since =
        BatchDateTimeSupport.utcNow().minusSeconds(maxAgeSeconds).atOffset(ZoneOffset.UTC);

    try {
      Map<String, Object> hit =
          mapper.selectLatestArrival(ctx.tenantId(), sqlPattern, sourceType, since);
      if (hit == null) {
        return SensorProbeResult.notYet();
      }
      return SensorProbeResult.matched(hit);
    } catch (Exception e) {
      log.warn(
          "FILE_ARRIVAL probe error tenant={} pattern={} err={}",
          ctx.tenantId(),
          pattern,
          e.getMessage());
      return SensorProbeResult.error(
          "error.workflow.sensor_probe_failed", List.of("FILE_ARRIVAL", e.getMessage()));
    }
  }

  /** channelCode 映射到 file_record.source_type；channelCode 为空则 null（不过滤）。 */
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

package io.github.pinpols.batch.orchestrator.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 文件治理指标缓存服务。
 *
 * <p>负责计算并缓存租户维度的文件到达延迟与处理延迟指标（违约数量、最大延迟秒数及延迟样本）， 使用 Redis Hash 存储，TTL 固定为 60 秒（{@code
 * METRICS_TTL}）。 {@link #load} 优先从缓存读取，未命中时调用 {@link #compute} 查库计算后通过 {@link #write} 回填。 指标字段以
 * JSON 序列化存入 Hash，读取时按字段名反序列化为原始类型或 {@code List<Map>}。
 */
@Service
@RequiredArgsConstructor
public class FileGovernanceMetricsCacheService {

  private static final Duration METRICS_TTL = Duration.ofSeconds(60);

  private final OrchestratorRedisSupport redis;
  private final FileGovernanceRepository fileGovernanceRepository;
  private final ObjectMapper objectMapper;

  public Map<String, Object> load(
      String tenantId,
      long arrivalThresholdSeconds,
      long processingThresholdSeconds,
      long processingMaxAgeSeconds,
      int sampleSize) {
    if (!Texts.hasText(tenantId)) {
      return Map.of();
    }
    String key = BatchRedisKeys.fileGovernanceMetrics(tenantId);
    Map<Object, Object> cached = redis.entries(key);
    if (!cached.isEmpty()) {
      return toResponse(cached);
    }
    Map<String, Object> computed =
        compute(
            tenantId,
            arrivalThresholdSeconds,
            processingThresholdSeconds,
            processingMaxAgeSeconds,
            sampleSize);
    write(tenantId, computed);
    return computed;
  }

  public Map<String, Object> compute(
      String tenantId,
      long arrivalThresholdSeconds,
      long processingThresholdSeconds,
      long processingMaxAgeSeconds,
      int sampleSize) {
    long arrivalCount =
        fileGovernanceRepository.countArrivalDelayViolations(tenantId, arrivalThresholdSeconds);
    long arrivalMax = fileGovernanceRepository.maxArrivalDelaySeconds(tenantId);
    long processingCount =
        fileGovernanceRepository.countProcessingDelayViolations(
            tenantId, processingThresholdSeconds, processingMaxAgeSeconds);
    long processingMax =
        fileGovernanceRepository.maxProcessingDelaySeconds(tenantId, processingMaxAgeSeconds);
    List<Map<String, Object>> arrivalSamples =
        arrivalCount > 0
            ? fileGovernanceRepository.selectArrivalDelaySamples(
                tenantId, arrivalThresholdSeconds, sampleSize)
            : List.of();
    List<Map<String, Object>> processingSamples =
        processingCount > 0
            ? fileGovernanceRepository.selectProcessingDelaySamples(
                tenantId, processingThresholdSeconds, processingMaxAgeSeconds, sampleSize)
            : List.of();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("tenantId", tenantId);
    response.put("arrivalDelayViolations", arrivalCount);
    response.put("maxArrivalDelaySeconds", arrivalMax);
    response.put("processingDelayViolations", processingCount);
    response.put("maxProcessingDelaySeconds", processingMax);
    response.put("arrivalDelaySamples", arrivalSamples);
    response.put("processingDelaySamples", processingSamples);
    return response;
  }

  public void write(String tenantId, Map<String, Object> metrics) {
    if (!Texts.hasText(tenantId) || metrics == null || metrics.isEmpty()) {
      return;
    }
    Map<String, String> hash = new LinkedHashMap<>();
    metrics.forEach((key, value) -> hash.put(key, writeJson(key, value)));
    redis.putHashAll(BatchRedisKeys.fileGovernanceMetrics(tenantId), hash, METRICS_TTL);
  }

  private Map<String, Object> toResponse(Map<Object, Object> cached) {
    Map<String, Object> response = new LinkedHashMap<>();
    cached.forEach(
        (key, value) ->
            response.put(
                String.valueOf(key), readJson(String.valueOf(key), String.valueOf(value))));
    return response;
  }

  private String writeJson(String key, Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Failed to serialize file governance metrics field: " + key, exception);
    }
  }

  private Object readJson(String key, String value) {
    try {
      return switch (key) {
        case "arrivalDelaySamples", "processingDelaySamples" ->
            objectMapper.readValue(value, new TypeReference<List<Map<String, Object>>>() {});
        default -> objectMapper.readValue(value, Object.class);
      };
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Failed to deserialize file governance metrics field: " + key, exception);
    }
  }
}

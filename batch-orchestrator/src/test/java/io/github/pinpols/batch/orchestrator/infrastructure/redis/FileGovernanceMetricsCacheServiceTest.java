package io.github.pinpols.batch.orchestrator.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("文件治理指标缓存服务: 租户参数校验, 缓存命中复用与未命中回源计算")
class FileGovernanceMetricsCacheServiceTest {

  @Mock
  private OrchestratorRedisSupport redis;

  @Mock
  private FileGovernanceRepository fileGovernanceRepository;

  private FileGovernanceMetricsCacheService service;

  @BeforeEach
  void setUp() {
    service =
        new FileGovernanceMetricsCacheService(redis, fileGovernanceRepository, new ObjectMapper());
  }

  @Test
  @DisplayName("租户标识为空时直接返回空结果, 不读取缓存条目")
  void shouldReturnEmptyMap_whenTenantIdBlank() {
    Map<String, Object> result = service.load("", 600, 900, 604800, 10);

    assertThat(result).isEmpty();
    verify(redis, never()).entries(anyString());
  }

  @Test
  @DisplayName("缓存命中时直接复用哈希条目, 不再回源统计违规次数")
  void shouldReturnCachedEntries_whenCacheHit() {
    Map<Object, Object> cached = Map.of(
        "tenantId", "\"t1\"",
        "arrivalDelayViolations", "2",
        "maxArrivalDelaySeconds", "3600",
        "processingDelayViolations", "0",
        "maxProcessingDelaySeconds", "0",
        "arrivalDelaySamples", "[]",
        "processingDelaySamples", "[]");
    when(redis.entries(anyString())).thenReturn(cached);

    Map<String, Object> result = service.load("t1", 600, 900, 604800, 10);

    assertThat(result).isNotEmpty();
    verify(fileGovernanceRepository, never()).countArrivalDelayViolations(anyString(), anyLong());
  }

  @Test
  @DisplayName("缓存未命中时回源统计违规次数与最大延迟, 并把结果写入缓存")
  void shouldComputeAndCache_whenCacheMiss() {
    when(redis.entries(anyString())).thenReturn(Map.of());
    when(fileGovernanceRepository.countArrivalDelayViolations(anyString(), anyLong()))
        .thenReturn(1L);
    when(fileGovernanceRepository.maxArrivalDelaySeconds(anyString())).thenReturn(7200L);
    when(fileGovernanceRepository.countProcessingDelayViolations(anyString(), anyLong(), anyLong()))
        .thenReturn(0L);
    when(fileGovernanceRepository.maxProcessingDelaySeconds(anyString(), anyLong()))
        .thenReturn(0L);
    when(fileGovernanceRepository.selectArrivalDelaySamples(anyString(), anyLong(), anyInt()))
        .thenReturn(List.of(Map.of("file_name", "f.csv")));

    Map<String, Object> result = service.load("t1", 600, 900, 604800, 10);

    assertThat(result).containsKey("arrivalDelayViolations");
    assertThat(((Number) result.get("arrivalDelayViolations")).longValue()).isEqualTo(1L);
    verify(redis).putHashAll(anyString(), any(), any());
  }

  @Test
  @DisplayName("指标集合为空时跳过写入, 不产生任何缓存写入")
  void shouldSkipWrite_whenMetricsEmpty() {
    service.write("t1", Map.of());

    verify(redis, never()).putHashAll(anyString(), any(), any());
  }
}

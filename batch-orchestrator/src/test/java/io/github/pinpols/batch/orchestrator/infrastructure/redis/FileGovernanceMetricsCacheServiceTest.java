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
import io.github.pinpols.batch.orchestrator.domain.view.FileGovernanceViews;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceLatencyMetrics;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    FileGovernanceLatencyMetrics result = service.load("", 600, 900, 604800, 10);

    assertThat(result).isEqualTo(FileGovernanceLatencyMetrics.empty(null));
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

    FileGovernanceLatencyMetrics result = service.load("t1", 600, 900, 604800, 10);

    assertThat(result.tenantId()).isEqualTo("t1");
    assertThat(result.arrivalDelayViolations()).isEqualTo(2L);
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
        .thenReturn(1L);
    when(fileGovernanceRepository.maxProcessingDelaySeconds(anyString(), anyLong()))
        .thenReturn(0L);
    when(fileGovernanceRepository.selectArrivalDelaySamples(anyString(), anyLong(), anyInt()))
        .thenReturn(List.of(new FileGovernanceViews.ArrivalDelaySampleRow(
            "t1", 42L, "f.csv", null, "12:00", 7200L)));
    when(fileGovernanceRepository.selectProcessingDelaySamples(
            anyString(), anyLong(), anyLong(), anyInt()))
        .thenReturn(List.of(new FileGovernanceViews.ProcessingDelaySampleRow(
            "t1", 19L, "job-a", "IMPORT", 88L, null, 3600L)));

    FileGovernanceLatencyMetrics result = service.load("t1", 600, 900, 604800, 10);

    assertThat(result.arrivalDelayViolations()).isEqualTo(1L);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
    verify(redis).putHashAll(anyString(), fields.capture(), any());
    assertThat(fields.getValue())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "tenantId", "\"t1\"",
                "arrivalDelayViolations", "1",
                "maxArrivalDelaySeconds", "7200",
                "processingDelayViolations", "1",
                "maxProcessingDelaySeconds", "0",
                "arrivalDelaySamples",
                    "[{\"tenant_id\":\"t1\",\"id\":42,\"file_name\":\"f.csv\",\"created_at\":null,\"expected_arrival_time\":\"12:00\",\"arrival_delay_seconds\":7200}]",
                "processingDelaySamples",
                    "[{\"tenant_id\":\"t1\",\"id\":19,\"job_code\":\"job-a\",\"pipeline_type\":\"IMPORT\",\"related_job_instance_id\":88,\"started_at\":null,\"processing_delay_seconds\":3600}]"));
  }

  @Test
  @DisplayName("指标为空时跳过写入, 不产生任何缓存写入")
  void shouldSkipWrite_whenMetricsNull() {
    service.write("t1", null);

    verify(redis, never()).putHashAll(anyString(), any(), any());
  }
}

package com.example.batch.orchestrator.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FileGovernanceMetricsCacheServiceTest {

  @Mock private OrchestratorRedisSupport redis;
  @Mock private FileGovernanceRepository fileGovernanceRepository;

  private FileGovernanceMetricsCacheService service;

  @BeforeEach
  void setUp() {
    service =
        new FileGovernanceMetricsCacheService(redis, fileGovernanceRepository, new ObjectMapper());
  }

  @Test
  void blankTenantIdReturnsEmptyMap() {
    Map<String, Object> result = service.load("", 600, 900, 604800, 10);

    assertThat(result).isEmpty();
    verify(redis, never()).entries(anyString());
  }

  @Test
  void cacheHitSkipsComputeAndReturnsHashEntries() {
    Map<Object, Object> cached =
        Map.of(
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
  void cacheMissComputesAndWritesToRedis() {
    when(redis.entries(anyString())).thenReturn(Map.of());
    when(fileGovernanceRepository.countArrivalDelayViolations(anyString(), anyLong()))
        .thenReturn(1L);
    when(fileGovernanceRepository.maxArrivalDelaySeconds(anyString())).thenReturn(7200L);
    when(fileGovernanceRepository.countProcessingDelayViolations(anyString(), anyLong(), anyLong()))
        .thenReturn(0L);
    when(fileGovernanceRepository.maxProcessingDelaySeconds(anyString(), anyLong())).thenReturn(0L);
    when(fileGovernanceRepository.selectArrivalDelaySamples(anyString(), anyLong(), anyInt()))
        .thenReturn(List.of(Map.of("file_name", "f.csv")));

    Map<String, Object> result = service.load("t1", 600, 900, 604800, 10);

    assertThat(result).containsKey("arrivalDelayViolations");
    assertThat(((Number) result.get("arrivalDelayViolations")).longValue()).isEqualTo(1L);
    verify(redis).putHashAll(anyString(), any(), any());
  }

  @Test
  void writeSkipsWhenMetricsMapIsEmpty() {
    service.write("t1", Map.of());

    verify(redis, never()).putHashAll(anyString(), any(), any());
  }
}

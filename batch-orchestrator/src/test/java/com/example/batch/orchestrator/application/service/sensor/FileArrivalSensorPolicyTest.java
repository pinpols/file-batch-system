package com.example.batch.orchestrator.application.service.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.mapper.SensorFileArrivalMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class FileArrivalSensorPolicyTest {

  private final SensorFileArrivalMapper mapper = Mockito.mock(SensorFileArrivalMapper.class);
  private final FileArrivalSensorPolicy policy = new FileArrivalSensorPolicy(mapper);

  @Test
  void probe_matched_returnsHit() {
    Map<String, Object> hit = new LinkedHashMap<>();
    hit.put("fileId", 42L);
    hit.put("fileName", "settle-001.csv");
    when(mapper.selectLatestArrival(eq("ta"), eq("settle-%"), any(), any())).thenReturn(hit);

    SensorProbeResult r = policy.probe(ctx(Map.of("pattern", "settle-*", "maxAgeSeconds", 3600)));

    assertThat(r.status()).isEqualTo(SensorProbeStatus.MATCHED);
    assertThat(r.output().get("fileId")).isEqualTo(42L);
  }

  @Test
  void probe_noHit_returnsNotYet() {
    when(mapper.selectLatestArrival(any(), any(), any(), any())).thenReturn(null);
    SensorProbeResult r = policy.probe(ctx(Map.of("pattern", "x-*", "maxAgeSeconds", 600)));
    assertThat(r.status()).isEqualTo(SensorProbeStatus.NOT_YET);
  }

  @Test
  void probe_missingPattern_returnsError() {
    SensorProbeResult r = policy.probe(ctx(Map.of("maxAgeSeconds", 600)));
    assertThat(r.status()).isEqualTo(SensorProbeStatus.ERROR);
    assertThat(r.errorKey()).isEqualTo("error.workflow.sensor_spec_invalid");
  }

  @Test
  void probe_mapperThrows_returnsError() {
    when(mapper.selectLatestArrival(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("db down"));
    SensorProbeResult r = policy.probe(ctx(Map.of("pattern", "*.csv", "maxAgeSeconds", 3600)));
    assertThat(r.status()).isEqualTo(SensorProbeStatus.ERROR);
    assertThat(r.errorKey()).isEqualTo("error.workflow.sensor_probe_failed");
  }

  @Test
  void probe_channelCodeSftp_filterSftpSourceType() {
    when(mapper.selectLatestArrival(any(), any(), any(), any())).thenReturn(null);
    policy.probe(
        ctx(
            Map.of(
                "channelCode", "sftp_bank_in",
                "pattern", "*.csv",
                "maxAgeSeconds", 3600)));
    ArgumentCaptor<String> srcCap = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<OffsetDateTime> sinceCap = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(mapper).selectLatestArrival(eq("ta"), eq("%.csv"), srcCap.capture(), sinceCap.capture());
    assertThat(srcCap.getValue()).isEqualTo("SFTP");
    assertThat(sinceCap.getValue()).isNotNull();
  }

  private static SensorContext ctx(Map<String, Object> spec) {
    return new SensorContext("ta", 100L, spec, Map.of(), Duration.ofMinutes(30));
  }
}

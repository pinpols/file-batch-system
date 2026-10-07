package io.github.pinpols.batch.orchestrator.application.service.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.infrastructure.sensor.FileArrivalSensorPolicy;
import io.github.pinpols.batch.orchestrator.mapper.SensorFileArrivalMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("文件到达传感策略: 到达命中, 记录缺失与查询异常口径")
class FileArrivalSensorPolicyTest {

  @Mock
  private SensorFileArrivalMapper mapper;

  private FileArrivalSensorPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new FileArrivalSensorPolicy(mapper);
  }

  @Test
  @DisplayName("查询到最新到达记录时判定为命中并带出文件标识")
  void probe_matched_returnsHit() {
    Map<String, Object> hit = new LinkedHashMap<>();
    hit.put("fileId", 42L);
    hit.put("fileName", "settle-001.csv");
    when(mapper.selectLatestArrival(eq("ta"), eq("settle-%"), any(), any())).thenReturn(hit);

    SensorProbeResult r = policy.probe(ctx(Map.of("pattern", "settle-*", "maxAgeSeconds", 3600)));

    assertThat(r.status()).isEqualTo(SensorProbeStatus.MATCHED);
    assertThat(r.output()).containsEntry("fileId", 42L);
  }

  @Test
  @DisplayName("没有到达记录时判定为未就绪")
  void probe_noHit_returnsNotYet() {
    when(mapper.selectLatestArrival(any(), any(), any(), any())).thenReturn(null);
    SensorProbeResult r = policy.probe(ctx(Map.of("pattern", "x-*", "maxAgeSeconds", 600)));
    assertThat(r.status()).isEqualTo(SensorProbeStatus.NOT_YET);
  }

  @Test
  @DisplayName("命名规则缺失时判定为配置错误并给出错误键")
  void probe_missingPattern_returnsError() {
    SensorProbeResult r = policy.probe(ctx(Map.of("maxAgeSeconds", 600)));
    assertThat(r.status()).isEqualTo(SensorProbeStatus.ERROR);
    assertThat(r.errorKey()).isEqualTo("error.workflow.sensor_spec_invalid");
  }

  @Test
  @DisplayName("查询过程抛异常时判定为探针失败并给出错误键")
  void probe_mapperThrows_returnsError() {
    when(mapper.selectLatestArrival(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("db down"));
    SensorProbeResult r = policy.probe(ctx(Map.of("pattern", "*.csv", "maxAgeSeconds", 3600)));
    assertThat(r.status()).isEqualTo(SensorProbeStatus.ERROR);
    assertThat(r.errorKey()).isEqualTo("error.workflow.sensor_probe_failed");
  }

  @Test
  @DisplayName("渠道来源为文件传输时按该来源类型过滤并带出时间下界")
  void probe_channelCodeSftp_filterSftpSourceType() {
    when(mapper.selectLatestArrival(any(), any(), any(), any())).thenReturn(null);
    policy.probe(ctx(Map.of(
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

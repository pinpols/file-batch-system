package io.github.pinpols.batch.orchestrator.application.service.sensor;

import io.github.pinpols.batch.orchestrator.domain.query.SensorFileArrivalLatestArrivalQuery;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.infrastructure.sensor.FileArrivalSensorPolicy;
import io.github.pinpols.batch.orchestrator.mapper.SensorFileArrivalMapper;
import java.time.Duration;
import java.time.LocalDate;
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
    when(mapper.selectLatestArrival(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.tenantId(), "ta") && java.util.Objects.equals(parameters.namePattern(), "settle-%"))))
        .thenReturn(hit);

    SensorProbeResult r = policy.probe(ctx(Map.of("pattern", "settle-*", "maxAgeSeconds", 3600)));

    assertThat(r.status()).isEqualTo(SensorProbeStatus.MATCHED);
    assertThat(r.output()).containsEntry("fileId", 42L);
  }

  @Test
  @DisplayName("没有到达记录时判定为未就绪")
  void probe_noHit_returnsNotYet() {
    when(mapper.selectLatestArrival(
            org.mockito.ArgumentMatchers.any(SensorFileArrivalLatestArrivalQuery.class)))
        .thenReturn(null);
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
    when(mapper.selectLatestArrival(
            org.mockito.ArgumentMatchers.any(SensorFileArrivalLatestArrivalQuery.class)))
        .thenThrow(new RuntimeException("db down"));
    SensorProbeResult r = policy.probe(ctx(Map.of("pattern", "*.csv", "maxAgeSeconds", 3600)));
    assertThat(r.status()).isEqualTo(SensorProbeStatus.ERROR);
    assertThat(r.errorKey()).isEqualTo("error.workflow.sensor_probe_failed");
  }

  @Test
  @DisplayName("渠道来源为文件传输时按该来源类型过滤并带出时间下界")
  void probe_channelCodeSftp_filterSftpSourceType() {
    when(mapper.selectLatestArrival(
            org.mockito.ArgumentMatchers.any(SensorFileArrivalLatestArrivalQuery.class)))
        .thenReturn(null);
    policy.probe(ctx(Map.of(
        "channelCode", "sftp_bank_in",
        "pattern", "*.csv",
        "maxAgeSeconds", 3600)));
    ArgumentCaptor<SensorFileArrivalLatestArrivalQuery> queryCaptor = ArgumentCaptor.forClass(SensorFileArrivalLatestArrivalQuery.class);
    verify(mapper)
        .selectLatestArrival(
            queryCaptor.capture());
    assertThat(queryCaptor.getValue().tenantId()).isEqualTo("ta");
    assertThat(queryCaptor.getValue().namePattern()).isEqualTo("%.csv");
    assertThat(queryCaptor.getValue().sourceType()).isEqualTo("SFTP");
    assertThat(queryCaptor.getValue().since()).isNotNull();
  }

  @Test
  @DisplayName("显式绑定 workflow 业务日并可要求到达组完整后才命中")
  void probe_completeGroup_usesWorkflowBizDate() {
    when(mapper.selectLatestArrival(org.mockito.ArgumentMatchers.any(SensorFileArrivalLatestArrivalQuery.class)))
        .thenReturn(Map.of("fileId", 91L, "bizDate", "2026-05-07"));

    SensorProbeResult result = policy.probe(new SensorContext(
        "ta",
        100L,
        Map.of(
            "pattern",
            "settle-*.csv",
            "maxAgeSeconds",
            3600,
            "fileGroupCode",
            "SETTLEMENT",
            "requireCompleteGroup",
            true,
            "bindWorkflowBizDate",
            true),
        Map.of("bizDate", "2026-05-07"),
        Duration.ofMinutes(30),
        902L));

    assertThat(result.status()).isEqualTo(SensorProbeStatus.MATCHED);
    verify(mapper)
        .selectLatestArrival(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.tenantId(), "ta") && java.util.Objects.equals(parameters.namePattern(), "settle-%.csv") && java.util.Objects.equals(parameters.bizDate(), LocalDate.parse("2026-05-07")) && java.util.Objects.equals(parameters.fileGroupCode(), "SETTLEMENT") && java.util.Objects.equals(parameters.workflowDefinitionId(), 902L) && java.util.Objects.equals(parameters.requireCompleteGroup(), true)));
  }

  @Test
  @DisplayName("旧配置不自动追加 workflow 业务日过滤")
  void probe_legacyConfig_keepsUnscopedBizDate() {
    when(mapper.selectLatestArrival(
            org.mockito.ArgumentMatchers.any(SensorFileArrivalLatestArrivalQuery.class)))
        .thenReturn(null);

    policy.probe(new SensorContext(
        "ta",
        100L,
        Map.of("pattern", "*.csv", "maxAgeSeconds", 600),
        Map.of("bizDate", "2026-05-07"),
        Duration.ofMinutes(30),
        null));

    verify(mapper)
        .selectLatestArrival(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.tenantId(), "ta") && java.util.Objects.equals(parameters.namePattern(), "%.csv") && parameters.bizDate() == null && parameters.fileGroupCode() == null && parameters.workflowDefinitionId() == null && java.util.Objects.equals(parameters.requireCompleteGroup(), false)));
  }

  @Test
  @DisplayName("要求完整到达组却未指定组编码时返回配置错误")
  void probe_completeGroupWithoutGroupCode_returnsError() {
    SensorProbeResult result = policy.probe(
        ctx(Map.of("pattern", "*.csv", "maxAgeSeconds", 600, "requireCompleteGroup", true)));

    assertThat(result.status()).isEqualTo(SensorProbeStatus.ERROR);
    assertThat(result.errorKey()).isEqualTo("error.workflow.sensor_spec_invalid");
  }

  @Test
  @DisplayName("完整到达组查询未绑定业务日时拒绝探测")
  void probe_completeGroupWithoutBizDate_returnsError() {
    SensorProbeResult result = policy.probe(new SensorContext(
        "ta",
        100L,
        Map.of(
            "pattern",
            "*.csv",
            "maxAgeSeconds",
            600,
            "fileGroupCode",
            "SETTLEMENT",
            "requireCompleteGroup",
            true),
        Map.of(),
        Duration.ofMinutes(30),
        null));

    assertThat(result.status()).isEqualTo(SensorProbeStatus.ERROR);
    assertThat(result.errorKey()).isEqualTo("error.workflow.sensor_spec_invalid");
    verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("完整文件组探测没有目标 Workflow 身份时失败关闭")
  void probe_completeGroupWithoutWorkflowId_returnsError() {
    SensorProbeResult result = policy.probe(new SensorContext(
        "ta",
        100L,
        Map.of(
            "pattern",
            "*.csv",
            "maxAgeSeconds",
            600,
            "fileGroupCode",
            "SETTLEMENT",
            "requireCompleteGroup",
            true,
            "bizDate",
            "2026-05-07"),
        Map.of(),
        Duration.ofMinutes(30),
        null));

    assertThat(result.status()).isEqualTo(SensorProbeStatus.ERROR);
    verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("workflow 业务日格式错误时拒绝探测")
  void probe_invalidWorkflowBizDate_returnsError() {
    SensorProbeResult result = policy.probe(new SensorContext(
        "ta",
        100L,
        Map.of("pattern", "*.csv", "maxAgeSeconds", 600, "bindWorkflowBizDate", true),
        Map.of("bizDate", "not-a-date"),
        Duration.ofMinutes(30),
        null));

    assertThat(result.status()).isEqualTo(SensorProbeStatus.ERROR);
    assertThat(result.errorKey()).isEqualTo("error.workflow.sensor_spec_invalid");
  }

  private static SensorContext ctx(Map<String, Object> spec) {
    return new SensorContext("ta", 100L, spec, Map.of(), Duration.ofMinutes(30), null);
  }
}

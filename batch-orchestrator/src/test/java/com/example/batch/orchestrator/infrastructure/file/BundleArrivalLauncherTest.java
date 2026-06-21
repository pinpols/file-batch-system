package com.example.batch.orchestrator.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.service.LaunchService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class BundleArrivalLauncherTest {

  private LaunchService launchService;
  private BundleArrivalLauncher launcher;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    launchService = mock(LaunchService.class);
    ObjectProvider<LaunchService> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(launchService);
    launcher = new BundleArrivalLauncher(provider);
  }

  private static Map<String, Object> file(long id, String metadataJson) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("tenant_id", "t1");
    m.put("biz_date", LocalDate.of(2026, 6, 21));
    m.put("metadata_json", metadataJson);
    return m;
  }

  @Test
  void launchesBundleWhenGroupCarriesBundleJobCode() {
    when(launchService.launch(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new LaunchResponse("INST-1", "trace-1"));
    List<Map<String, Object>> groupFiles =
        List.of(
            file(
                101,
                "{\"bundleJobCode\":\"BUNDLE_IMPORT_DAILY\",\"bundleTemplateCode\":\"TPL_ORDER\"}"),
            file(
                102,
                "{\"bundleJobCode\":\"BUNDLE_IMPORT_DAILY\",\"bundleTemplateCode\":\"TPL_CUST\"}"));

    launcher.launchIfBundle("t1", "bundle-daily", groupFiles);

    ArgumentCaptor<LaunchRequest> captor = ArgumentCaptor.forClass(LaunchRequest.class);
    verify(launchService).launch(captor.capture());
    LaunchRequest req = captor.getValue();
    Assertions.assertThat(req.tenantId()).isEqualTo("t1");
    Assertions.assertThat(req.jobCode()).isEqualTo("BUNDLE_IMPORT_DAILY");
    Assertions.assertThat(req.bizDate()).isEqualTo(LocalDate.of(2026, 6, 21));
    Assertions.assertThat(req.triggerType()).isEqualTo(TriggerType.EVENT);
    // 确定性幂等 requestId(同组同 bizDate → 同 id)
    Assertions.assertThat(req.requestId()).isEqualTo("bundle-arrival-t1-bundle-daily-2026-06-21");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> bundleFiles =
        (List<Map<String, Object>>) req.params().get("bundleFiles");
    Assertions.assertThat(bundleFiles).hasSize(2);
    Assertions.assertThat(bundleFiles.get(0)).containsEntry("sourceFileId", 101L);
    Assertions.assertThat(bundleFiles.get(0)).containsEntry("templateCode", "TPL_ORDER");
    Assertions.assertThat(bundleFiles.get(1)).containsEntry("sourceFileId", 102L);
    Assertions.assertThat(bundleFiles.get(1)).containsEntry("templateCode", "TPL_CUST");
  }

  @Test
  void launchesDispatchBundleWithTargetRefChannelBinding() {
    // ADR-046 Phase3:分发束——文件到达带 bundleTargetRef(下游渠道),emit {sourceFileId, targetRef}
    when(launchService.launch(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new LaunchResponse("INST-2", "trace-2"));
    List<Map<String, Object>> groupFiles =
        List.of(
            file(
                201, "{\"bundleJobCode\":\"BUNDLE_DISPATCH_EOD\",\"bundleTargetRef\":\"CH_SFTP\"}"),
            file(
                202, "{\"bundleJobCode\":\"BUNDLE_DISPATCH_EOD\",\"bundleTargetRef\":\"CH_OSS\"}"));

    launcher.launchIfBundle("t1", "dispatch-eod", groupFiles);

    ArgumentCaptor<LaunchRequest> captor = ArgumentCaptor.forClass(LaunchRequest.class);
    verify(launchService).launch(captor.capture());
    LaunchRequest req = captor.getValue();
    Assertions.assertThat(req.jobCode()).isEqualTo("BUNDLE_DISPATCH_EOD");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> bundleFiles =
        (List<Map<String, Object>>) req.params().get("bundleFiles");
    Assertions.assertThat(bundleFiles).hasSize(2);
    Assertions.assertThat(bundleFiles.get(0))
        .containsEntry("sourceFileId", 201L)
        .containsEntry("targetRef", "CH_SFTP")
        .doesNotContainKey("templateCode");
    Assertions.assertThat(bundleFiles.get(1))
        .containsEntry("sourceFileId", 202L)
        .containsEntry("targetRef", "CH_OSS");
  }

  @Test
  void launchesExportBundleFromManifestTemplateList() {
    // ADR-046 Phase3:导出束 manifest-only——一条 trigger 记录的 bundleExportTemplates 列表展成 N 项
    // {templateCode}(无 sourceFileId)。
    when(launchService.launch(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new LaunchResponse("INST-3", "trace-3"));
    List<Map<String, Object>> groupFiles =
        List.of(
            file(
                301,
                "{\"bundleJobCode\":\"BUNDLE_EXPORT_EOD\","
                    + "\"bundleExportTemplates\":[\"EXP_RISK\",\"EXP_TRADE\"]}"));

    launcher.launchIfBundle("t1", "export-eod", groupFiles);

    ArgumentCaptor<LaunchRequest> captor = ArgumentCaptor.forClass(LaunchRequest.class);
    verify(launchService).launch(captor.capture());
    LaunchRequest req = captor.getValue();
    Assertions.assertThat(req.jobCode()).isEqualTo("BUNDLE_EXPORT_EOD");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> bundleFiles =
        (List<Map<String, Object>>) req.params().get("bundleFiles");
    Assertions.assertThat(bundleFiles).hasSize(2);
    Assertions.assertThat(bundleFiles.get(0))
        .containsEntry("templateCode", "EXP_RISK")
        .doesNotContainKey("sourceFileId");
    Assertions.assertThat(bundleFiles.get(1)).containsEntry("templateCode", "EXP_TRADE");
  }

  @Test
  void skipsNonBundleGroup() {
    // 普通到达组:metadata 无 bundleJobCode → 不发 launch
    List<Map<String, Object>> groupFiles =
        List.of(file(1, "{\"scanner\":\"objectStore-import\",\"fileGroupCode\":\"plain\"}"));

    launcher.launchIfBundle("t1", "plain", groupFiles);

    verify(launchService, never()).launch(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void skipsWhenBundleJobCodePresentButNoUsableFileBinding() {
    // 有 bundleJobCode 但所有文件都缺 templateCode → 不发(不展空束)
    List<Map<String, Object>> groupFiles =
        List.of(file(1, "{\"bundleJobCode\":\"BUNDLE_IMPORT_DAILY\"}"));

    launcher.launchIfBundle("t1", "g", groupFiles);

    verify(launchService, never()).launch(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void isolatesLaunchExceptionWithoutPropagating() {
    when(launchService.launch(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new RuntimeException("launch boom"));
    List<Map<String, Object>> groupFiles =
        List.of(
            file(
                101,
                "{\"bundleJobCode\":\"BUNDLE_IMPORT_DAILY\",\"bundleTemplateCode\":\"TPL_ORDER\"}"));

    // 异常隔离:不向上抛,governance sweep 继续
    assertThatCode(() -> launcher.launchIfBundle("t1", "g", groupFiles)).doesNotThrowAnyException();
  }

  @Test
  void skipsEmptyGroup() {
    launcher.launchIfBundle("t1", "g", List.of());
    verify(launchService, never()).launch(org.mockito.ArgumentMatchers.any());
  }
}

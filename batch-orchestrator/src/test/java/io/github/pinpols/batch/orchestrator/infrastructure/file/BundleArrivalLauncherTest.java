package io.github.pinpols.batch.orchestrator.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import java.time.LocalDate;
import java.time.Month;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("束文件到达启动器,校验到达组内束作业识别,文件绑定解析与失败快速返回")
class BundleArrivalLauncherTest {

  private LaunchService launchService;
  private TriggerRequestMapper triggerRequestMapper;
  private BundleArrivalLauncher launcher;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    launchService = mock(LaunchService.class);
    triggerRequestMapper = mock(TriggerRequestMapper.class);
    ObjectProvider<LaunchService> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(launchService);
    launcher = new BundleArrivalLauncher(provider, triggerRequestMapper);
  }

  private static Map<String, Object> file(long id, Object metadataJson) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("tenant_id", "t1");
    m.put("biz_date", LocalDate.of(2026, Month.JUNE, 21));
    m.put("metadata_json", metadataJson);
    return m;
  }

  /** 复刻 JDBC 对 PG jsonb 列的映射结果:org.postgresql.util.PGobject(不是 String)。 */
  private static Object pgJsonb(String json) throws Exception {
    PGobject pg = new PGobject();
    pg.setType("jsonb");
    pg.setValue(json);
    return pg;
  }

  @Test
  @DisplayName("元数据由数据库驱动以对象形式返回时仍识别为束作业并启动,启动请求携带作业编码与业务日期")
  void shouldLaunchBundle_whenMetadataJsonArrivesAsDriverObject() throws Exception {
    // 回归:selectArrivalGroupFiles 的 metadata_json 是 jsonb → 驱动给 PGobject。
    // 若只认 String,这里会静默 NOT_BUNDLE,到达组永远不 launch(线上真实缺陷,见 stage 26)。
    when(launchService.launch(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new LaunchResponse("INST-1", "trace-1"));
    List<Map<String, Object>> groupFiles = List.of(
        file(
            101,
            pgJsonb(
                "{\"bundleJobCode\":\"BUNDLE_IMPORT_DAILY\",\"bundleTemplateCode\":\"TPL_ORDER\"}")),
        file(
            102,
            pgJsonb(
                "{\"bundleJobCode\":\"BUNDLE_IMPORT_DAILY\",\"bundleTemplateCode\":\"TPL_CUST\"}")));

    launcher.launchIfBundle("t1", "bundle-daily", groupFiles);

    ArgumentCaptor<LaunchRequest> captor = ArgumentCaptor.forClass(LaunchRequest.class);
    verify(launchService).launch(captor.capture());
    Assertions.assertThat(captor.getValue().jobCode()).isEqualTo("BUNDLE_IMPORT_DAILY");
    Assertions.assertThat(captor.getValue().bizDate())
        .isEqualTo(LocalDate.of(2026, Month.JUNE, 21));
  }

  @Test
  @DisplayName("到达组携带束作业编码时先登记触发请求再启动,请求号按组与业务日期确定,载荷含每个源文件的模板绑定")
  void shouldLaunchBundle_whenGroupCarriesBundleJobCode() {
    when(launchService.launch(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new LaunchResponse("INST-1", "trace-1"));
    List<Map<String, Object>> groupFiles = List.of(
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
    Assertions.assertThat(req.bizDate()).isEqualTo(LocalDate.of(2026, Month.JUNE, 21));
    Assertions.assertThat(req.triggerType()).isEqualTo(TriggerType.EVENT);
    // 确定性幂等 requestId(同组同 bizDate → 同 id)
    Assertions.assertThat(req.requestId()).isEqualTo("bundle-arrival-t1-bundle-daily-2026-06-21");
    // 关键顺序:DefaultLaunchService 按 requestId 查 trigger_request,查不到直接抛
    // error.trigger.request_not_found → 必须先落 ACCEPTED 行再 launch(与其它内部 launcher 一致)。
    ArgumentCaptor<TriggerRequestEntity> entityCaptor =
        ArgumentCaptor.forClass(TriggerRequestEntity.class);
    InOrder order = inOrder(triggerRequestMapper, launchService);
    order.verify(triggerRequestMapper).insertIfAbsent(entityCaptor.capture());
    order.verify(launchService).launch(org.mockito.ArgumentMatchers.any());
    TriggerRequestEntity entity = entityCaptor.getValue();
    Assertions.assertThat(entity.getRequestId()).isEqualTo(req.requestId());
    Assertions.assertThat(entity.getJobCode()).isEqualTo("BUNDLE_IMPORT_DAILY");
    Assertions.assertThat(entity.getTriggerType()).isEqualTo(TriggerType.EVENT.code());
    Assertions.assertThat(entity.getRequestStatus())
        .isEqualTo(TriggerRequestStatus.ACCEPTED.code());
    Assertions.assertThat(entity.getBizDate()).isEqualTo(LocalDate.of(2026, Month.JUNE, 21));
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
  @DisplayName("分发束文件携带下游渠道引用时按渠道生成文件清单,条目不含模板绑定")
  void shouldLaunchDispatchBundle_whenFilesCarryTargetRef() {
    // ADR-046 Phase3:分发束——文件到达带 bundleTargetRef(下游渠道),emit {sourceFileId, targetRef}
    when(launchService.launch(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new LaunchResponse("INST-2", "trace-2"));
    List<Map<String, Object>> groupFiles = List.of(
        file(201, "{\"bundleJobCode\":\"BUNDLE_DISPATCH_EOD\",\"bundleTargetRef\":\"CH_SFTP\"}"),
        file(202, "{\"bundleJobCode\":\"BUNDLE_DISPATCH_EOD\",\"bundleTargetRef\":\"CH_OSS\"}"));

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
  @DisplayName("导出束清单声明模板列表时逐项展开为模板绑定条目,条目不含源文件标识")
  void shouldLaunchExportBundle_whenManifestListsTemplates() {
    // ADR-046 Phase3:导出束 manifest-only——一条 trigger 记录的 bundleExportTemplates 列表展成 N 项
    // {templateCode}(无 sourceFileId)。
    when(launchService.launch(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new LaunchResponse("INST-3", "trace-3"));
    List<Map<String, Object>> groupFiles = List.of(file(
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
  @DisplayName("到达组元数据不含束作业编码时判定为非束组并不发起启动调用")
  void shouldSkipLaunch_whenGroupHasNoBundleJobCode() {
    // 普通到达组:metadata 无 bundleJobCode → 不发 launch
    List<Map<String, Object>> groupFiles =
        List.of(file(1, "{\"scanner\":\"objectStore-import\",\"fileGroupCode\":\"plain\"}"));

    Assertions.assertThat(launcher.launchIfBundle("t1", "plain", groupFiles))
        .isEqualTo(BundleArrivalLauncher.LaunchOutcome.NOT_BUNDLE);

    verify(launchService, never()).launch(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("存在束作业编码但所有文件都缺少可用绑定时快速失败且不发起启动调用,使到达组保持可重试")
  void shouldFailFast_whenBundleJobCodeHasNoUsableBinding() {
    // 有 bundleJobCode 但所有文件都缺 binding → fail-fast,由到达组调度保持 retryable
    List<Map<String, Object>> groupFiles =
        List.of(file(1, "{\"bundleJobCode\":\"BUNDLE_IMPORT_DAILY\"}"));

    assertThatThrownBy(() -> launcher.launchIfBundle("t1", "g", groupFiles))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("without usable binding");

    verify(launchService, never()).launch(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("启动过程抛出运行时异常时原样向上传递,使到达组调度可重试")
  void shouldPropagateException_whenLaunchFails() {
    when(launchService.launch(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new RuntimeException("launch boom"));
    List<Map<String, Object>> groupFiles = List.of(file(
        101, "{\"bundleJobCode\":\"BUNDLE_IMPORT_DAILY\",\"bundleTemplateCode\":\"TPL_ORDER\"}"));

    assertThatThrownBy(() -> launcher.launchIfBundle("t1", "g", groupFiles))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("launch boom");
  }

  @Test
  @DisplayName("同一到达组混用不同束作业编码时拒绝启动,且不发起任何启动调用")
  void shouldRejectLaunch_whenGroupMixesBundleJobCodes() {
    List<Map<String, Object>> groupFiles = List.of(
        file(101, "{\"bundleJobCode\":\"BUNDLE_IMPORT_A\",\"bundleTemplateCode\":\"TPL_A\"}"),
        file(102, "{\"bundleJobCode\":\"BUNDLE_IMPORT_B\",\"bundleTemplateCode\":\"TPL_B\"}"));

    assertThatThrownBy(() -> launcher.launchIfBundle("t1", "g", groupFiles))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mixes bundleJobCode");
    verify(launchService, never()).launch(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("已判定为束组的到达组中出现缺少束作业编码的绑定时拒绝启动,且不发起任何启动调用")
  void shouldRejectLaunch_whenBindingMissesBundleJobCode() {
    List<Map<String, Object>> groupFiles = List.of(
        file(101, "{\"bundleJobCode\":\"BUNDLE_IMPORT_DAILY\",\"bundleTemplateCode\":\"TPL_A\"}"),
        file(102, "{\"bundleTemplateCode\":\"TPL_B\"}"));

    assertThatThrownBy(() -> launcher.launchIfBundle("t1", "g", groupFiles))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("binding without bundleJobCode");
    verify(launchService, never()).launch(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("到达组文件列表为空时不发起任何启动调用")
  void shouldSkipLaunch_whenGroupIsEmpty() {
    launcher.launchIfBundle("t1", "g", List.of());
    verify(launchService, never()).launch(org.mockito.ArgumentMatchers.any());
  }
}

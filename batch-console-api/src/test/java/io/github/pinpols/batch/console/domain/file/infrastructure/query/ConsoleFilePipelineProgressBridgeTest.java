package io.github.pinpols.batch.console.domain.file.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFilePipelineProgressResponse;
import io.github.pinpols.batch.console.domain.file.mapper.FilePipelineStepRunMapper;
import io.github.pinpols.batch.console.domain.file.support.ConsoleFileQueryMappers;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineExecutionView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineInfoView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineProgressStepView;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.shared.view.ConsolePipelineProgressItemResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证流水线进度查询对实时行数的补充及流水线级文件名的返回。
 *
 * <p>运行中步骤的持久处理行数为空时，服务应查询编排器缓存并补充实时值；持久值已存在时不应调用下游服务。文件名从
 * {@code selectFileInfoByPipelineInstance} 查询，并放在响应顶层。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文件流水线进度桥接: 实时行数补齐与终态隔离")
class ConsoleFilePipelineProgressBridgeTest {

  private static final long PIPELINE_ID = 77L;
  private static final String TENANT = "t-obs";

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @Mock
  private FilePipelineStepRunMapper stepRunMapper;

  @Mock
  private LocalizedErrorRenderer localizedErrorRenderer;

  @Mock
  private ConsoleOrchestratorPort orchestratorProxy;

  private ConsoleFileQueryService service;

  @BeforeEach
  void setUp() {
    ConsoleFileQueryMappers mappers =
        new ConsoleFileQueryMappers(null, null, null, null, stepRunMapper, null, null, null);
    service = new ConsoleFileQueryService(
        tenantGuard,
        mappers,
        new BatchSecurityProperties(),
        localizedErrorRenderer,
        orchestratorProxy,
        mock(BatchDateTimeSupport.class));
  }

  private void stubTenant() {
    when(stepRunMapper.selectTenantIdByPipelineInstanceId(PIPELINE_ID)).thenReturn(TENANT);
  }

  @Test
  @DisplayName("跨服务读取遇到不同阶段尝试时不把新计数写入旧步骤快照")
  void shouldWithholdLiveCountsWhenAttemptIdentityMissingOrDifferent() {
    stubTenant();
    when(stepRunMapper.selectProgressByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(step("RUNNING", null)));
    when(orchestratorProxy.pipelineProgressByInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(
            new ConsolePipelineProgressItemResponse("LOAD", 999L, 1000L, null, List.of(2L))));
    stubFileInfo(5L, "retry.csv");
    var response = service.pipelineProgress(PIPELINE_ID);
    assertThat(response.steps().getFirst().rowsProcessed()).isNull();
    assertThat(response.steps().getFirst().totalRowsHint()).isNull();
  }

  private FilePipelineProgressStepView step(String status, Long rowsProcessed) {
    return step("LOAD", status, rowsProcessed);
  }

  private FilePipelineProgressStepView step(String stageCode, String status, Long rowsProcessed) {
    return new FilePipelineProgressStepView(
        1L, PIPELINE_ID, stageCode, stageCode, status, rowsProcessed, null, null, 1, null);
  }

  private void stubFileInfo(Long fileId, String fileName) {
    when(stepRunMapper.selectFileInfoByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(new FilePipelineInfoView(fileId, fileName));
  }

  @Test
  @DisplayName("运行中 step 持久行数为空时,按当前 worker 从 cache 桥接实时行数")
  void shouldBridgeLiveRowsForRunningStepWhenPersistedNull() {
    // 准备依赖
    stubTenant();
    when(stepRunMapper.selectProgressByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(step("RUNNING", null)));
    when(orchestratorProxy.pipelineProgressByInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(
            new ConsolePipelineProgressItemResponse("LOAD", 4200L, 5000L, null, List.of(1L))));
    stubFileInfo(555L, "customers.csv");

    // 执行查询
    ConsoleFilePipelineProgressResponse resp = service.pipelineProgress(PIPELINE_ID);

    // 校验结果
    assertThat(resp.fileId()).isEqualTo(555L);
    assertThat(resp.fileName()).isEqualTo("customers.csv");
    assertThat(resp.steps()).hasSize(1);
    assertThat(resp.steps().get(0).rowsProcessed()).isEqualTo(4200L);
    assertThat(resp.steps().get(0).totalRowsHint()).isEqualTo(5000L);
  }

  @Test
  @DisplayName("持久行数已有值时不触发桥接,cache 不被查询")
  void shouldNotBridgeWhenPersistedRowsPresent() {
    // 准备依赖
    stubTenant();
    when(stepRunMapper.selectProgressByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(step("RUNNING", 100L)));
    stubFileInfo(1L, "orders.csv");

    // 执行查询
    ConsoleFilePipelineProgressResponse resp = service.pipelineProgress(PIPELINE_ID);

    // 校验结果
    assertThat(resp.steps().get(0).rowsProcessed()).isEqualTo(100L);
    verify(orchestratorProxy, never()).pipelineProgressByInstance(anyString(), any());
  }

  @Test
  @DisplayName("orchestrator 无实时进度时,运行中 step 行数保持 null")
  void shouldKeepNullWhenNoLiveProgressExists() {
    // 准备依赖
    stubTenant();
    when(stepRunMapper.selectProgressByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(step("RUNNING", null)));
    when(orchestratorProxy.pipelineProgressByInstance(TENANT, PIPELINE_ID)).thenReturn(List.of());
    stubFileInfo(2L, "trades.csv");

    // 执行查询
    ConsoleFilePipelineProgressResponse resp = service.pipelineProgress(PIPELINE_ID);

    // 校验结果
    assertThat(resp.steps().get(0).rowsProcessed()).isNull();
    verify(orchestratorProxy).pipelineProgressByInstance(TENANT, PIPELINE_ID);
  }

  @Test
  @DisplayName("非运行中 step(持久空)不触发桥接")
  void shouldNotBridgeForNonRunningStep() {
    // 准备依赖
    stubTenant();
    when(stepRunMapper.selectProgressByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(step("SUCCESS", null)));
    stubFileInfo(3L, "done.csv");

    // 执行查询
    ConsoleFilePipelineProgressResponse resp = service.pipelineProgress(PIPELINE_ID);

    // 校验结果
    assertThat(resp.steps().get(0).rowsProcessed()).isNull();
    verify(orchestratorProxy, never()).pipelineProgressByInstance(anyString(), any());
  }

  @Test
  @DisplayName("同一 pipeline 有运行中 step 时,终态 step 不吸收尚未过期的实时快照")
  void shouldNotApplyLiveSnapshotToTerminalStep() {
    stubTenant();
    when(stepRunMapper.selectProgressByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(step("LOAD", "RUNNING", null), step("VALIDATE", "SUCCESS", null)));
    when(orchestratorProxy.pipelineProgressByInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(
            new ConsolePipelineProgressItemResponse("LOAD", 42L, 100L, null, List.of(1L)),
            new ConsolePipelineProgressItemResponse("VALIDATE", 90L, 90L, null, List.of(1L))));
    stubFileInfo(4L, "mixed.csv");

    ConsoleFilePipelineProgressResponse response = service.pipelineProgress(PIPELINE_ID);

    assertThat(response.steps().get(0).rowsProcessed()).isEqualTo(42L);
    assertThat(response.steps().get(0).totalRowsHint()).isEqualTo(100L);
    assertThat(response.steps().get(1).rowsProcessed()).isNull();
    assertThat(response.steps().get(1).totalRowsHint()).isNull();
  }

  @Test
  @DisplayName("未知 pipelineInstanceId(无租户)返回空 steps 且 fileId/fileName 为 null")
  void shouldReturnEmptyWhenTenantUnresolved() {
    // 准备依赖
    when(stepRunMapper.selectTenantIdByPipelineInstanceId(999L)).thenReturn(null);

    // 执行查询
    ConsoleFilePipelineProgressResponse resp = service.pipelineProgress(999L);

    // 校验结果
    assertThat(resp.steps()).isEmpty();
    assertThat(resp.fileId()).isNull();
    assertThat(resp.fileName()).isNull();
  }

  @Test
  @DisplayName("为已授权后台读取构建带版本的最新快照")
  void shouldBuildVersionedLatestSnapshotForAuthorizedBackground() {
    var execution = new FilePipelineExecutionView();
    execution.setPipelineInstanceId(PIPELINE_ID);
    execution.setRunStatus("RUNNING");
    execution.setCurrentStage("LOAD");
    execution.setSystemPipelineCode("IMPORT");
    execution.setSystemPipelineVersion(1);
    when(stepRunMapper.selectExecution(TENANT, PIPELINE_ID)).thenReturn(execution);
    when(stepRunMapper.selectLatestProgressByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(step("RUNNING", null)));
    when(orchestratorProxy.pipelineProgressByInstance(TENANT, PIPELINE_ID))
        .thenReturn(
            List.of(new ConsolePipelineProgressItemResponse("LOAD", 88L, null, null, List.of(1L))));
    var result = service.pipelineSnapshotAuthorized(TENANT, PIPELINE_ID);
    assertThat(result.stageGraphAvailable()).isTrue();
    assertThat(result.stageCodes())
        .containsExactly("RECEIVE", "PREPROCESS", "PARSE", "VALIDATE", "LOAD", "FEEDBACK");
    assertThat(result.steps().getFirst().rowsProcessed()).isEqualTo(88L);
    assertThat(result.steps().getFirst().stepStatus()).isEqualTo("RUNNING");
    verify(tenantGuard, never()).assertTenantAllowed(anyString());
  }

  @Test
  @DisplayName("运行版本未知时不推测阶段")
  void shouldNotInventStagesForUnknownRuntimeVersion() {
    var execution = new FilePipelineExecutionView();
    execution.setPipelineInstanceId(PIPELINE_ID);
    execution.setRunStatus("SUCCESS");
    execution.setSystemPipelineCode("IMPORT");
    execution.setSystemPipelineVersion(99);
    when(stepRunMapper.selectExecution(TENANT, PIPELINE_ID)).thenReturn(execution);
    when(stepRunMapper.selectLatestProgressByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(step("SUCCESS", 15L)));
    var result = service.pipelineSnapshotAuthorized(TENANT, PIPELINE_ID);
    assertThat(result.stageGraphAvailable()).isFalse();
    assertThat(result.stageCodes()).isEmpty();
    assertThat(result.steps()).hasSize(1);
    verify(orchestratorProxy, never()).pipelineProgressByInstance(anyString(), any());
  }

  @Test
  @DisplayName("Pipeline 终止后不再合并实时进度")
  void shouldNotBridgeAfterPipelineTerminates() {
    var execution = new FilePipelineExecutionView();
    execution.setPipelineInstanceId(PIPELINE_ID);
    execution.setRunStatus("TERMINATED");
    execution.setSystemPipelineCode("IMPORT");
    execution.setSystemPipelineVersion(1);
    when(stepRunMapper.selectExecution(TENANT, PIPELINE_ID)).thenReturn(execution);
    when(stepRunMapper.selectLatestProgressByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(step("RUNNING", null)));
    var result = service.pipelineSnapshotAuthorized(TENANT, PIPELINE_ID);
    assertThat(result.steps().getFirst().rowsProcessed()).isNull();
    verify(orchestratorProxy, never()).pipelineProgressByInstance(anyString(), any());
  }
}

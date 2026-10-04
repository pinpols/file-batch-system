package io.github.pinpols.batch.console.domain.file.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFilePipelineProgressResponse;
import io.github.pinpols.batch.console.domain.file.mapper.FilePipelineStepRunMapper;
import io.github.pinpols.batch.console.domain.file.support.ConsoleFileQueryMappers;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.shared.view.ConsolePipelineProgressItemResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 缺口1/2 单测:pipelineProgress 服务端桥接实时行数 + pipeline 级文件名透出。
 *
 * <p>桥接逻辑(缺口1)mock orchestratorProxy 验证——运行中 step 的持久 rows_processed 为 null 时才解析 worker 查 cache
 * 补上;持久有值则完全不触发下游调用。文件名(缺口2)从 selectFileInfoByPipelineInstance 取,放响应顶层。
 */
@ExtendWith(MockitoExtension.class)
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
        orchestratorProxy);
  }

  private void stubTenant() {
    when(stepRunMapper.selectTenantIdByPipelineInstanceId(PIPELINE_ID)).thenReturn(TENANT);
  }

  private Map<String, Object> step(String status, Long rowsProcessed) {
    return step("LOAD", status, rowsProcessed);
  }

  private Map<String, Object> step(String stageCode, String status, Long rowsProcessed) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("step_id", 1L);
    row.put("pipeline_instance_id", PIPELINE_ID);
    row.put("step_code", stageCode);
    row.put("stage_code", stageCode);
    row.put("step_status", status);
    row.put("rows_processed", rowsProcessed);
    row.put("total_rows_hint", null);
    row.put("last_heartbeat_at", null);
    return row;
  }

  private void stubFileInfo(Long fileId, String fileName) {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("file_id", fileId);
    info.put("file_name", fileName);
    when(stepRunMapper.selectFileInfoByPipelineInstance(TENANT, PIPELINE_ID)).thenReturn(info);
  }

  @Test
  @DisplayName("运行中 step 持久行数为空时,按当前 worker 从 cache 桥接实时行数")
  void shouldBridgeLiveRowsForRunningStepWhenPersistedNull() {
    // arrange
    stubTenant();
    when(stepRunMapper.selectProgressByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(step("RUNNING", null)));
    when(orchestratorProxy.pipelineProgressByInstance(TENANT, PIPELINE_ID))
        .thenReturn(
            List.of(new ConsolePipelineProgressItemResponse(null, 4200L, 5000L, null, "LOAD")));
    stubFileInfo(555L, "customers.csv");

    // act
    ConsoleFilePipelineProgressResponse resp = service.pipelineProgress(PIPELINE_ID);

    // assert
    assertThat(resp.fileId()).isEqualTo(555L);
    assertThat(resp.fileName()).isEqualTo("customers.csv");
    assertThat(resp.steps()).hasSize(1);
    assertThat(resp.steps().get(0).rowsProcessed()).isEqualTo(4200L);
    assertThat(resp.steps().get(0).totalRowsHint()).isEqualTo(5000L);
  }

  @Test
  @DisplayName("持久行数已有值时不触发桥接,cache 不被查询")
  void shouldNotBridgeWhenPersistedRowsPresent() {
    // arrange
    stubTenant();
    when(stepRunMapper.selectProgressByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(step("RUNNING", 100L)));
    stubFileInfo(1L, "orders.csv");

    // act
    ConsoleFilePipelineProgressResponse resp = service.pipelineProgress(PIPELINE_ID);

    // assert
    assertThat(resp.steps().get(0).rowsProcessed()).isEqualTo(100L);
    verify(orchestratorProxy, never()).pipelineProgressByInstance(anyString(), any());
  }

  @Test
  @DisplayName("orchestrator 无实时进度时,运行中 step 行数保持 null")
  void shouldKeepNullWhenNoLiveProgressExists() {
    // arrange
    stubTenant();
    when(stepRunMapper.selectProgressByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(step("RUNNING", null)));
    when(orchestratorProxy.pipelineProgressByInstance(TENANT, PIPELINE_ID)).thenReturn(List.of());
    stubFileInfo(2L, "trades.csv");

    // act
    ConsoleFilePipelineProgressResponse resp = service.pipelineProgress(PIPELINE_ID);

    // assert
    assertThat(resp.steps().get(0).rowsProcessed()).isNull();
    verify(orchestratorProxy).pipelineProgressByInstance(TENANT, PIPELINE_ID);
  }

  @Test
  @DisplayName("非运行中 step(持久空)不触发桥接")
  void shouldNotBridgeForNonRunningStep() {
    // arrange
    stubTenant();
    when(stepRunMapper.selectProgressByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(step("SUCCESS", null)));
    stubFileInfo(3L, "done.csv");

    // act
    ConsoleFilePipelineProgressResponse resp = service.pipelineProgress(PIPELINE_ID);

    // assert
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
            new ConsolePipelineProgressItemResponse(null, 42L, 100L, null, "LOAD"),
            new ConsolePipelineProgressItemResponse(null, 90L, 90L, null, "VALIDATE")));
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
    // arrange
    when(stepRunMapper.selectTenantIdByPipelineInstanceId(999L)).thenReturn(null);

    // act
    ConsoleFilePipelineProgressResponse resp = service.pipelineProgress(999L);

    // assert
    assertThat(resp.steps()).isEmpty();
    assertThat(resp.fileId()).isNull();
    assertThat(resp.fileName()).isNull();
  }
}

package io.github.pinpols.batch.console.domain.file.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.console.domain.file.mapper.FilePipelineStepRunMapper;
import io.github.pinpols.batch.console.domain.file.support.ConsoleFileQueryMappers;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFilePipelineProgressResponse;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
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

  @Mock private ConsoleTenantGuard tenantGuard;
  @Mock private FilePipelineStepRunMapper stepRunMapper;
  @Mock private LocalizedErrorRenderer localizedErrorRenderer;
  @Mock private ConsoleOrchestratorProxyService orchestratorProxy;

  private ConsoleFileQueryService service;

  @BeforeEach
  void setUp() {
    ConsoleFileQueryMappers mappers =
        new ConsoleFileQueryMappers(null, null, null, null, stepRunMapper, null, null, null);
    service =
        new ConsoleFileQueryService(
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
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("step_id", 1L);
    row.put("pipeline_instance_id", PIPELINE_ID);
    row.put("step_code", "LOAD");
    row.put("stage_code", "LOAD");
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
    when(stepRunMapper.selectRunningWorkerCode(TENANT, PIPELINE_ID)).thenReturn("worker-9");
    Map<String, Object> cacheRow = new LinkedHashMap<>();
    cacheRow.put("workerCode", "worker-9");
    cacheRow.put("rowsProcessed", 4200L);
    when(orchestratorProxy.pipelineProgress(TENANT, List.of("worker-9")))
        .thenReturn(List.of(cacheRow));
    stubFileInfo(555L, "customers.csv");

    // act
    ConsoleFilePipelineProgressResponse resp = service.pipelineProgress(PIPELINE_ID);

    // assert
    assertThat(resp.fileId()).isEqualTo(555L);
    assertThat(resp.fileName()).isEqualTo("customers.csv");
    assertThat(resp.steps()).hasSize(1);
    assertThat(resp.steps().get(0).rowsProcessed()).isEqualTo(4200L);
    assertThat(resp.steps().get(0).totalRowsHint()).isNull();
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
    verify(stepRunMapper, never()).selectRunningWorkerCode(anyString(), anyLong());
    verify(orchestratorProxy, never()).pipelineProgress(anyString(), any());
  }

  @Test
  @DisplayName("解析不到运行中 worker 时,运行中 step 行数保持 null,不调用 cache")
  void shouldKeepNullWhenNoRunningWorkerResolved() {
    // arrange
    stubTenant();
    when(stepRunMapper.selectProgressByPipelineInstance(TENANT, PIPELINE_ID))
        .thenReturn(List.of(step("RUNNING", null)));
    when(stepRunMapper.selectRunningWorkerCode(TENANT, PIPELINE_ID)).thenReturn(null);
    stubFileInfo(2L, "trades.csv");

    // act
    ConsoleFilePipelineProgressResponse resp = service.pipelineProgress(PIPELINE_ID);

    // assert
    assertThat(resp.steps().get(0).rowsProcessed()).isNull();
    verify(orchestratorProxy, never()).pipelineProgress(anyString(), any());
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
    verify(stepRunMapper, never()).selectRunningWorkerCode(anyString(), anyLong());
    verify(orchestratorProxy, never()).pipelineProgress(anyString(), any());
  }

  @Test
  @DisplayName("未知 pipelineInstanceId(无租户)返回空 steps 且 fileId/fileName 为 null")
  void shouldReturnEmptyWhenTenantUnresolved() {
    // arrange
    when(stepRunMapper.selectTenantIdByPipelineInstanceId(eq(999L))).thenReturn(null);

    // act
    ConsoleFilePipelineProgressResponse resp = service.pipelineProgress(999L);

    // assert
    assertThat(resp.steps()).isEmpty();
    assertThat(resp.fileId()).isNull();
    assertThat(resp.fileName()).isNull();
  }
}

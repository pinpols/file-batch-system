package io.github.pinpols.batch.console.domain.workflow.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.workflow.application.ConsoleWorkflowDefinitionApplicationService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService;
import io.github.pinpols.batch.console.domain.workflow.web.response.WorkflowDefinitionDetailResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.WorkflowDefinitionVersionSummaryResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 单测 GET /api/console/workflow-definitions/{id}/versions(+ /{version})——Polish 版本下拉端点。
 *
 * <p>覆盖 4 case:成功列出当前版本、降级仍返 1 条(无历史表)、跨租户 404、单版本 detail 成功 + 不存在版本 404。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConsoleWorkflowVersions Controller")
class ConsoleWorkflowVersionsControllerTest {

  @Mock private ConsoleWorkflowDefinitionApplicationService service;
  @Mock private WorkflowDesignLockService lockService;
  @Mock private ConsoleRequestMetadataResolver requestMetadataResolver;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleWorkflowDefinitionController(service, responseFactory, lockService))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  @DisplayName("成功列出:已有 workflow → 返回当前版本一条记录")
  void shouldListCurrentVersion_whenWorkflowExists() throws Exception {
    // 准备
    Instant updatedAt = Instant.parse("2026-06-04T03:00:00Z");
    WorkflowDefinitionVersionSummaryResponse summary =
        new WorkflowDefinitionVersionSummaryResponse(3, null, updatedAt, null, Boolean.TRUE);
    when(service.listVersions(eq(42L), eq("ta"))).thenReturn(List.of(summary));

    // 执行并断言
    mockMvc
        .perform(get("/api/console/workflow-definitions/42/versions").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].version").value(3))
        .andExpect(jsonPath("$.data[0].current").value(true))
        .andExpect(jsonPath("$.data[0].savedBy").doesNotExist())
        .andExpect(jsonPath("$.data[0].summary").doesNotExist());
  }

  @Test
  @DisplayName("降级仍返当前一条:无历史表 follow-up 文档化,list 仍正常返回 1 条")
  void shouldReturnSingleEntryFallback_whenHistoryTableAbsent() throws Exception {
    // 准备:模拟仅当前版本 = 1 的新 workflow(刚 create 未编辑)
    WorkflowDefinitionVersionSummaryResponse summary =
        new WorkflowDefinitionVersionSummaryResponse(
            1, null, Instant.parse("2026-06-04T01:00:00Z"), null, Boolean.TRUE);
    when(service.listVersions(eq(7L), eq("ta"))).thenReturn(List.of(summary));

    // 执行并断言
    mockMvc
        .perform(get("/api/console/workflow-definitions/7/versions").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].version").value(1))
        .andExpect(jsonPath("$.data[0].current").value(true));
  }

  @Test
  @DisplayName("跨租户:租户不匹配 → service 抛 NOT_FOUND → 404")
  void shouldReturnNotFound_whenTenantMismatch() throws Exception {
    when(service.listVersions(eq(42L), eq("other_tenant")))
        .thenThrow(BizException.of(ResultCode.NOT_FOUND, "error.common.not_found", "wf:42"));

    mockMvc
        .perform(
            get("/api/console/workflow-definitions/42/versions").param("tenantId", "other_tenant"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  @DisplayName("单版本 detail:存在版本 → 200,不存在版本 → 404 workflow_version.not_found")
  void shouldReturnDetail_whenVersionMatches_andNotFound_whenVersionStale() throws Exception {
    // 准备:current = 3,GET v3 走通,GET v1 因降级无历史 → 404
    WorkflowDefinitionDetailResponse detail =
        new WorkflowDefinitionDetailResponse(
            42L,
            "ta",
            "wf_ok",
            "wf",
            "DAG",
            3,
            Boolean.TRUE,
            null,
            Instant.parse("2026-06-01T00:00:00Z"),
            Instant.parse("2026-06-04T03:00:00Z"),
            List.of(),
            List.of());
    when(service.getVersion(eq(42L), eq("ta"), eq(3))).thenReturn(detail);
    when(service.getVersion(eq(42L), eq("ta"), eq(1)))
        .thenThrow(
            BizException.of(ResultCode.NOT_FOUND, "error.workflow_version.not_found", 42L, 1, 3));

    // 执行并断言 (current version OK)
    mockMvc
        .perform(get("/api/console/workflow-definitions/42/versions/3").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.version").value(3))
        .andExpect(jsonPath("$.data.workflowCode").value("wf_ok"));

    // 执行并断言 (stale version → 404)
    mockMvc
        .perform(get("/api/console/workflow-definitions/42/versions/1").param("tenantId", "ta"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}

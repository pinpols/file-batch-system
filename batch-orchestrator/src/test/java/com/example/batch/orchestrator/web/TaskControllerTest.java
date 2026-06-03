package com.example.batch.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.application.service.task.TaskAssignmentService.TaskHeartbeatResult;
import com.example.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import com.example.batch.orchestrator.application.service.task.TaskExecutionService;
import com.example.batch.orchestrator.controller.OrchestratorApiExceptionHandler;
import com.example.batch.orchestrator.controller.TaskController;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TaskControllerTest {

  private final TaskExecutionService taskExecutionService = mock(TaskExecutionService.class);
  private final TaskControllerApplicationService taskControllerApplicationService =
      new TaskControllerApplicationService(taskExecutionService, new ObjectMapper());

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new TaskController(taskControllerApplicationService))
          .setControllerAdvice(OrchestratorApiExceptionHandler.forStandaloneTest())
          .build();

  @Test
  void shouldReturn200WhenClaimSucceeds() throws Exception {
    JobTaskEntity task = new JobTaskEntity();
    task.setTaskStatus(TaskStatus.RUNNING.code());
    task.setAssignedWorkerCode("w1");
    when(taskExecutionService.assignWorker(eq("t1"), eq(10L), eq("w1"))).thenReturn(task);

    mockMvc
        .perform(
            post("/internal/tasks/10/claim")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void shouldReturnEffectiveConfigBodyWhenClaimSucceeds() throws Exception {
    JobTaskEntity task = new JobTaskEntity();
    task.setTaskStatus(TaskStatus.RUNNING.code());
    task.setAssignedWorkerCode("w1");
    when(taskExecutionService.assignWorker(eq("t1"), eq(10L), eq("w1"))).thenReturn(task);
    EffectiveTaskConfig config =
        new EffectiveTaskConfig(
            "t1",
            10L,
            100L,
            200L,
            "INST-1",
            "JOB-X",
            "IMPORT",
            1,
            "IMPORT",
            "HIGH",
            "biz",
            "idem",
            "{}",
            "trace-1",
            "INCREMENTAL",
            "update_time",
            "wm-1",
            "EXPONENTIAL",
            5,
            600,
            1,
            1,
            "JOB-X:2026-05-01:1",
            null,
            null,
            null);
    when(taskExecutionService.loadEffectiveConfig("t1", 10L)).thenReturn(config);

    mockMvc
        .perform(
            post("/internal/tasks/10/claim")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
        .andExpect(jsonPath("$.jobCode").value("JOB-X"))
        .andExpect(jsonPath("$.executionMode").value("INCREMENTAL"))
        .andExpect(jsonPath("$.retryMaxCount").value(5))
        .andExpect(jsonPath("$.timeoutSeconds").value(600))
        .andExpect(jsonPath("$.highWaterMarkIn").value("wm-1"));
  }

  @Test
  void shouldReturn404WhenTaskNotFoundOnClaim() throws Exception {
    when(taskExecutionService.assignWorker(any(), any(), any())).thenReturn(null);

    mockMvc
        .perform(
            post("/internal/tasks/99/claim")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void shouldReturn409WhenClaimConflict() throws Exception {
    JobTaskEntity task = new JobTaskEntity();
    task.setTaskStatus(TaskStatus.RUNNING.code());
    task.setAssignedWorkerCode("other");
    when(taskExecutionService.assignWorker(eq("t1"), eq(10L), eq("w1"))).thenReturn(task);

    mockMvc
        .perform(
            post("/internal/tasks/10/claim")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void shouldReturn200WhenReportAccepted() throws Exception {
    mockMvc
        .perform(
            post("/internal/tasks/5/report")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "tenantId": "t1",
                      "success": true,
                      "resultSummary": "ok"
                    }
                    """))
        .andExpect(status().isOk());
  }

  @Test
  void shouldFallbackToLegacyCodeAndMessageWhenErrorFieldsMissing() throws Exception {
    mockMvc
        .perform(
            post("/internal/tasks/5/report")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "tenantId": "t1",
                      "success": false,
                      "code": "ERR_PARSE",
                      "message": "parse failed"
                    }
                    """))
        .andExpect(status().isOk());

    ArgumentCaptor<TaskOutcomeCommand> captor = ArgumentCaptor.forClass(TaskOutcomeCommand.class);
    verify(taskExecutionService).applyTaskOutcome(captor.capture());
    assertThat(captor.getValue().errorCode()).isEqualTo("ERR_PARSE");
    assertThat(captor.getValue().errorMessage()).isEqualTo("parse failed");
  }

  @Test
  void shouldReturn200WithCancelFalseWhenRenewSucceeds() throws Exception {
    when(taskExecutionService.recordHeartbeat("t1", 7L, "w1", null, null))
        .thenReturn(new TaskHeartbeatResult(true, false));

    mockMvc
        .perform(
            post("/internal/tasks/7/renew")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cancelRequested").value(false));
  }

  @Test
  void shouldReturnCancelRequestedTrueWhenPlatformCancelled() throws Exception {
    when(taskExecutionService.recordHeartbeat("t1", 7L, "w1", null, null))
        .thenReturn(new TaskHeartbeatResult(true, true));

    mockMvc
        .perform(
            post("/internal/tasks/7/renew")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cancelRequested").value(true));
  }

  @Test
  void shouldPersistHeartbeatDetailsWhenRenewCarriesDetails() throws Exception {
    when(taskExecutionService.recordHeartbeat(eq("t1"), eq(7L), eq("w1"), any(), any()))
        .thenReturn(new TaskHeartbeatResult(true, false));

    mockMvc
        .perform(
            post("/internal/tasks/7/renew")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"t1\",\"workerId\":\"w1\",\"details\":{\"processed\":42}}"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
    verify(taskExecutionService)
        .recordHeartbeat(eq("t1"), eq(7L), eq("w1"), any(), details.capture());
    assertThat(details.getValue()).contains("\"processed\":42");
  }

  @Test
  void shouldReturn409WhenRenewRejected() throws Exception {
    when(taskExecutionService.recordHeartbeat("t1", 7L, "w1", null, null))
        .thenReturn(new TaskHeartbeatResult(false, false));

    mockMvc
        .perform(
            post("/internal/tasks/7/renew")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void shouldReturn200WhenCancelRequested() throws Exception {
    when(taskExecutionService.requestCancel("t1", 7L)).thenReturn(true);

    mockMvc
        .perform(
            post("/internal/tasks/7/cancel")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"reason\":\"manual\"}"))
        .andExpect(status().isOk());

    verify(taskExecutionService).requestCancel("t1", 7L);
  }

  @Test
  void shouldReturn200WithPerTaskResultsForRenewBatch() throws Exception {
    when(taskExecutionService.renewTaskLease("t1", 7L, "w1", null)).thenReturn(true);
    when(taskExecutionService.renewTaskLease("t1", 8L, "w1", null)).thenReturn(false);

    mockMvc
        .perform(
            post("/internal/tasks/leases/renew-batch")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "items": [
                        {"tenantId":"t1","taskId":7,"workerId":"w1"},
                        {"tenantId":"t1","taskId":8,"workerId":"w1"}
                      ]
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].taskId").value(7))
        .andExpect(jsonPath("$.results[0].renewed").value(true))
        .andExpect(jsonPath("$.results[1].taskId").value(8))
        .andExpect(jsonPath("$.results[1].renewed").value(false));

    verify(taskExecutionService).renewTaskLease("t1", 7L, "w1", null);
    verify(taskExecutionService).renewTaskLease("t1", 8L, "w1", null);
  }
}

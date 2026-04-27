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
import com.example.batch.orchestrator.application.service.TaskControllerApplicationService;
import com.example.batch.orchestrator.application.service.TaskExecutionService;
import com.example.batch.orchestrator.controller.OrchestratorApiExceptionHandler;
import com.example.batch.orchestrator.controller.TaskController;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TaskControllerTest {

  private final TaskExecutionService taskExecutionService = mock(TaskExecutionService.class);
  private final TaskControllerApplicationService taskControllerApplicationService =
      new TaskControllerApplicationService(taskExecutionService);

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new TaskController(taskControllerApplicationService))
          .setControllerAdvice(new OrchestratorApiExceptionHandler())
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
            600);
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
  void shouldReturn200WhenRenewSucceeds() throws Exception {
    when(taskExecutionService.renewTaskLease("t1", 7L, "w1")).thenReturn(true);

    mockMvc
        .perform(
            post("/internal/tasks/7/renew")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void shouldReturn409WhenRenewRejected() throws Exception {
    when(taskExecutionService.renewTaskLease("t1", 7L, "w1")).thenReturn(false);

    mockMvc
        .perform(
            post("/internal/tasks/7/renew")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isConflict());
  }
}

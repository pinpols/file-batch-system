package io.github.pinpols.batch.orchestrator.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.orchestrator.application.service.governance.DeadLetterOrphanSourceException;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.controller.DeadLetterController;
import io.github.pinpols.batch.orchestrator.controller.OrchestratorApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("死信重放接口:成功重放返回成功并转发入参,死信来源缺失时返回冲突")
class DeadLetterControllerTest {

  @Mock
  private RetryGovernanceService retryGovernanceService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new DeadLetterController(retryGovernanceService))
        .setControllerAdvice(OrchestratorApiExceptionHandler.forStandaloneTest())
        .build();
  }

  @Test
  @DisplayName("重放请求仅带租户时返回成功,并按租户与死信编号发起重放")
  void shouldReplayDeadLetter() throws Exception {
    mockMvc
        .perform(post("/internal/dead-letters/42/replay")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\"}"))
        .andExpect(status().isOk());

    verify(retryGovernanceService).replayDeadLetter("t1", 42L, null, null, null);
  }

  @Test
  @DisplayName("重放请求携带操作人,原因与幂等键时,这些字段原样转发给服务")
  void shouldForwardOperatorAndReasonToService() throws Exception {
    mockMvc
        .perform(post("/internal/dead-letters/77/replay")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"operatorId\":\"alice\","
                + "\"reason\":\"manual recovery\",\"idempotencyKey\":\"k-1\"}"))
        .andExpect(status().isOk());

    verify(retryGovernanceService).replayDeadLetter("t1", 77L, "alice", "manual recovery", "k-1");
  }

  @Test
  @DisplayName("重放时死信来源已缺失则返回冲突状态")
  void shouldReturnConflictWhenReplayEncountersOrphanDeadLetterSource() throws Exception {
    doThrow(new DeadLetterOrphanSourceException("orphan"))
        .when(retryGovernanceService)
        .replayDeadLetter("t1", 99L, null, null, null);

    mockMvc
        .perform(post("/internal/dead-letters/99/replay")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\"}"))
        .andExpect(status().isConflict());
  }
}

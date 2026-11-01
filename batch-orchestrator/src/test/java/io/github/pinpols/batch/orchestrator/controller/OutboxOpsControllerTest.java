package io.github.pinpols.batch.orchestrator.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.orchestrator.application.service.governance.OutboxOpsApplicationService;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.OutboxEventMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("内部 Outbox 清理接口保护保留天数")
class OutboxOpsControllerTest {

  @Mock
  private OutboxEventMapper outboxMapper;

  @Mock
  private JobExecutionLogMapper logMapper;

  @ParameterizedTest
  @CsvSource({"0, false", "-1, false", "0, true", "-1, true"})
  @DisplayName("非法保留天数返回 400 且不触达数据层")
  void shouldRejectInvalidRetention(int retainDays, boolean dryRun) throws Exception {
    OutboxOpsApplicationService service = new OutboxOpsApplicationService(outboxMapper, logMapper);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new OutboxOpsController(service))
        .setControllerAdvice(OrchestratorApiExceptionHandler.forStandaloneTest())
        .build();

    mvc.perform(post("/internal/outbox/cleanup")
            .param("tenantId", "tenant-a")
            .param("retainDays", String.valueOf(retainDays))
            .param("dryRun", String.valueOf(dryRun)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    verifyNoInteractions(outboxMapper, logMapper);
  }
}

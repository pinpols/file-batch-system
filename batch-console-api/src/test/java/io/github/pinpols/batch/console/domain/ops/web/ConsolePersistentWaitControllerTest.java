package io.github.pinpols.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.application.ops.query.ConsolePersistentWaitQuery;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("Console 持久等待查询参数")
class ConsolePersistentWaitControllerTest {

  @Mock
  private ConsoleOrchestratorPort orchestrator;

  @Mock
  private ConsoleResponseFactory responses;

  @Test
  @DisplayName("查询参数保持既有名称并使用原分页默认值")
  void shouldKeepQueryContractAndPageDefaults() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ConsolePersistentWaitController(orchestrator, responses))
        .build();

    mvc.perform(get("/api/console/ops/persistent-waits")
            .param("tenantId", "tenant-a")
            .param("waitType", "READINESS")
            .param("bizDateFrom", "2026-10-01"))
        .andExpect(status().isOk());

    ArgumentCaptor<ConsolePersistentWaitQuery> query =
        ArgumentCaptor.forClass(ConsolePersistentWaitQuery.class);
    verify(orchestrator).listPersistentWaits(query.capture());
    ConsolePersistentWaitQuery actual = query.getValue();
    assertThat(actual.tenantId()).isEqualTo("tenant-a");
    assertThat(actual.waitType()).isEqualTo("READINESS");
    assertThat(actual.waitStatus()).isNull();
    assertThat(actual.workflowDefinitionId()).isNull();
    assertThat(actual.bizDateFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(actual.bizDateTo()).isNull();
    assertThat(actual.pageNo()).isEqualTo(1);
    assertThat(actual.pageSize()).isEqualTo(15);
  }

  @Test
  @DisplayName("缺少租户时拒绝查询")
  void shouldRejectMissingTenant() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ConsolePersistentWaitController(orchestrator, responses))
        .build();

    mvc.perform(get("/api/console/ops/persistent-waits")).andExpect(status().isBadRequest());
    verifyNoInteractions(orchestrator);
  }
}

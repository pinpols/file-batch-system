package io.github.pinpols.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.application.ops.query.ConsoleWaitRelationshipQuery;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("等待关系接口的租户和游标校验")
class ConsoleWaitRelationshipControllerTest {
  @Mock
  private ConsoleOrchestratorPort orchestrator;

  @Mock
  private ConsoleResponseFactory responses;

  @Test
  @DisplayName("按租户和精确游标转发查询")
  void shouldForwardTenantFilterAndExactLargeCursor() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ConsoleWaitRelationshipController(orchestrator, responses))
        .build();
    mvc.perform(get("/api/console/ops/wait-relationships")
            .param("tenantId", "tenant-a")
            .param("waitType", "JOB")
            .param("cursorType", "JOB")
            .param("cursorId", "9007199254740993")
            .param("pageSize", "15"))
        .andExpect(status().isOk());
    ArgumentCaptor<ConsoleWaitRelationshipQuery> captured =
        ArgumentCaptor.forClass(ConsoleWaitRelationshipQuery.class);
    verify(orchestrator).listWaitRelationships(captured.capture());
    assertThat(captured.getValue().tenantId()).isEqualTo("tenant-a");
    assertThat(captured.getValue().cursorId()).isEqualTo("9007199254740993");
  }

  @Test
  @DisplayName("拒绝不成对或溢出的游标")
  void shouldRejectUnpairedAndOverflowingCursors() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ConsoleWaitRelationshipController(orchestrator, responses))
        .build();
    mvc.perform(get("/api/console/ops/wait-relationships")
            .param("tenantId", "tenant-a")
            .param("cursorType", "JOB"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/console/ops/wait-relationships")
            .param("tenantId", "tenant-a")
            .param("cursorType", "JOB")
            .param("cursorId", "9999999999999999999"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(orchestrator);
  }
}

package io.github.pinpols.batch.console.domain.job.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Console 日历依赖等待的租户与操作身份边界")
@ExtendWith(MockitoExtension.class)
class ConsoleCalendarDependencyWaitControllerTest {

  @Mock
  private ConsoleOrchestratorPort orchestrator;

  @Mock
  private ConsoleRequestMetadataResolver metadataResolver;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(metadataResolver);
    when(metadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleCalendarDependencyWaitController(
            orchestrator, responseFactory, metadataResolver))
        .setControllerAdvice(ConsoleApiExceptionHandler.forStandaloneTest(responseFactory))
        .build();
  }

  @DisplayName("按租户查询日历依赖等待")
  @Test
  void shouldListTenantScopedWaits() throws Exception {
    when(orchestrator.listCalendarDependencyWaits("tenant-a", "CAL_A", "WAITING", 50))
        .thenReturn(List.of());
    mockMvc
        .perform(get("/api/console/calendar-dependency-waits")
            .param("tenantId", "tenant-a")
            .param("calendarCode", "CAL_A")
            .param("waitStatus", "WAITING")
            .param("limit", "50"))
        .andExpect(status().isOk());
    verify(orchestrator).listCalendarDependencyWaits("tenant-a", "CAL_A", "WAITING", 50);
  }

  @DisplayName("处置使用已认证操作者身份")
  @Test
  void shouldUseAuthenticatedOperatorInsteadOfClientSuppliedActor() throws Exception {
    when(metadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("r", "t", "tenant-a", "admin-user", null, null));
    mockMvc
        .perform(post("/api/console/calendar-dependency-waits/manual-action")
            .contentType(APPLICATION_JSON)
            .content("""
                {"tenantId":"tenant-a","calendarCode":"CAL_A","bizDate":"2026-10-13",
                 "action":"SKIP","reason":"source cancelled"}
                """))
        .andExpect(status().isOk());
    verify(orchestrator)
        .requestCalendarDependencyWaitAction(ArgumentMatchers.argThat(command -> command != null
            && Objects.equals(command.tenantId(), "tenant-a")
            && Objects.equals(command.calendarCode(), "CAL_A")
            && Objects.equals(command.bizDate(), LocalDate.parse("2026-10-13"))
            && Objects.equals(command.action(), "SKIP")
            && Objects.equals(command.operatorId(), "admin-user")
            && Objects.equals(command.reason(), "source cancelled")));
  }

  @DisplayName("拒绝不支持的人工处置动作")
  @Test
  void shouldRejectUnsupportedManualAction() throws Exception {
    mockMvc
        .perform(post("/api/console/calendar-dependency-waits/manual-action")
            .contentType(APPLICATION_JSON)
            .content("""
                {"tenantId":"tenant-a","calendarCode":"CAL_A","bizDate":"2026-10-13",
                 "action":"OPEN","reason":"bad action"}
                """))
        .andExpect(status().isBadRequest());
  }
}

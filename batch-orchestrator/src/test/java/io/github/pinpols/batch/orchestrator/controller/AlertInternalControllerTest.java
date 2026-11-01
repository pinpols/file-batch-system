package io.github.pinpols.batch.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.domain.command.AlertEmitCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("内部告警入口保留请求字段并委派应用服务")
class AlertInternalControllerTest {

  @Mock
  private AlertEventService alertEventService;

  @Test
  @DisplayName("忽略扩展字段并将已有字段完整传递给服务")
  void shouldMapAlertRequestToCommand() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new AlertInternalController(alertEventService))
        .build();
    String body = """
        {"tenantId":"t1","serviceName":"worker","alertType":"SLA","severity":"WARN",
         "title":"timeout","detailJson":"{}","resourceKey":"job:1","traceId":"trace-1",
         "futureField":"ignored"}
        """;

    mvc.perform(post("/internal/alerts").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());

    ArgumentCaptor<AlertEmitCommand> captor = ArgumentCaptor.forClass(AlertEmitCommand.class);
    verify(alertEventService).emit(captor.capture());
    AlertEmitCommand expected = AlertEmitCommand.builder()
        .tenantId("t1")
        .serviceName("worker")
        .alertType("SLA")
        .severity("WARN")
        .title("timeout")
        .detailJson("{}")
        .resourceKey("job:1")
        .traceId("trace-1")
        .build();
    assertThat(captor.getValue()).isEqualTo(expected);
  }
}

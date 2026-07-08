package io.github.pinpols.batch.console.domain.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleNotificationApplicationService;
import io.github.pinpols.batch.console.domain.notification.web.request.NotificationChannelUpdateRequest;
import io.github.pinpols.batch.console.domain.notification.web.request.NotificationChannelUpsertRequest;
import io.github.pinpols.batch.console.domain.notification.web.request.SubscriptionRuleUpsertRequest;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleNotificationControllerTest {

  private final ConsoleNotificationApplicationService applicationService =
      mock(ConsoleNotificationApplicationService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "t1", "operator-1", null, "127.0.0.1"));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleNotificationController(applicationService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldListChannels() throws Exception {
    when(applicationService.listChannels("t1"))
        .thenReturn(List.of(Map.of("channelCode", "mail-1", "channelType", "EMAIL")));

    mockMvc
        .perform(get("/api/console/notifications/channels").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].channelCode").value("mail-1"));
  }

  @Test
  void shouldCreateRule() throws Exception {
    mockMvc
        .perform(
            post("/api/console/notifications/rules")
                .param("tenantId", "t1")
                .header("Idempotency-Key", "idem-1")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "ruleName":"high-priority",
                      "channelCode":"mail-1",
                      "eventTypes":"JOB_FAILED,JOB_TIMEOUT",
                      "severityFilter":"HIGH",
                      "enabled":true
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    ArgumentCaptor<SubscriptionRuleUpsertRequest> captor =
        ArgumentCaptor.forClass(SubscriptionRuleUpsertRequest.class);
    verify(applicationService).createRule(eq("t1"), captor.capture());
    SubscriptionRuleUpsertRequest req = captor.getValue();
    assertThat(req.getRuleName()).isEqualTo("high-priority");
    assertThat(req.getChannelCode()).isEqualTo("mail-1");
    assertThat(req.getEventTypes()).isEqualTo("JOB_FAILED,JOB_TIMEOUT");
    assertThat(req.getSeverityFilter()).isEqualTo("HIGH");
    assertThat(req.getJobCodeFilter()).isNull();
    assertThat(req.getEnabled()).isTrue();
  }

  @Test
  void shouldCreateChannelWithTypedBodyAndDefaultEnabled() throws Exception {
    // 反序列化守护:JSON 字段名与旧 Map 消费键逐一对应;enabled 缺省为 true。
    mockMvc
        .perform(
            post("/api/console/notifications/channels")
                .param("tenantId", "t1")
                .header("Idempotency-Key", "idem-2")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "channelCode":"mail-1",
                      "channelName":"Mail One",
                      "channelType":"EMAIL",
                      "configJson":"{\\"to\\":\\"ops@example.com\\"}"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    ArgumentCaptor<NotificationChannelUpsertRequest> captor =
        ArgumentCaptor.forClass(NotificationChannelUpsertRequest.class);
    verify(applicationService).createChannel(eq("t1"), captor.capture());
    NotificationChannelUpsertRequest req = captor.getValue();
    assertThat(req.getChannelCode()).isEqualTo("mail-1");
    assertThat(req.getChannelName()).isEqualTo("Mail One");
    assertThat(req.getChannelType()).isEqualTo("EMAIL");
    assertThat(req.getConfigJson()).isEqualTo("{\"to\":\"ops@example.com\"}");
    assertThat(req.getEnabled()).isTrue();
  }

  @Test
  void shouldRejectChannelCreateWithoutChannelCode() throws Exception {
    mockMvc
        .perform(
            post("/api/console/notifications/channels")
                .param("tenantId", "t1")
                .header("Idempotency-Key", "idem-3")
                .contentType(APPLICATION_JSON)
                .content("{\"channelName\":\"Mail One\",\"channelType\":\"EMAIL\"}"))
        .andExpect(status().isBadRequest());
    verify(applicationService, never()).createChannel(eq("t1"), any());
  }

  @Test
  void shouldUpdateChannelWithoutChannelCodeInBody() throws Exception {
    // 兼容守护:update body 不要求 channelCode(路径参数为准),字段名与旧 Map 消费键一致。
    mockMvc
        .perform(
            put("/api/console/notifications/channels/mail-1")
                .param("tenantId", "t1")
                .header("Idempotency-Key", "idem-4")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "channelName":"Mail Renamed",
                      "channelType":"WEBHOOK",
                      "configJson":"{\\"url\\":\\"https://example.com/hook\\"}",
                      "enabled":false
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    ArgumentCaptor<NotificationChannelUpdateRequest> captor =
        ArgumentCaptor.forClass(NotificationChannelUpdateRequest.class);
    verify(applicationService).updateChannel(eq("t1"), eq("mail-1"), captor.capture());
    NotificationChannelUpdateRequest req = captor.getValue();
    assertThat(req.getChannelName()).isEqualTo("Mail Renamed");
    assertThat(req.getChannelType()).isEqualTo("WEBHOOK");
    assertThat(req.getEnabled()).isFalse();
  }

  @Test
  void shouldRejectRuleCreateWithoutRuleName() throws Exception {
    mockMvc
        .perform(
            post("/api/console/notifications/rules")
                .param("tenantId", "t1")
                .header("Idempotency-Key", "idem-5")
                .contentType(APPLICATION_JSON)
                .content("{\"channelCode\":\"mail-1\",\"eventTypes\":\"JOB_FAILED\"}"))
        .andExpect(status().isBadRequest());
    verify(applicationService, never()).createRule(eq("t1"), any());
  }
}

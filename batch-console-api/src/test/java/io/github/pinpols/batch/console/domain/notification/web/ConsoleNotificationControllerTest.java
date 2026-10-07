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
import io.github.pinpols.batch.console.domain.notification.application.contract.request.NotificationChannelUpdateRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.NotificationChannelUpsertRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.SubscriptionRuleUpsertRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleNotificationChannelResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@DisplayName("通知配置接口: 渠道与订阅规则的字段绑定和必填校验")
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

    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleNotificationController(applicationService, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("按租户返回渠道清单, 响应字段保持下划线命名")
  void shouldListChannels() throws Exception {
    // 生产 NotificationChannelMapper 以 resultType=map 返回 snake_case 列键（channel_code），
    // 类型化响应经 @JsonProperty 保持 snake_case wire 一字不差。
    when(applicationService.listChannels("t1"))
        .thenReturn(List.of(ConsoleNotificationChannelResponse.from(
            Map.of("channel_code", "mail-1", "channel_type", "EMAIL"))));

    mockMvc
        .perform(get("/api/console/notifications/channels").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].channel_code").value("mail-1"));
  }

  @Test
  @DisplayName("创建订阅规则时字段逐一绑定到请求对象, 未给的作业编码过滤为空")
  void shouldCreateRule() throws Exception {
    mockMvc
        .perform(post("/api/console/notifications/rules")
            .param("tenantId", "t1")
            .header("Idempotency-Key", "idem-1")
            .contentType(APPLICATION_JSON)
            .content("""
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
  @DisplayName("创建渠道按类型化请求体绑定字段, 未给启用标记时默认为启用")
  void shouldCreateChannelWithTypedBodyAndDefaultEnabled() throws Exception {
    // 反序列化守护:JSON 字段名与旧 Map 消费键逐一对应;enabled 缺省为 true。
    mockMvc
        .perform(post("/api/console/notifications/channels")
            .param("tenantId", "t1")
            .header("Idempotency-Key", "idem-2")
            .contentType(APPLICATION_JSON)
            .content("""
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
  @DisplayName("创建渠道缺少渠道编码时返回参数错误, 不调用应用服务")
  void shouldRejectChannelCreateWithoutChannelCode() throws Exception {
    mockMvc
        .perform(post("/api/console/notifications/channels")
            .param("tenantId", "t1")
            .header("Idempotency-Key", "idem-3")
            .contentType(APPLICATION_JSON)
            .content("{\"channelName\":\"Mail One\",\"channelType\":\"EMAIL\"}"))
        .andExpect(status().isBadRequest());
    verify(applicationService, never()).createChannel(eq("t1"), any());
  }

  @Test
  @DisplayName("更新渠道不要求请求体带渠道编码, 以路径参数为准")
  void shouldUpdateChannelWithoutChannelCodeInBody() throws Exception {
    // 兼容守护:update body 不要求 channelCode(路径参数为准),字段名与旧 Map 消费键一致。
    mockMvc
        .perform(put("/api/console/notifications/channels/mail-1")
            .param("tenantId", "t1")
            .header("Idempotency-Key", "idem-4")
            .contentType(APPLICATION_JSON)
            .content("""
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
  @DisplayName("创建规则缺少规则名称时返回参数错误, 不调用应用服务")
  void shouldRejectRuleCreateWithoutRuleName() throws Exception {
    mockMvc
        .perform(post("/api/console/notifications/rules")
            .param("tenantId", "t1")
            .header("Idempotency-Key", "idem-5")
            .contentType(APPLICATION_JSON)
            .content("{\"channelCode\":\"mail-1\",\"eventTypes\":\"JOB_FAILED\"}"))
        .andExpect(status().isBadRequest());
    verify(applicationService, never()).createRule(eq("t1"), any());
  }
}

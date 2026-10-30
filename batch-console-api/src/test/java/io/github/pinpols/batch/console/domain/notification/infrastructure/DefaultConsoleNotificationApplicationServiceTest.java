package io.github.pinpols.batch.console.domain.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.NotificationChannelUpdateRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.NotificationChannelUpsertRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.SubscriptionRuleUpsertRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleNotificationChannelResponse;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleNotificationDeliveryLogResponse;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleNotificationTestResultResponse;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationChannelMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.param.NotificationChannelInsertParam;
import io.github.pinpols.batch.console.domain.notification.param.NotificationChannelUpdateParam;
import io.github.pinpols.batch.console.domain.notification.param.NotificationDeliveryLogInsertParam;
import io.github.pinpols.batch.console.domain.notification.param.SubscriptionRuleInsertParam;
import io.github.pinpols.batch.console.domain.notification.param.SubscriptionRuleUpdateParam;
import io.github.pinpols.batch.console.domain.notification.service.NotificationMessage;
import io.github.pinpols.batch.console.domain.notification.service.NotificationSender;
import io.github.pinpols.batch.console.domain.notification.service.NotificationSenderRegistry;
import io.github.pinpols.batch.console.domain.notification.service.WebhookDeliveryResult;
import io.github.pinpols.batch.console.domain.notification.service.WebhookDispatcher;
import io.github.pinpols.batch.console.domain.notification.service.WebhookEventPayload;
import io.github.pinpols.batch.console.domain.notification.view.NotificationChannelRow;
import io.github.pinpols.batch.console.domain.notification.view.NotificationDeliveryLogRow;
import io.github.pinpols.batch.console.domain.notification.view.SubscriptionRuleRow;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.CallbackUrlValidator;
import io.github.pinpols.batch.console.support.ratelimit.SlidingWindowRateLimiter;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

@DisplayName("通知渠道与订阅规则服务: 参数校验, 持久化字段组装与测试发送链路")
class DefaultConsoleNotificationApplicationServiceTest {

  @DisplayName("创建通知规则前拒绝仅用于告警的事件类型")
  @ParameterizedTest
  @ValueSource(
      strings = {
        "JOB_SLA_BREACH",
        " job_sla_violation ",
        "JOB_SLA_VIOLATION_ESCALATED",
        "ＪＯＢ＿ＳＬＡ＿ＢＲＥＡＣＨ",
        "JOB_SLA_\001BREACH"
      })
  void shouldRejectAlertOnlyTypesBeforeCreatingRule(String eventType) {
    when(channelMapper.selectByCode("tenant-a", "email-1"))
        .thenReturn(channelRow("email-1", "EMAIL", null));
    assertThatThrownBy(() -> service.createRule(
            "tenant-a", ruleUpsert("sla", "email-1", "JOB_FAILED," + eventType, null, null, true)))
        .isInstanceOfSatisfying(
            BizException.class,
            ex -> assertThat(ex.getMessageKey()).isEqualTo("error.notification.sla_alert_channel"));
    verify(ruleMapper, never()).insert(any());
  }

  @DisplayName("更新通知规则前拒绝仅用于告警的事件类型")
  @ParameterizedTest
  @ValueSource(
      strings = {
        "JOB_SLA_BREACH",
        " job_sla_violation ",
        "JOB_SLA_VIOLATION_ESCALATED",
        "ＪＯＢ＿ＳＬＡ＿ＢＲＥＡＣＨ",
        "JOB_SLA_\001BREACH"
      })
  void shouldRejectAlertOnlyTypesBeforeUpdatingRule(String eventType) {
    when(ruleMapper.selectById("tenant-a", 7L))
        .thenReturn(new SubscriptionRuleRow(
            7L, "tenant-a", null, null, null, null, null, true, null, null, null, null));
    when(channelMapper.selectByCode("tenant-a", "email-1"))
        .thenReturn(channelRow("email-1", "EMAIL", null));
    assertThatThrownBy(() -> service.updateRule(
            "tenant-a", 7L, ruleUpsert("sla", "email-1", eventType, null, null, true)))
        .isInstanceOfSatisfying(
            BizException.class,
            ex -> assertThat(ex.getMessageKey()).isEqualTo("error.notification.sla_alert_channel"));
    verify(ruleMapper, never()).update(any());
  }

  private ConsoleTenantGuard tenantGuard;
  private ConsoleRequestMetadataResolver metadataResolver;
  private NotificationChannelMapper channelMapper;
  private SubscriptionRuleMapper ruleMapper;
  private NotificationDeliveryLogMapper deliveryLogMapper;
  private NotificationSenderRegistry senderRegistry;
  private WebhookDispatcher webhookDispatcher;
  private SlidingWindowRateLimiter testChannelRateLimiter;
  private CallbackUrlValidator callbackUrlValidator;
  private DefaultConsoleNotificationApplicationService service;

  @BeforeEach
  void setUp() {
    tenantGuard = mock(ConsoleTenantGuard.class);
    metadataResolver = mock(ConsoleRequestMetadataResolver.class);
    channelMapper = mock(NotificationChannelMapper.class);
    ruleMapper = mock(SubscriptionRuleMapper.class);
    deliveryLogMapper = mock(NotificationDeliveryLogMapper.class);
    senderRegistry = mock(NotificationSenderRegistry.class);
    webhookDispatcher = mock(WebhookDispatcher.class);
    testChannelRateLimiter = mock(SlidingWindowRateLimiter.class);
    when(testChannelRateLimiter.tryAcquire(any(), ArgumentMatchers.anyInt())).thenReturn(true);
    callbackUrlValidator = mock(CallbackUrlValidator.class);
    service = new DefaultConsoleNotificationApplicationService(
        tenantGuard,
        metadataResolver,
        channelMapper,
        ruleMapper,
        deliveryLogMapper,
        senderRegistry,
        webhookDispatcher,
        testChannelRateLimiter,
        callbackUrlValidator);
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(metadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata(
            "req-1", "trace-1", "tenant-a", "operator-1", "idem-1", "127.0.0.1"));
  }

  @Test
  @DisplayName("按租户列出通知渠道并转换为对外响应对象")
  void shouldListChannels() {
    when(channelMapper.selectByTenant("tenant-a"))
        .thenReturn(List.of(channelRow("email-1", "EMAIL", null)));

    List<ConsoleNotificationChannelResponse> result = service.listChannels("tenant-a");

    assertThat(result).hasSize(1);
    assertThat(result.get(0).channelCode()).isEqualTo("email-1");
  }

  @Test
  @DisplayName("按渠道编码查询单个通知渠道的详情")
  void shouldGetChannel() {
    when(channelMapper.selectByCode("tenant-a", "email-1"))
        .thenReturn(channelRow("email-1", "EMAIL", null));

    ConsoleNotificationChannelResponse result = service.getChannel("tenant-a", "email-1");

    assertThat(result.channelCode()).isEqualTo("email-1");
  }

  @Test
  @DisplayName("渠道记录缺失时抛出未找到业务异常")
  void shouldThrowWhenChannelMissing() {
    when(channelMapper.selectByCode("tenant-a", "missing")).thenReturn(null);

    assertThatThrownBy(() -> service.getChannel("tenant-a", "missing"))
        .isInstanceOf(BizException.class)
        // i18n: messageKey 仅 key,改为基于 messageKey 含 not_found 或 messageArgs 含 not found
        .satisfies(ex -> {
          BizException bx = (BizException) ex;
          boolean keyMatch = bx.getMessageKey() != null && bx.getMessageKey().contains("not_found");
          boolean argsMatch = bx.getMessageArgs() != null
              && Arrays.stream(bx.getMessageArgs())
                  .anyMatch(a -> a != null && a.toString().toLowerCase().contains("not found"));
          assertThat(keyMatch || argsMatch)
              .as("messageKey or args should imply not_found")
              .isTrue();
        });
  }

  @Test
  @DisplayName("创建渠道时写入租户, 渠道字段与创建人更新人")
  void shouldCreateChannel() {
    when(channelMapper.insert(any())).thenReturn(1);
    when(channelMapper.selectByCode("tenant-a", "email-1")).thenReturn(null);

    service.createChannel(
        "tenant-a",
        channelUpsert(
            "email-1", "Email One", "EMAIL", "{\"url\":\"https://example.com/hook\"}", true));

    ArgumentCaptor<NotificationChannelInsertParam> captor = ArgumentCaptor.captor();
    verify(channelMapper).insert(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("tenant-a");
    assertThat(captor.getValue().channelCode()).isEqualTo("email-1");
    assertThat(captor.getValue().channelName()).isEqualTo("Email One");
    assertThat(captor.getValue().channelType()).isEqualTo("EMAIL");
    assertThat(captor.getValue().configJson()).isEqualTo("{\"url\":\"https://example.com/hook\"}");
    assertThat(captor.getValue().enabled()).isEqualTo(true);
    assertThat(captor.getValue().createdBy()).isEqualTo("operator-1");
    assertThat(captor.getValue().updatedBy()).isEqualTo("operator-1");
  }

  @Test
  @DisplayName("回调地址属于受限网段时创建渠道被拒绝且不写库")
  void shouldValidateWebhookChannelUrlOnCreateAndRejectRestrictedAddress() {
    // Critical 回归堵口:WEBHOOK 渠道 url 存前经 CallbackUrlValidator fail-closed,拦字面量内网/元数据 IP,
    // 不让它入库(OkHttp Dns pin 对字面量 IP 短路不生效,此处是主修)。
    when(channelMapper.selectByCode("tenant-a", "hook-1")).thenReturn(null);
    doThrow(BizException.of(ResultCode.INVALID_ARGUMENT, "error.callback.restricted_address"))
        .when(callbackUrlValidator)
        .validate("https://169.254.169.254/latest/meta-data/");

    assertThatThrownBy(() -> service.createChannel(
            "tenant-a",
            channelUpsert(
                "hook-1",
                "Hook One",
                "WEBHOOK",
                "{\"url\":\"https://169.254.169.254/latest/meta-data/\"}",
                true)))
        .isInstanceOf(BizException.class);

    verify(channelMapper, never()).insert(any());
  }

  @Test
  @DisplayName("非回调类渠道创建时不触发地址安全检查仍正常写库")
  void shouldNotValidateUrlForNonWebhookChannel() {
    when(channelMapper.insert(any())).thenReturn(1);
    // 非 WEBHOOK 渠道(EMAIL/SMS 等)不经 callbackUrl SSRF 校验,避免误伤。
    when(channelMapper.selectByCode("tenant-a", "email-1")).thenReturn(null);

    service.createChannel(
        "tenant-a",
        channelUpsert(
            "email-1", "Email One", "EMAIL", "{\"url\":\"https://example.com/x\"}", true));

    verify(callbackUrlValidator, never()).validate(any());
    verify(channelMapper).insert(any());
  }

  @Test
  @DisplayName("渠道编码已存在时拒绝创建并提示编码重复")
  void shouldRejectDuplicateChannelCode() {
    when(channelMapper.selectByCode("tenant-a", "email-1"))
        .thenReturn(channelRow("email-1", "EMAIL", null));

    assertThatThrownBy(() -> service.createChannel(
            "tenant-a", channelUpsert("email-1", "Email One", "EMAIL", null, true)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("code_already_exists");
  }

  @Test
  @DisplayName("更新渠道时按最新字段与当前操作人写库")
  void shouldUpdateChannel() {
    when(channelMapper.selectByCode("tenant-a", "email-1"))
        .thenReturn(channelRow(
            "email-1",
            "EMAIL",
            "{\"url\":\"https://example.com/old\",\"token\":\"stored-token\"}"));

    NotificationChannelUpdateRequest update = new NotificationChannelUpdateRequest();
    update.setChannelName("Email Updated");
    update.setChannelType("EMAIL");
    update.setConfigJson("{\"url\":\"****\",\"token\":\"****\"}");
    update.setEnabled(false);
    service.updateChannel("tenant-a", "email-1", update);

    ArgumentCaptor<NotificationChannelUpdateParam> captor = ArgumentCaptor.captor();
    verify(channelMapper).update(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("tenant-a");
    assertThat(captor.getValue().channelCode()).isEqualTo("email-1");
    assertThat(captor.getValue().channelName()).isEqualTo("Email Updated");
    assertThat(captor.getValue().channelType()).isEqualTo("EMAIL");
    assertThat(captor.getValue().configJson())
        .isEqualTo("{\"url\":\"https://example.com/old\",\"token\":\"stored-token\"}");
    assertThat(captor.getValue().enabled()).isEqualTo(false);
    assertThat(captor.getValue().updatedBy()).isEqualTo("operator-1");
  }

  @Test
  @DisplayName("创建订阅规则时写入规则字段与创建人更新人")
  void shouldCreateRule() {
    when(channelMapper.selectByCode("tenant-a", "email-1"))
        .thenReturn(channelRow("email-1", "EMAIL", null));

    service.createRule(
        "tenant-a",
        ruleUpsert("default-rule", "email-1", "JOB_SUCCESS,JOB_FAILED", "HIGH", "JOB-*", true));

    ArgumentCaptor<SubscriptionRuleInsertParam> captor = ArgumentCaptor.captor();
    verify(ruleMapper).insert(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("tenant-a");
    assertThat(captor.getValue().ruleName()).isEqualTo("default-rule");
    assertThat(captor.getValue().channelCode()).isEqualTo("email-1");
    assertThat(captor.getValue().eventTypes()).isEqualTo("JOB_SUCCESS,JOB_FAILED");
    assertThat(captor.getValue().severityFilter()).isEqualTo("HIGH");
    assertThat(captor.getValue().jobCodeFilter()).isEqualTo("JOB-*");
    assertThat(captor.getValue().enabled()).isEqualTo(true);
    assertThat(captor.getValue().createdBy()).isEqualTo("operator-1");
    assertThat(captor.getValue().updatedBy()).isEqualTo("operator-1");
  }

  @Test
  @DisplayName("订阅规则引用的渠道缺失时抛出未找到业务异常")
  void shouldRejectRuleWhenChannelMissing() {
    when(channelMapper.selectByCode("tenant-a", "missing")).thenReturn(null);

    assertThatThrownBy(() -> service.createRule(
            "tenant-a", ruleUpsert("default-rule", "missing", "JOB_SUCCESS", null, null, true)))
        .isInstanceOf(BizException.class)
        // i18n: messageKey 仅 key,改为基于 messageKey 含 not_found 或 messageArgs 含 not found
        .satisfies(ex -> {
          BizException bx = (BizException) ex;
          boolean keyMatch = bx.getMessageKey() != null && bx.getMessageKey().contains("not_found");
          boolean argsMatch = bx.getMessageArgs() != null
              && Arrays.stream(bx.getMessageArgs())
                  .anyMatch(a -> a != null && a.toString().toLowerCase().contains("not found"));
          assertThat(keyMatch || argsMatch)
              .as("messageKey or args should imply not_found")
              .isTrue();
        });
  }

  @Test
  @DisplayName("更新订阅规则时按最新字段与当前操作人写库")
  void shouldUpdateRule() {
    when(ruleMapper.selectById("tenant-a", 7L))
        .thenReturn(new SubscriptionRuleRow(
            7L, "tenant-a", null, null, null, null, null, true, null, null, null, null));

    when(channelMapper.selectByCode("tenant-a", "email-1"))
        .thenReturn(channelRow("email-1", "EMAIL", null));
    service.updateRule(
        "tenant-a", 7L, ruleUpsert("updated-rule", "email-1", "JOB_FAILED", "LOW", "JOB-1", false));

    ArgumentCaptor<SubscriptionRuleUpdateParam> captor = ArgumentCaptor.captor();
    verify(ruleMapper).update(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("tenant-a");
    assertThat(captor.getValue().id()).isEqualTo(7L);
    assertThat(captor.getValue().ruleName()).isEqualTo("updated-rule");
    assertThat(captor.getValue().channelCode()).isEqualTo("email-1");
    assertThat(captor.getValue().eventTypes()).isEqualTo("JOB_FAILED");
    assertThat(captor.getValue().severityFilter()).isEqualTo("LOW");
    assertThat(captor.getValue().jobCodeFilter()).isEqualTo("JOB-1");
    assertThat(captor.getValue().enabled()).isEqualTo(false);
    assertThat(captor.getValue().updatedBy()).isEqualTo("operator-1");
  }

  @Test
  @DisplayName("订阅规则记录缺失时抛出未找到业务异常")
  void shouldThrowWhenRuleMissing() {
    when(ruleMapper.selectById("tenant-a", 99L)).thenReturn(null);

    assertThatThrownBy(() -> service.updateRule(
            "tenant-a", 99L, ruleUpsert("r", "email-1", "JOB_FAILED", null, null, true)))
        .isInstanceOf(BizException.class)
        // i18n: messageKey 仅 key,改为基于 messageKey 含 not_found 或 messageArgs 含 not found
        .satisfies(ex -> {
          BizException bx = (BizException) ex;
          boolean keyMatch = bx.getMessageKey() != null && bx.getMessageKey().contains("not_found");
          boolean argsMatch = bx.getMessageArgs() != null
              && Arrays.stream(bx.getMessageArgs())
                  .anyMatch(a -> a != null && a.toString().toLowerCase().contains("not found"));
          assertThat(keyMatch || argsMatch)
              .as("messageKey or args should imply not_found")
              .isTrue();
        });
  }

  private static NotificationChannelRow channelRow(
      String channelCode, String channelType, String configJson) {
    return new NotificationChannelRow(
        1L,
        "tenant-a",
        channelCode,
        channelCode,
        channelType,
        configJson,
        true,
        "operator-1",
        "operator-1",
        null,
        null);
  }

  private static NotificationChannelUpsertRequest channelUpsert(
      String channelCode,
      String channelName,
      String channelType,
      String configJson,
      Boolean enabled) {
    NotificationChannelUpsertRequest req = new NotificationChannelUpsertRequest();
    req.setChannelCode(channelCode);
    req.setChannelName(channelName);
    req.setChannelType(channelType);
    req.setConfigJson(configJson);
    req.setEnabled(enabled);
    return req;
  }

  private static SubscriptionRuleUpsertRequest ruleUpsert(
      String ruleName,
      String channelCode,
      String eventTypes,
      String severityFilter,
      String jobCodeFilter,
      Boolean enabled) {
    SubscriptionRuleUpsertRequest req = new SubscriptionRuleUpsertRequest();
    req.setRuleName(ruleName);
    req.setChannelCode(channelCode);
    req.setEventTypes(eventTypes);
    req.setSeverityFilter(severityFilter);
    req.setJobCodeFilter(jobCodeFilter);
    req.setEnabled(enabled);
    return req;
  }

  @Test
  @DisplayName("查询投递日志时请求条数超过上限被收敛为五百条")
  void shouldReturnLogsAndCapLimit() {
    when(deliveryLogMapper.selectByTenant("tenant-a", 500))
        .thenReturn(List.of(new NotificationDeliveryLogRow(
            1L, null, null, null, null, null, null, null, null, null, null, null, null)));

    List<ConsoleNotificationDeliveryLogResponse> logs = service.deliveryLogs("tenant-a", 999);

    assertThat(logs).hasSize(1);
    verify(deliveryLogMapper).bindTenant("tenant-a");
    verify(deliveryLogMapper).selectByTenant("tenant-a", 500);
  }

  @Test
  @DisplayName("渠道测试会真实调用发送器, 成功后返回正常状态并写入投递日志")
  void shouldTestChannelBySendingRealMessageAndLogSuccess() {
    when(channelMapper.selectByCode("tenant-a", "wecom-1"))
        .thenReturn(channelRow(
            "wecom-1", "WECOM", "{\"url\":\"https://qyapi.weixin.qq.com/robot?key=x\"}"));
    NotificationSender sender = mock(NotificationSender.class);
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any(NotificationMessage.class))).thenReturn(WebhookDeliveryResult.ok());

    ConsoleNotificationTestResultResponse result = service.testChannel("tenant-a", "wecom-1");

    // 真调了 sender(不再是假成功)
    ArgumentCaptor<NotificationMessage> msgCaptor = ArgumentCaptor.captor();
    verify(sender).send(msgCaptor.capture());
    assertThat(msgCaptor.getValue().channelType()).isEqualTo("WECOM");
    assertThat(msgCaptor.getValue().payload().eventType()).isEqualTo("TEST");

    assertThat(result.channelCode()).isEqualTo("wecom-1");
    assertThat(result.channelType()).isEqualTo("WECOM");
    assertThat(result.success()).isTrue();
    assertThat(result.status()).isEqualTo("OK");

    ArgumentCaptor<NotificationDeliveryLogInsertParam> captor = ArgumentCaptor.captor();
    verify(deliveryLogMapper).insert(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("tenant-a");
    assertThat(captor.getValue().ruleId()).isEqualTo(0L);
    assertThat(captor.getValue().channelCode()).isEqualTo("wecom-1");
    assertThat(captor.getValue().eventType()).isEqualTo("TEST");
    assertThat(captor.getValue().deliveryStatus()).isEqualTo("SUCCESS");
    assertThat(captor.getValue().attempt()).isEqualTo(1);
  }

  @Test
  @DisplayName("发送失败时返回失败状态并透传错误摘要, 日志记录失败原因")
  void shouldReportFailureAndPassThroughErrorWhenSendFails() {
    when(channelMapper.selectByCode("tenant-a", "wecom-1"))
        .thenReturn(channelRow(
            "wecom-1", "WECOM", "{\"url\":\"https://qyapi.weixin.qq.com/robot?key=x\"}"));
    NotificationSender sender = mock(NotificationSender.class);
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any(NotificationMessage.class)))
        .thenReturn(WebhookDeliveryResult.failure(200, "wecom errcode=93000"));

    ConsoleNotificationTestResultResponse result = service.testChannel("tenant-a", "wecom-1");

    assertThat(result.success()).isFalse();
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.errorSummary()).isEqualTo("wecom errcode=93000");
    assertThat(result.message()).contains("wecom errcode=93000");

    ArgumentCaptor<NotificationDeliveryLogInsertParam> captor = ArgumentCaptor.captor();
    verify(deliveryLogMapper).insert(captor.capture());
    assertThat(captor.getValue().deliveryStatus()).isEqualTo("FAILED");
    assertThat(captor.getValue().errorMessage()).isEqualTo("wecom errcode=93000");
  }

  @Test
  @DisplayName("渠道测试失败时响应与投递审计不回显发送器摘要中的凭据")
  void shouldMaskSenderCredentialsInChannelTestResponseAndAudit() {
    when(channelMapper.selectByCode("tenant-a", "wecom-1"))
        .thenReturn(channelRow(
            "wecom-1", "WECOM", "{\"url\":\"https://qyapi.weixin.qq.com/robot?key=x\"}"));
    NotificationSender sender = mock(NotificationSender.class);
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any(NotificationMessage.class)))
        .thenReturn(
            WebhookDeliveryResult.failure(
                500,
                "{&quot;password&quot;:&quot;stored-secret&quot;} ｔｏｋｅｎ=unicode-secret; &lt;script&gt; retry later"));

    ConsoleNotificationTestResultResponse result = service.testChannel("tenant-a", "wecom-1");

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary())
        .contains("password", "token=****", "&lt;script&gt;", "retry later")
        .doesNotContain("stored-secret", "unicode-secret", "<script>");
    assertThat(result.message())
        .contains("&lt;script&gt;")
        .doesNotContain("stored-secret", "unicode-secret", "<script>");
    ArgumentCaptor<NotificationDeliveryLogInsertParam> captor = ArgumentCaptor.captor();
    verify(deliveryLogMapper).insert(captor.capture());
    assertThat(captor.getValue().errorMessage())
        .contains("password", "token=****")
        .doesNotContain("stored-secret", "unicode-secret");
  }

  @Test
  @DisplayName("回调类型渠道改走专用投递通道并携带密钥, 不经发送器注册表")
  void shouldRouteWebhookChannelThroughWebhookDispatcher() {
    when(channelMapper.selectByCode("tenant-a", "hook-1"))
        .thenReturn(channelRow(
            "hook-1", "WEBHOOK", "{\"url\":\"https://example.com/hook\",\"secret\":\"s3cr3t\"}"));
    when(webhookDispatcher.attemptDelivery(
            any(WebhookSubscriptionEntity.class),
            any(WebhookEventPayload.class),
            any(String.class)))
        .thenReturn(WebhookDeliveryResult.ok());

    ConsoleNotificationTestResultResponse result = service.testChannel("tenant-a", "hook-1");

    ArgumentCaptor<WebhookSubscriptionEntity> subCaptor = ArgumentCaptor.captor();
    verify(webhookDispatcher)
        .attemptDelivery(subCaptor.capture(), any(WebhookEventPayload.class), any(String.class));
    assertThat(subCaptor.getValue().getCallbackUrl()).isEqualTo("https://example.com/hook");
    assertThat(subCaptor.getValue().getSecret()).isEqualTo("s3cr3t");
    // WEBHOOK 不经 sender 注册表
    verify(senderRegistry, never()).resolve(any());
    assertThat(result.success()).isTrue();
    assertThat(result.status()).isEqualTo("OK");
  }

  @Test
  @DisplayName("渠道测试触发限流时返回限流错误, 且不发起投递也不写日志")
  void shouldRejectTestChannelWhenRateLimited() {
    // deliverTest 是可重复触发的 SSRF 放大点(赢 DNS-rebinding 竞态);超频时限流拦下,不再建连投递。
    when(channelMapper.selectByCode("tenant-a", "hook-1"))
        .thenReturn(channelRow("hook-1", "WEBHOOK", "{\"url\":\"https://example.com/hook\"}"));
    when(testChannelRateLimiter.tryAcquire(any(), ArgumentMatchers.anyInt())).thenReturn(false);

    assertThatThrownBy(() -> service.testChannel("tenant-a", "hook-1"))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> assertThat(((BizException) ex).getCode()).isEqualTo(ResultCode.RATE_LIMITED));

    // 拦在建连之前:没有走 webhook 投递,也没写投递日志
    verify(webhookDispatcher, never())
        .attemptDelivery(any(), any(WebhookEventPayload.class), any(String.class));
    verify(deliveryLogMapper, never()).insert(any());
  }
}

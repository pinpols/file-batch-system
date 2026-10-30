package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.param.NotificationDeliveryLogInsertParam;
import io.github.pinpols.batch.console.domain.notification.view.EnabledSubscriptionRuleRow;
import io.github.pinpols.batch.console.support.ratelimit.SlidingWindowRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
@DisplayName("订阅规则回调分发器: 规则匹配, 限流去重与投递日志落库")
class SubscriptionRuleWebhookDispatcherTest {

  @Mock
  private SubscriptionRuleMapper subscriptionRuleMapper;

  @Mock
  private WebhookDispatcher webhookDispatcher;

  @Mock
  private NotificationSenderRegistry senderRegistry;

  @Mock
  private SlidingWindowRateLimiter sendRateLimiter;

  @Mock
  private NotificationDeliveryLogMapper deliveryLogMapper;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private SubscriptionRuleWebhookDispatcher dispatcher;

  private SubscriptionRuleWebhookDispatcher newDispatcher() {
    // 限流默认放行;"超限丢弃"用例单独覆盖。
    lenient().when(sendRateLimiter.tryAcquire(any(), anyInt())).thenReturn(true);
    dispatcher = new SubscriptionRuleWebhookDispatcher(
        subscriptionRuleMapper,
        webhookDispatcher,
        senderRegistry,
        sendRateLimiter,
        meterRegistry,
        deliveryLogMapper);
    return dispatcher;
  }

  @AfterEach
  void tearDown() {
    if (dispatcher != null) {
      dispatcher.shutdown();
    }
  }

  @Test
  @DisplayName("渠道类型为回调的规则命中时, 用规则配置的地址与密钥合成投递目标")
  void shouldGeneratePendingDelivery_whenWebhookChannelRuleMatches() {
    // arrange: 一条 WEBHOOK 类型规则,config_json 带 url + secret
    EnabledSubscriptionRuleRow rule = enabledRule(
        null,
        "tenant-a",
        "ops-hook",
        "WEBHOOK",
        "JOB_SUCCESS,JOB_FAILED",
        null,
        "{\"url\":\"https://hook.example.com/in\",\"secret\":\"s3cr3t\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenReturn(List.of(rule));
    when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());

    // act
    newDispatcher()
        .dispatch("tenant-a", "JOB_SUCCESS", "stream-1", "cursor-1", "data", Instant.now());

    // assert: 异步投递最终调用 attemptDelivery,且喂入的合成订阅带 config 的 url/secret
    ArgumentCaptor<WebhookSubscriptionEntity> captor =
        ArgumentCaptor.forClass(WebhookSubscriptionEntity.class);
    verify(webhookDispatcher, timeout(2000)).attemptDelivery(captor.capture(), any(), any());
    WebhookSubscriptionEntity synthetic = captor.getValue();
    assertThat(synthetic.getCallbackUrl()).isEqualTo("https://hook.example.com/in");
    assertThat(synthetic.getSecret()).isEqualTo("s3cr3t");
  }

  @Test
  @DisplayName("去重限流查询异常时按放行处理并累加回退计数, 不误判为重复")
  void shouldFailOpenAndRecordCounter_whenDedupRateLimiterThrows() {
    // arrange: dedup 键(notify:dedup:*)查询 Redis 时抛 DataAccessException,其余键正常放行。
    // fail-open 语义:不误判为重复,继续投递;同时须留痕(log.warn + counter),而非静默吞掉。
    EnabledSubscriptionRuleRow rule = enabledRule(
        null,
        "tenant-a",
        "ops-hook",
        "WEBHOOK",
        "JOB_SUCCESS",
        null,
        "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenReturn(List.of(rule));
    when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());
    when(sendRateLimiter.tryAcquire(any(), anyInt())).thenReturn(true);
    when(sendRateLimiter.tryAcquire(
            argThat(key -> key != null && key.startsWith("notify:dedup:")), anyInt()))
        .thenThrow(new DataAccessResourceFailureException("redis down"));

    dispatcher = new SubscriptionRuleWebhookDispatcher(
        subscriptionRuleMapper,
        webhookDispatcher,
        senderRegistry,
        sendRateLimiter,
        meterRegistry,
        deliveryLogMapper);
    dispatcher.dispatch("tenant-a", "JOB_SUCCESS", "stream-1", "cursor-1", "data", Instant.now());

    verify(webhookDispatcher, timeout(2000)).attemptDelivery(any(), any(), any());
    assertThat(meterRegistry.find("notification.dedup.redis_fallback").counter())
        .isNotNull();
    assertThat(meterRegistry.find("notification.dedup.redis_fallback").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("渠道类型不是回调时记录跳过原因, 不调用回调投递")
  void shouldSkipAndLog_whenChannelTypeNotWebhook() throws InterruptedException {
    // arrange: EMAIL 渠道规则 —— 本轮不投递,必须 log 跳过而非静默丢弃
    CountDownLatch queried = new CountDownLatch(1);
    EnabledSubscriptionRuleRow rule = enabledRule(
        null, "tenant-a", "ops-mail", "EMAIL", "JOB_SUCCESS", null, "{\"to\":\"ops@example.com\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenAnswer(inv -> {
          queried.countDown();
          return List.of(rule);
        });

    // act
    newDispatcher().dispatch("tenant-a", "JOB_SUCCESS", "stream-1", "cursor-1", "data", null);

    // assert: 查询发生了(规则被处理过),但 EMAIL 渠道绝不调用 webhook 投递(被 log.warn 跳过)
    assertThat(queried.await(2, TimeUnit.SECONDS)).isTrue();
    verify(webhookDispatcher, never()).attemptDelivery(any(), any(), any());
  }

  @Test
  @DisplayName("事件载荷级别不在规则级别白名单内时不命中, 不投递")
  void shouldNotMatch_whenSeverityFilterMissesPayload() {
    // arrange: 规则要求 severity=CRITICAL,但事件 payload 是 WARN → 不命中,不投递
    EnabledSubscriptionRuleRow rule = enabledRule(
        null,
        "tenant-a",
        "ops-hook",
        "WEBHOOK",
        "JOB_FAILED",
        "CRITICAL",
        "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_FAILED"))
        .thenReturn(List.of(rule));

    // act
    newDispatcher()
        .dispatch(
            "tenant-a",
            "JOB_FAILED",
            "stream-1",
            "cursor-1",
            Map.of("severity", "WARN"),
            Instant.now());

    // assert
    verify(webhookDispatcher, never()).attemptDelivery(any(), any(), any());
  }

  @Test
  @DisplayName("事件载荷级别命中规则级别白名单时投递, 且忽略大小写")
  void shouldDeliver_whenSeverityFilterMatchesPayload() {
    // arrange: severity=CRITICAL 命中
    EnabledSubscriptionRuleRow rule = enabledRule(
        null,
        "tenant-a",
        "ops-hook",
        "WEBHOOK",
        "JOB_FAILED",
        "CRITICAL,FATAL",
        "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_FAILED"))
        .thenReturn(List.of(rule));
    when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());

    // act
    newDispatcher()
        .dispatch(
            "tenant-a",
            "JOB_FAILED",
            "stream-1",
            "cursor-1",
            Map.of("severity", "critical"),
            Instant.now());

    // assert
    verify(webhookDispatcher, timeout(2000)).attemptDelivery(any(), any(), any());
  }

  @Test
  @DisplayName("记录型事件载荷的 severity 命中订阅规则时投递")
  void shouldDeliver_whenRecordPayloadMatchesSeverityFilter() {
    EnabledSubscriptionRuleRow rule = enabledRule(
        null,
        "tenant-a",
        "ops-hook",
        "WEBHOOK",
        "ALERT_ESCALATED",
        "CRITICAL",
        "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "ALERT_ESCALATED"))
        .thenReturn(List.of(rule));
    when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());

    newDispatcher()
        .dispatch(
            "tenant-a",
            "ALERT_ESCALATED",
            "alerts",
            "cursor-1",
            new EscalationPayload("CRITICAL", 42L),
            Instant.now());

    verify(webhookDispatcher, timeout(2000)).attemptDelivery(any(), any(), any());
  }

  private record EscalationPayload(String severity, long alertId) {}

  @Test
  @DisplayName("不同租户同名渠道的限流与去重键各自带租户前缀, 互不共享窗口")
  void shouldTenantScopeRateLimitKeys_whenSameChannelCodeAcrossTenants() {
    // 两租户可合法配置同名 channelCode(唯一约束是 (tenant_id, channel_code))。
    // 限流/去重/目标 key 必须带 tenant 前缀,否则 A 打满后 B 同名渠道合法告警被静默压制(跨租串扰)。
    EnabledSubscriptionRuleRow ruleA = enabledRule(
        null,
        "tenant-a",
        "ops-hook",
        "WEBHOOK",
        "JOB_SUCCESS",
        null,
        "{\"url\":\"https://hook.example.com/in\"}");
    EnabledSubscriptionRuleRow ruleB = enabledRule(
        null,
        "tenant-b",
        "ops-hook",
        "WEBHOOK",
        "JOB_SUCCESS",
        null,
        "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenReturn(List.of(ruleA));
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-b", "JOB_SUCCESS"))
        .thenReturn(List.of(ruleB));
    lenient()
        .when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());

    newDispatcher().dispatch("tenant-a", "JOB_SUCCESS", "s", "c", "data", Instant.now());
    dispatcher.dispatch("tenant-b", "JOB_SUCCESS", "s", "c", "data", Instant.now());

    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(sendRateLimiter, timeout(2000).atLeastOnce()).tryAcquire(keys.capture(), anyInt());
    List<String> sendKeys =
        keys.getAllValues().stream().filter(k -> k.startsWith("notify:send:")).toList();
    // 两租户的 send 限流 key 都带各自 tenant 前缀,且彼此不同 → 不共享限流窗口。
    assertThat(sendKeys).contains("notify:send:tenant-a:ops-hook", "notify:send:tenant-b:ops-hook");
    assertThat(keys.getAllValues())
        .anyMatch(k -> k.startsWith("notify:dedup:tenant-a:"))
        .anyMatch(k -> k.startsWith("notify:dedup:tenant-b:"))
        .anyMatch(k -> k.startsWith("notify:dest:tenant-a:"))
        .anyMatch(k -> k.startsWith("notify:dest:tenant-b:"));
  }

  @Test
  @DisplayName("渠道发送限流拒绝时累加丢弃计数, 原因为渠道限流")
  void shouldIncrementDropCounter_whenChannelSendRateLimitExceeded() {
    EnabledSubscriptionRuleRow rule = enabledRule(
        null,
        "tenant-a",
        "ops-hook",
        "WEBHOOK",
        "JOB_SUCCESS",
        null,
        "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenReturn(List.of(rule));
    when(sendRateLimiter.tryAcquire(any(), anyInt())).thenReturn(false);
    dispatcher = new SubscriptionRuleWebhookDispatcher(
        subscriptionRuleMapper,
        webhookDispatcher,
        senderRegistry,
        sendRateLimiter,
        meterRegistry,
        deliveryLogMapper);

    dispatcher.dispatch("tenant-a", "JOB_SUCCESS", "s", "c", "data", Instant.now());

    assertThat(meterRegistry
            .find("notification.dropped")
            .tag("reason", "channel_ratelimit")
            .counter()
            .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("渠道类型无可用发送器时累加丢弃计数, 原因为缺少发送器")
  void shouldIncrementDropCounter_whenNoSenderForChannelType() throws InterruptedException {
    CountDownLatch queried = new CountDownLatch(1);
    EnabledSubscriptionRuleRow rule = enabledRule(
        null, "tenant-a", "ops-mail", "EMAIL", "JOB_SUCCESS", null, "{\"to\":\"ops@example.com\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenAnswer(inv -> {
          queried.countDown();
          return List.of(rule);
        });
    // senderRegistry.resolve("EMAIL") 默认返回 null → no_sender 丢弃路径。

    newDispatcher().dispatch("tenant-a", "JOB_SUCCESS", "s", "c", "data", Instant.now());

    assertThat(queried.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(meterRegistry
            .find("notification.dropped")
            .tag("reason", "no_sender")
            .counter()
            .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("渠道发送限流拒绝时直接丢弃, 不触达投递")
  void shouldDropDelivery_whenChannelSendRateLimitExceeded() {
    EnabledSubscriptionRuleRow rule = enabledRule(
        null,
        "tenant-a",
        "ops-hook",
        "WEBHOOK",
        "JOB_SUCCESS",
        null,
        "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenReturn(List.of(rule));
    // 限流器拒绝 → 直接丢弃,不触达投递。
    when(sendRateLimiter.tryAcquire(any(), anyInt())).thenReturn(false);
    dispatcher = new SubscriptionRuleWebhookDispatcher(
        subscriptionRuleMapper,
        webhookDispatcher,
        senderRegistry,
        sendRateLimiter,
        meterRegistry,
        deliveryLogMapper);

    dispatcher.dispatch("tenant-a", "JOB_SUCCESS", "s", "c", "data", Instant.now());

    verify(webhookDispatcher, never()).attemptDelivery(any(), any(), any());
  }

  @Test
  @DisplayName("告警升级事件类型与事件目录一致时, 命中规则并投递")
  void shouldMatchAndDispatch_escalationEventAlignedWithCatalog() {
    // Bug4:AlertEscalationNotifier 发 ALERT_ESCALATED(与事件目录一致),前端可配 subscription_rule
    // event_types=ALERT_ESCALATED 来订阅升级告警;此处验证该事件类型能匹配到规则并投递。
    EnabledSubscriptionRuleRow rule = enabledRule(
        1L,
        "tenant-a",
        "ops-hook",
        "WEBHOOK",
        "ALERT_ESCALATED",
        null,
        "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "ALERT_ESCALATED"))
        .thenReturn(List.of(rule));
    when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());

    newDispatcher()
        .dispatch(
            "tenant-a",
            "ALERT_ESCALATED",
            "alerts",
            "cursor-1",
            Map.of("alertId", 55, "severity", "HIGH"),
            Instant.now());

    verify(webhookDispatcher, timeout(2000)).attemptDelivery(any(), any(), any());
  }

  @Test
  @DisplayName("回调投递成功时写入成功交付日志, 记录规则, 渠道与事件标识")
  void shouldPersistSuccessDeliveryLog_whenWebhookDelivered() {
    EnabledSubscriptionRuleRow rule = enabledRule(
        42L,
        "tenant-a",
        "ops-hook",
        "WEBHOOK",
        "JOB_SUCCESS",
        null,
        "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenReturn(List.of(rule));
    when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());

    newDispatcher()
        .dispatch(
            "tenant-a", "JOB_SUCCESS", "stream-1", "cursor-1", Map.of("alertId", 7), Instant.now());

    ArgumentCaptor<NotificationDeliveryLogInsertParam> captor = ArgumentCaptor.captor();
    verify(deliveryLogMapper, timeout(2000)).insert(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("tenant-a");
    assertThat(captor.getValue().ruleId()).isEqualTo(42L);
    assertThat(captor.getValue().channelCode()).isEqualTo("ops-hook");
    assertThat(captor.getValue().eventType()).isEqualTo("JOB_SUCCESS");
    assertThat(captor.getValue().alertEventId()).isEqualTo(7L);
    assertThat(captor.getValue().deliveryStatus()).isEqualTo("SUCCESS");
    assertThat(captor.getValue().errorMessage()).isNull();
  }

  @Test
  @DisplayName("发送器返回失败时写入失败交付日志, 记录错误摘要")
  void shouldPersistFailedDeliveryLog_whenSenderExhausts() {
    EnabledSubscriptionRuleRow rule = enabledRule(
        9L,
        "tenant-a",
        "ops-wecom",
        "WECOM",
        "JOB_FAILED",
        null,
        "{\"url\":\"https://qyapi.weixin.qq.com/robot?key=x\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_FAILED"))
        .thenReturn(List.of(rule));
    NotificationSender sender = mock(NotificationSender.class);
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.failure(200, "wecom errcode=93000"));

    newDispatcher()
        .dispatch("tenant-a", "JOB_FAILED", "stream-1", "cursor-1", Map.of(), Instant.now());

    ArgumentCaptor<NotificationDeliveryLogInsertParam> captor = ArgumentCaptor.captor();
    verify(deliveryLogMapper, timeout(2000)).insert(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("tenant-a");
    assertThat(captor.getValue().ruleId()).isEqualTo(9L);
    assertThat(captor.getValue().channelCode()).isEqualTo("ops-wecom");
    assertThat(captor.getValue().eventType()).isEqualTo("JOB_FAILED");
    assertThat(captor.getValue().deliveryStatus()).isEqualTo("FAILED");
    assertThat(captor.getValue().errorMessage()).isEqualTo("wecom errcode=93000");
  }

  @Test
  @DisplayName("发送器失败摘要写入投递日志前遮罩凭据")
  void shouldMaskCredentialsBeforePersistingFailedDeliveryLog() {
    EnabledSubscriptionRuleRow rule = enabledRule(
        10L,
        "tenant-a",
        "ops-wecom",
        "WECOM",
        "JOB_FAILED",
        null,
        "{\"url\":\"https://qyapi.weixin.qq.com/robot?key=x\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_FAILED"))
        .thenReturn(List.of(rule));
    NotificationSender sender = mock(NotificationSender.class);
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any()))
        .thenReturn(WebhookDeliveryResult.failure(
            500, "password=stored-secret; ｔｏｋｅｎ=unicode-secret; retry later"));

    newDispatcher()
        .dispatch("tenant-a", "JOB_FAILED", "stream-1", "cursor-1", Map.of(), Instant.now());

    ArgumentCaptor<NotificationDeliveryLogInsertParam> captor = ArgumentCaptor.captor();
    verify(deliveryLogMapper, timeout(2000)).insert(captor.capture());
    assertThat(captor.getValue().errorMessage())
        .contains("password=****", "token=****", "retry later")
        .doesNotContain("stored-secret", "unicode-secret");
  }

  @Test
  @DisplayName("非回调发送器耗尽重试的告警日志不回显失败摘要凭据")
  void shouldMaskSenderFailureInExhaustionLog() {
    EnabledSubscriptionRuleRow rule = enabledRule(
        11L,
        "tenant-a",
        "ops-wecom",
        "WECOM",
        "JOB_FAILED",
        null,
        "{\"url\":\"https://qyapi.weixin.qq.com/robot?key=x\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_FAILED"))
        .thenReturn(List.of(rule));
    NotificationSender sender = mock(NotificationSender.class);
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any()))
        .thenReturn(
            WebhookDeliveryResult.failure(500, "password=stored-secret; ｔｏｋｅｎ=unicode-secret"));
    Logger logger = (Logger) LoggerFactory.getLogger(SubscriptionRuleWebhookDispatcher.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      newDispatcher()
          .dispatch("tenant-a", "JOB_FAILED", "stream-1", "cursor-1", Map.of(), Instant.now());
      verify(deliveryLogMapper, timeout(2000)).insert(any());

      assertThat(appender.list)
          .anySatisfy(event -> assertThat(event.getFormattedMessage())
              .contains("delivery exhausted", "password=****", "token=****"))
          .allSatisfy(event -> assertThat(event.getFormattedMessage())
              .doesNotContain("stored-secret", "unicode-secret"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("实时通知在租户候选中仅投递匹配的大小写与通配规则")
  void shouldDispatchOnlyMatchedRules_whenCandidatesIncludeUnrelatedEvents() {
    var lower = enabledRule(
        1L,
        "tenant-a",
        "lower",
        "WEBHOOK",
        "job_success",
        null,
        "{\"url\":\"https://lower.example.com\"}");
    var wildcard = enabledRule(
        2L,
        "tenant-a",
        "wildcard",
        "WEBHOOK",
        "*",
        null,
        "{\"url\":\"https://wildcard.example.com\"}");
    var unrelated = enabledRule(
        3L,
        "tenant-a",
        "other",
        "WEBHOOK",
        "JOB_FAILED",
        null,
        "{\"url\":\"https://other.example.com\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenReturn(List.of(lower, wildcard, unrelated));
    when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());
    newDispatcher()
        .dispatch("tenant-a", "JOB_SUCCESS", "stream", "cursor", Map.of(), Instant.now());
    ArgumentCaptor<WebhookSubscriptionEntity> captor =
        ArgumentCaptor.forClass(WebhookSubscriptionEntity.class);
    verify(webhookDispatcher, timeout(2000).times(2))
        .attemptDelivery(captor.capture(), any(), any());
    assertThat(captor.getAllValues())
        .extracting(WebhookSubscriptionEntity::getCallbackUrl)
        .containsExactlyInAnyOrder("https://lower.example.com", "https://wildcard.example.com");
  }

  @Test
  @DisplayName("自定义事件按 Unicode 大小写规则匹配并投递")
  void shouldDispatchUnicodeCustomEvent_whenCaseFoldChangesLength() {
    var rule = enabledRule(
        1L,
        "tenant-a",
        "custom",
        "WEBHOOK",
        "straße",
        null,
        "{\"url\":\"https://custom.example.com\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "STRASSE"))
        .thenReturn(List.of(rule));
    when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());
    newDispatcher().dispatch("tenant-a", "straße", "stream", "cursor", Map.of(), Instant.now());
    ArgumentCaptor<WebhookSubscriptionEntity> captor =
        ArgumentCaptor.forClass(WebhookSubscriptionEntity.class);
    verify(webhookDispatcher, timeout(2000)).attemptDelivery(captor.capture(), any(), any());
    assertThat(captor.getValue().getCallbackUrl()).isEqualTo("https://custom.example.com");
  }

  private static EnabledSubscriptionRuleRow enabledRule(
      Long id,
      String tenantId,
      String channelCode,
      String channelType,
      String eventTypes,
      String severityFilter,
      String configJson) {
    return new EnabledSubscriptionRuleRow(
        id, tenantId, null, channelCode, eventTypes, severityFilter, null, channelType, configJson);
  }
}

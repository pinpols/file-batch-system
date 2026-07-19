package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.support.ratelimit.SlidingWindowRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * P1-2(2026-06-15):把前端配置的「通知中心」规则({@code subscription_rule} + {@code notification_channel})接进事件投递链。
 *
 * <p>背景:在此之前运行时事件只走旧的 {@code webhook_subscription} 表({@link WebhookDispatcher} + {@link
 * ConsoleWebhookService#findEnabledSubscriptions}),前端在通知中心配置的规则保存成功但永不分发(死链路)。
 * 本类是接通后的「第二路」:与旧路并存,互不影响。
 *
 * <ul>
 *   <li><b>WEBHOOK 渠道</b>:从 {@code notification_channel.config_json} 取 {@code url}/{@code
 *       secret},构造一个内存版 {@link WebhookSubscriptionEntity},复用 {@link
 *       WebhookDispatcher#attemptDelivery} 的单次投递 + 同款 3 次 burst 退避重试 + DNS rebinding 防护 + HMAC
 *       签名机制。
 *   <li><b>非 WEBHOOK 渠道</b>(EMAIL/DINGTALK/WECOM/SLACK/SMS):走可插拔 {@link NotificationSender} SPI,由
 *       {@link NotificationSenderRegistry} 按 channelType 解析对应实现投递;无匹配 sender(如未接入的渠道)才 **显式 {@code
 *       log.warn} 跳过**,绝不静默丢弃。
 * </ul>
 *
 * <p><b>安全</b>:分发前按渠道 + 按目标(收件人指纹)双层限流 + 相同事件去重;第三方渠道 URL 经 {@code DnsResolveGuard} 防 SSRF。
 *
 * <p><b>去重</b>:若旧路({@code webhook_subscription})与本路({@code subscription_rule→WEBHOOK
 * channel})命中同一目标 URL, 两路都会各自投递,**不去重** —— 语义上是两个独立订阅(运维各自配置、各自启停),刻意不合并。
 *
 * <p><b>持久化投递审计</b>:本路 **不** 写 {@code webhook_delivery_log}({@code subscription_id} 是 {@code NOT
 * NULL REFERENCES webhook_subscription(id)} 外键,本路来源是 {@code subscription_rule},其 id 不在该表,
 * 会违反外键;也就不被 {@link WebhookDeliveryRelay} 持久化补偿覆盖)。改为向解耦的 {@code notification_delivery_log}
 * 落一条审计记录:每条真实投递(WEBHOOK 与第三方渠道)在 burst 重试收敛后,不论成功/失败都记一行(rule_id / channel_code / event_type /
 * alert_event_id / delivery_status / error_message / attempt)。落日志在投递线程池上执行(不在事件发布线程),
 * 且写库异常被吞掉只告警,绝不拖慢或中断投递(审计尽力而为)。
 */
@Slf4j
@Service
public class SubscriptionRuleWebhookDispatcher {

  private static final int MAX_ATTEMPTS = 3;
  private static final long BACKOFF_BASE_MILLIS = 250L;

  private static final String CHANNEL_TYPE_WEBHOOK = "WEBHOOK";

  /** config_json 中 WEBHOOK 渠道的字段约定(无正式 schema,见类注释决策点)。 */
  private static final String CONFIG_KEY_URL = "url";

  private static final String CONFIG_KEY_SECRET = "secret";

  private static final int QUEUE_CAPACITY = 1024;
  private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

  private final SubscriptionRuleMapper subscriptionRuleMapper;
  private final WebhookDispatcher webhookDispatcher;
  private final NotificationSenderRegistry senderRegistry;
  private final NotificationDeliveryLogMapper deliveryLogMapper;
  private final SubscriptionRuleDispatchPolicy dispatchPolicy;

  public SubscriptionRuleWebhookDispatcher(
      SubscriptionRuleMapper subscriptionRuleMapper,
      WebhookDispatcher webhookDispatcher,
      NotificationSenderRegistry senderRegistry,
      SlidingWindowRateLimiter sendRateLimiter,
      MeterRegistry meterRegistry,
      NotificationDeliveryLogMapper deliveryLogMapper) {
    this.subscriptionRuleMapper = subscriptionRuleMapper;
    this.webhookDispatcher = webhookDispatcher;
    this.senderRegistry = senderRegistry;
    this.deliveryLogMapper = deliveryLogMapper;
    this.dispatchPolicy = new SubscriptionRuleDispatchPolicy(sendRateLimiter, meterRegistry);
  }

  /**
   * 与 {@link WebhookDispatcher} 同款有界队列 + AbortPolicy:HTTP burst 投递不能跑在 Spring 事件发布线程(可能是请求/事务线程)上,
   * 否则单条 webhook 10s 超时 × 3 次重试会拖死调用方。队列满时直接丢弃(本路无持久化补偿,见类注释),仅告警。
   */
  private final ExecutorService executor =
      new ThreadPoolExecutor(
          2,
          2,
          60L,
          TimeUnit.SECONDS,
          new LinkedBlockingQueue<>(QUEUE_CAPACITY),
          runnable -> {
            Thread thread =
                new Thread(
                    runnable, "console-rule-webhook-dispatch-" + THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
          },
          new AbortPolicy());

  @PreDestroy
  public void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException ex) {
      SwallowedExceptionLogger.info(
          SubscriptionRuleWebhookDispatcher.class, "catch:InterruptedException", ex);
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /**
   * 对单个实时事件,按 subscription_rule 匹配并投递。与旧 {@link WebhookDispatcher#dispatchAsync} 在 listener 中并列调用。
   */
  public void dispatch(
      String tenantId,
      String eventType,
      String stream,
      String cursor,
      Object data,
      Instant emittedAt) {
    String normalizedEventType = dispatchPolicy.normalizeEventType(eventType);
    List<Map<String, Object>> rules =
        subscriptionRuleMapper.selectEnabledByEventType(tenantId, normalizedEventType);
    if (rules == null || rules.isEmpty()) {
      return;
    }
    Map<String, Object> dataMap = dispatchPolicy.asDataMap(data);
    WebhookEventPayload payload =
        new WebhookEventPayload(
            tenantId,
            normalizedEventType,
            stream,
            cursor,
            emittedAt == null ? BatchDateTimeSupport.utcNow() : emittedAt,
            data);
    String payloadJson = JsonUtils.toJson(payload);

    for (Map<String, Object> rule : rules) {
      dispatchRule(tenantId, rule, normalizedEventType, dataMap, payload, payloadJson);
    }
  }

  private void dispatchRule(
      String tenantId,
      Map<String, Object> rule,
      String eventType,
      Map<String, Object> dataMap,
      WebhookEventPayload payload,
      String payloadJson) {
    String channelCode = str(rule, "channel_code");
    String channelType = str(rule, "channel_type");

    // selectEnabledByEventType 用 LIKE '%type%' 粗筛,这里做精确的「逗号分隔成员」复核,避免 JOB 命中 JOB_FAILED 之类子串误配。
    if (!dispatchPolicy.matches(rule, eventType, dataMap)) {
      return;
    }

    // 单渠道每分钟投递上限,超额丢弃 + 告警(webhook 与第三方渠道同样适用)。
    if (!dispatchPolicy.withinSendRateLimit(tenantId, channelCode, channelType, eventType)) {
      return;
    }
    // 去重:相同(渠道+事件+内容)在滑窗内只发一次,挡风暴重复刷屏。
    if (dispatchPolicy.isDuplicateWithinWindow(tenantId, channelCode, eventType, payloadJson)) {
      return;
    }
    // 目标级限流:同一收件人(url/to/phoneNumbers)跨规则/渠道的更严上限。
    if (!dispatchPolicy.withinDestinationRateLimit(
        tenantId, rule, channelCode, channelType, eventType)) {
      return;
    }

    DeliveryLogContext logContext =
        deliveryLogContext(rule, channelCode, eventType, dataMap, payloadJson);

    if (!CHANNEL_TYPE_WEBHOOK.equalsIgnoreCase(channelType)) {
      // 非 WEBHOOK 渠道走可插拔 NotificationSender(DINGTALK/WECOM/SLACK/EMAIL/...);无对应 sender 才显式告警跳过。
      NotificationSender sender = senderRegistry.resolve(channelType);
      if (sender == null) {
        dispatchPolicy.recordDrop("no_sender");
        log.warn(
            "subscription_rule matched but no sender for channel type; skipping:"
                + " channelCode={}, channelType={}, eventType={}",
            channelCode,
            channelType,
            eventType);
        return;
      }
      NotificationMessage message =
          new NotificationMessage(
              str(rule, "tenant_id"),
              channelCode,
              channelType,
              str(rule, "config_json"),
              payload,
              payloadJson);
      try {
        executor.submit(
            () -> deliverViaSenderWithBurstRetry(sender, message, eventType, logContext));
      } catch (RejectedExecutionException ex) {
        dispatchPolicy.recordDrop("queue_full");
        log.warn(
            "subscription_rule {} dispatch rejected (queue full); dropped: channelCode={},"
                + " eventType={}",
            channelType,
            channelCode,
            eventType);
      }
      return;
    }

    WebhookSubscriptionEntity synthetic = toSyntheticSubscription(channelCode, rule);
    if (synthetic == null) {
      return;
    }
    try {
      executor.submit(
          () -> deliverWithBurstRetry(synthetic, payload, payloadJson, channelCode, logContext));
    } catch (RejectedExecutionException ex) {
      // 本路无持久化补偿(见类注释),队列满时丢弃,显式告警 + drop counter 让运维可见。
      dispatchPolicy.recordDrop("queue_full");
      log.warn(
          "subscription_rule WEBHOOK dispatch rejected (queue full); dropped: channelCode={},"
              + " eventType={}",
          channelCode,
          eventType);
    }
  }

  /**
   * 从 channel config_json 构造内存版订阅。复用 {@link WebhookSubscriptionEntity} 仅为喂给 {@link
   * WebhookDispatcher#attemptDelivery},不写入数据库。
   */
  private WebhookSubscriptionEntity toSyntheticSubscription(
      String channelCode, Map<String, Object> rule) {
    Map<String, Object> config = dispatchPolicy.parseConfig(str(rule, "config_json"));
    String url = str(config, CONFIG_KEY_URL);
    if (url == null || url.isBlank()) {
      log.warn(
          "subscription_rule WEBHOOK channel missing config url; skipping: channelCode={}",
          channelCode);
      return null;
    }
    WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity();
    entity.setTenantId(str(rule, "tenant_id"));
    entity.setName(channelCode);
    entity.setCallbackUrl(url);
    entity.setSecret(str(config, CONFIG_KEY_SECRET));
    entity.setEnabled(Boolean.TRUE);
    return entity;
  }

  /**
   * 同 {@link WebhookDispatcher#deliverPersisted} 的 burst 重试;收敛后向 notification_delivery_log 落审计行。
   */
  private void deliverWithBurstRetry(
      WebhookSubscriptionEntity subscription,
      WebhookEventPayload payload,
      String payloadJson,
      String channelCode,
      DeliveryLogContext logContext) {
    long backoffMillis = BACKOFF_BASE_MILLIS;
    WebhookDeliveryResult result = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      result = webhookDispatcher.attemptDelivery(subscription, payload, payloadJson);
      if (result.success()) {
        if (attempt > 1) {
          log.info(
              "subscription_rule WEBHOOK delivered after retry: channelCode={}, attempt={},"
                  + " httpStatus={}",
              channelCode,
              attempt,
              result.httpStatus());
        }
        persistDeliveryLog(logContext, "SUCCESS", null, attempt);
        return;
      }
      if (attempt < MAX_ATTEMPTS) {
        sleep(backoffMillis);
        backoffMillis *= 2;
      } else {
        log.warn(
            "subscription_rule WEBHOOK delivery exhausted: channelCode={}, attempts={},"
                + " httpStatus={}, error={}",
            channelCode,
            MAX_ATTEMPTS,
            result.httpStatus(),
            result.errorSummary());
      }
    }
    persistDeliveryLog(
        logContext, "FAILED", result == null ? null : result.errorSummary(), MAX_ATTEMPTS);
  }

  /** 非 WEBHOOK 渠道经 {@link NotificationSender} 的 burst 重试投递;收敛后落 notification_delivery_log 审计行。 */
  private void deliverViaSenderWithBurstRetry(
      NotificationSender sender,
      NotificationMessage message,
      String eventType,
      DeliveryLogContext logContext) {
    long backoffMillis = BACKOFF_BASE_MILLIS;
    WebhookDeliveryResult result = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      result = sender.send(message);
      if (result.success()) {
        if (attempt > 1) {
          log.info(
              "subscription_rule {} delivered after retry: channelCode={}, attempt={},"
                  + " eventType={}",
              message.channelType(),
              message.channelCode(),
              attempt,
              eventType);
        }
        persistDeliveryLog(logContext, "SUCCESS", null, attempt);
        return;
      }
      if (attempt < MAX_ATTEMPTS) {
        sleep(backoffMillis);
        backoffMillis *= 2;
      } else {
        log.warn(
            "subscription_rule {} delivery exhausted: channelCode={}, attempts={}, httpStatus={},"
                + " error={}",
            message.channelType(),
            message.channelCode(),
            MAX_ATTEMPTS,
            result.httpStatus(),
            result.errorSummary());
      }
    }
    persistDeliveryLog(
        logContext, "FAILED", result == null ? null : result.errorSummary(), MAX_ATTEMPTS);
  }

  /**
   * 向 {@code notification_delivery_log} 落一条投递审计行。写库异常只告警、绝不外抛(审计尽力而为,不能拖累/中断投递)。
   * 运行在投递线程池上,不占事件发布线程。
   */
  private void persistDeliveryLog(
      DeliveryLogContext ctx, String deliveryStatus, String errorMessage, int attempt) {
    if (ctx == null) {
      return;
    }
    try {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("tenantId", ctx.tenantId());
      row.put("ruleId", ctx.ruleId());
      row.put("channelCode", ctx.channelCode());
      row.put("eventType", ctx.eventType());
      row.put("alertEventId", ctx.alertEventId());
      row.put("payloadJson", ctx.payloadJson());
      row.put("deliveryStatus", deliveryStatus);
      row.put("errorMessage", truncate(errorMessage));
      row.put("attempt", attempt);
      deliveryLogMapper.insert(row);
    } catch (RuntimeException ex) {
      log.warn(
          "notification_delivery_log persist failed (audit only, delivery unaffected):"
              + " channelCode={}, eventType={}, status={}, cause={}",
          ctx.channelCode(),
          ctx.eventType(),
          deliveryStatus,
          ex.getMessage());
    }
  }

  private DeliveryLogContext deliveryLogContext(
      Map<String, Object> rule,
      String channelCode,
      String eventType,
      Map<String, Object> dataMap,
      String payloadJson) {
    return new DeliveryLogContext(
        str(rule, "tenant_id"),
        toLong(rule.get("id")),
        channelCode,
        eventType,
        extractAlertEventId(dataMap),
        payloadJson);
  }

  private static Long extractAlertEventId(Map<String, Object> dataMap) {
    for (String key : List.of("alertEventId", "alertId")) {
      Long id = toLong(dataMap.get(key));
      if (id != null) {
        return id;
      }
    }
    return null;
  }

  private static Long toLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String s && !s.isBlank()) {
      try {
        return Long.parseLong(s.trim());
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    // error_message 列 VARCHAR(1024);保守截断。
    return value.length() > 1024 ? value.substring(0, 1024) : value;
  }

  /** 一次真实投递落审计日志所需的最小上下文(投递前从规则/事件抽取,喂给投递线程)。 */
  private record DeliveryLogContext(
      String tenantId,
      Long ruleId,
      String channelCode,
      String eventType,
      Long alertEventId,
      String payloadJson) {}

  private static String str(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? null : value.toString();
  }

  private void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      SwallowedExceptionLogger.info(
          SubscriptionRuleWebhookDispatcher.class, "catch:InterruptedException", ex);
      Thread.currentThread().interrupt();
    }
  }
}

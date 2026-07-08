package io.github.pinpols.batch.console.domain.notification.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.support.ratelimit.SlidingWindowRateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
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
 *   <li><b>非 WEBHOOK 渠道</b>(EMAIL/DINGTALK/WECHAT/SLACK/SMS):走可插拔 {@link NotificationSender} SPI,由
 *       {@link NotificationSenderRegistry} 按 channelType 解析对应实现投递;无匹配 sender(如未接入的渠道)才 **显式 {@code
 *       log.warn} 跳过**,绝不静默丢弃。
 * </ul>
 *
 * <p><b>安全</b>:分发前按渠道 + 按目标(收件人指纹)双层限流 + 相同事件去重;第三方渠道 URL 经 {@code DnsResolveGuard} 防 SSRF。
 *
 * <p><b>去重</b>:若旧路({@code webhook_subscription})与本路({@code subscription_rule→WEBHOOK
 * channel})命中同一目标 URL, 两路都会各自投递,**不去重** —— 语义上是两个独立订阅(运维各自配置、各自启停),刻意不合并。
 *
 * <p><b>持久化 delivery-log 的取舍</b>:{@code webhook_delivery_log.subscription_id} 是 {@code NOT NULL
 * REFERENCES webhook_subscription(id)} 外键,而本路的来源是 {@code subscription_rule},其 id 不在 {@code
 * webhook_subscription} 表里,无法写该日志表(会违反外键)。因此本路 **只复用投递 + burst 重试**,不落 {@code
 * webhook_delivery_log}(也就不被 {@link WebhookDeliveryRelay} 持久化补偿覆盖);投递结果记应用日志。是否给本路补一张
 * 解耦的投递日志表是后续决策点,见类注释末尾。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionRuleWebhookDispatcher {

  private static final int MAX_ATTEMPTS = 3;
  private static final long BACKOFF_BASE_MILLIS = 250L;

  private static final String CHANNEL_TYPE_WEBHOOK = "WEBHOOK";

  /** config_json 中 WEBHOOK 渠道的字段约定(无正式 schema,见类注释决策点)。 */
  private static final String CONFIG_KEY_URL = "url";

  private static final String CONFIG_KEY_SECRET = "secret";

  /** payload data(若为 Map)中用于匹配 severity_filter / job_code_filter 的字段。 */
  private static final List<String> SEVERITY_KEYS = List.of("severity");

  private static final List<String> JOB_CODE_KEYS = List.of("jobCode", "job_code");

  private static final int QUEUE_CAPACITY = 1024;
  private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

  /** 单渠道每分钟最大投递条数(滑窗)。超额丢弃 + 告警,限制事件风暴或恶意触发对收件人的影响(尤其 SMS 计费、邮件批量投递)。 */
  private static final int MAX_SENDS_PER_CHANNEL_PER_MINUTE = 120;

  /** 目标级限流:同一投递目标(url/to/phoneNumbers 指纹)每分钟上限,比按渠道更严,限制多规则/多渠道同时命中同一收件人。 */
  private static final int MAX_SENDS_PER_DESTINATION_PER_MINUTE = 60;

  /** 去重:相同(渠道+事件+内容)在滑窗内只发一次(limit=1),挡告警风暴重复刷屏。 */
  private static final int DEDUP_WINDOW_LIMIT = 1;

  private final SubscriptionRuleMapper subscriptionRuleMapper;
  private final WebhookDispatcher webhookDispatcher;
  private final NotificationSenderRegistry senderRegistry;
  private final SlidingWindowRateLimiter sendRateLimiter;
  private final MeterRegistry meterRegistry;

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
    String normalizedEventType = normalizeEventType(eventType);
    List<Map<String, Object>> rules =
        subscriptionRuleMapper.selectEnabledByEventType(tenantId, normalizedEventType);
    if (rules == null || rules.isEmpty()) {
      return;
    }
    Map<String, Object> dataMap = asDataMap(data);
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
      dispatchRule(rule, normalizedEventType, dataMap, payload, payloadJson);
    }
  }

  private void dispatchRule(
      Map<String, Object> rule,
      String eventType,
      Map<String, Object> dataMap,
      WebhookEventPayload payload,
      String payloadJson) {
    String channelCode = str(rule, "channel_code");
    String channelType = str(rule, "channel_type");

    // selectEnabledByEventType 用 LIKE '%type%' 粗筛,这里做精确的「逗号分隔成员」复核,避免 JOB 命中 JOB_FAILED 之类子串误配。
    if (!eventTypeMatches(str(rule, "event_types"), eventType)) {
      return;
    }
    if (!filterMatches(str(rule, "severity_filter"), dataMap, SEVERITY_KEYS)) {
      return;
    }
    if (!filterMatches(str(rule, "job_code_filter"), dataMap, JOB_CODE_KEYS)) {
      return;
    }

    // 单渠道每分钟投递上限,超额丢弃 + 告警(webhook 与第三方渠道同样适用)。
    if (!withinSendRateLimit(channelCode, channelType, eventType)) {
      return;
    }
    // 去重:相同(渠道+事件+内容)在滑窗内只发一次,挡风暴重复刷屏。
    if (isDuplicateWithinWindow(channelCode, eventType, payloadJson)) {
      return;
    }
    // 目标级限流:同一收件人(url/to/phoneNumbers)跨规则/渠道的更严上限。
    if (!withinDestinationRateLimit(rule, channelCode, channelType, eventType)) {
      return;
    }

    if (!CHANNEL_TYPE_WEBHOOK.equalsIgnoreCase(channelType)) {
      // 非 WEBHOOK 渠道走可插拔 NotificationSender(DINGTALK/WECHAT/SLACK/EMAIL/...);无对应 sender 才显式告警跳过。
      NotificationSender sender = senderRegistry.resolve(channelType);
      if (sender == null) {
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
        executor.submit(() -> deliverViaSenderWithBurstRetry(sender, message, eventType));
      } catch (RejectedExecutionException ex) {
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
      executor.submit(() -> deliverWithBurstRetry(synthetic, payload, payloadJson, channelCode));
    } catch (RejectedExecutionException ex) {
      // 本路无持久化补偿(见类注释),队列满时丢弃,显式告警让运维可见。
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
    Map<String, Object> config = parseConfig(str(rule, "config_json"));
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

  /** 同 {@link WebhookDispatcher#deliverPersisted} 的 burst 重试,但不写 webhook_delivery_log(见类注释)。 */
  private void deliverWithBurstRetry(
      WebhookSubscriptionEntity subscription,
      WebhookEventPayload payload,
      String payloadJson,
      String channelCode) {
    long backoffMillis = BACKOFF_BASE_MILLIS;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      WebhookDeliveryResult result =
          webhookDispatcher.attemptDelivery(subscription, payload, payloadJson);
      if (result.success()) {
        if (attempt > 1) {
          log.info(
              "subscription_rule WEBHOOK delivered after retry: channelCode={}, attempt={},"
                  + " httpStatus={}",
              channelCode,
              attempt,
              result.httpStatus());
        }
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
  }

  /** 单渠道限流:每分钟投递上限(滑窗)。超额 → false(丢弃 + 告警)。 Redis 不可达时 fail-open(放行 + 告警),避免把限流可用性故障升级为"漏发真实告警"。 */
  private boolean withinSendRateLimit(String channelCode, String channelType, String eventType) {
    try {
      boolean allowed =
          sendRateLimiter.tryAcquire(
              "notify:send:" + channelCode, MAX_SENDS_PER_CHANNEL_PER_MINUTE);
      if (!allowed) {
        log.warn(
            "notification send rate limit exceeded; dropping to prevent flooding: channelCode={},"
                + " channelType={}, eventType={}, limitPerMin={}",
            channelCode,
            channelType,
            eventType,
            MAX_SENDS_PER_CHANNEL_PER_MINUTE);
      }
      return allowed;
    } catch (DataAccessException ex) {
      log.warn(
          "notification send rate limiter unavailable — fail-open: channelCode={}, cause={}",
          channelCode,
          ex.getMessage());
      return true;
    }
  }

  /**
   * 去重:相同 (channelCode + eventType + 内容指纹) 在滑窗内出现第二次即视为重复并抑制。 复用滑窗限流器(limit=1):窗内首条放行、其余返回
   * false。Redis 不可达 fail-open(不误抑制真实告警)。
   */
  private boolean isDuplicateWithinWindow(
      String channelCode, String eventType, String payloadJson) {
    String fingerprint = Integer.toHexString((payloadJson == null ? "" : payloadJson).hashCode());
    String key = "notify:dedup:" + channelCode + ":" + eventType + ":" + fingerprint;
    try {
      boolean firstInWindow = sendRateLimiter.tryAcquire(key, DEDUP_WINDOW_LIMIT);
      if (!firstInWindow) {
        log.info(
            "notification suppressed as duplicate within window: channelCode={}, eventType={}",
            channelCode,
            eventType);
      }
      return !firstInWindow;
    } catch (DataAccessException ex) {
      log.warn(
          "notification dedup rate limiter unavailable — fail-open (not deduped):"
              + " channelCode={}, eventType={}, cause={}",
          channelCode,
          eventType,
          ex.getMessage());
      Counter.builder("notification.dedup.redis_fallback").register(meterRegistry).increment();
      return false;
    }
  }

  /**
   * 目标级限流:对投递目标指纹(url/to/phoneNumbers)限流,比按渠道更严,避免多规则/多渠道同时压垮同一收件人。 无法识别目标时跳过本层(按渠道限流已覆盖)。Redis 不可达
   * fail-open。
   */
  private boolean withinDestinationRateLimit(
      Map<String, Object> rule, String channelCode, String channelType, String eventType) {
    Map<String, Object> config = parseConfig(str(rule, "config_json"));
    String dest = firstNonBlank(str(config, "url"), str(config, "to"), str(config, "phoneNumbers"));
    if (dest == null) {
      return true;
    }
    try {
      boolean allowed =
          sendRateLimiter.tryAcquire(
              "notify:dest:" + Integer.toHexString(dest.hashCode()),
              MAX_SENDS_PER_DESTINATION_PER_MINUTE);
      if (!allowed) {
        log.warn(
            "notification destination rate limit exceeded; dropping: channelCode={},"
                + " channelType={}, eventType={}, limitPerMin={}",
            channelCode,
            channelType,
            eventType,
            MAX_SENDS_PER_DESTINATION_PER_MINUTE);
      }
      return allowed;
    } catch (DataAccessException ex) {
      log.warn(
          "notification destination rate limiter unavailable — fail-open: channelCode={},"
              + " channelType={}, eventType={}, cause={}",
          channelCode,
          channelType,
          eventType,
          ex.getMessage());
      Counter.builder("notification.ratelimit.redis_fallback").register(meterRegistry).increment();
      return true;
    }
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  /** 非 WEBHOOK 渠道经 {@link NotificationSender} 的 burst 重试投递(同 webhook 路径,本路无持久化补偿)。 */
  private void deliverViaSenderWithBurstRetry(
      NotificationSender sender, NotificationMessage message, String eventType) {
    long backoffMillis = BACKOFF_BASE_MILLIS;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      WebhookDeliveryResult result = sender.send(message);
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
  }

  private boolean eventTypeMatches(String configuredEventTypes, String eventType) {
    if (configuredEventTypes == null || configuredEventTypes.isBlank()) {
      return false;
    }
    if ("*".equals(configuredEventTypes.trim())) {
      return true;
    }
    return splitUpper(configuredEventTypes).contains(eventType);
  }

  /**
   * severity / job_code 过滤:filter 为空 → match-all;非空 → 取 payload data(若为 Map)对应字段,要求其值落在 filter
   * 的逗号分隔集合内。 data 非 Map 或缺字段时,有 filter 的规则视为不命中(宁可漏发不要错发)。
   */
  private boolean filterMatches(String filter, Map<String, Object> dataMap, List<String> dataKeys) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    Collection<String> allowed = splitUpper(filter);
    if (allowed.isEmpty()) {
      return true;
    }
    String actual = firstNonNull(dataMap, dataKeys);
    if (actual == null) {
      return false;
    }
    return allowed.contains(actual.toUpperCase(Locale.ROOT));
  }

  private String firstNonNull(Map<String, Object> dataMap, List<String> keys) {
    for (String key : keys) {
      Object value = dataMap.get(key);
      if (value != null) {
        return value.toString();
      }
    }
    return null;
  }

  private List<String> splitUpper(String csv) {
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .map(value -> value.toUpperCase(Locale.ROOT))
        .toList();
  }

  private Map<String, Object> parseConfig(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> parsed =
          JsonUtils.fromJson(configJson, new TypeReference<Map<String, Object>>() {});
      return parsed == null ? Map.of() : parsed;
    } catch (RuntimeException ex) {
      SwallowedExceptionLogger.info(
          SubscriptionRuleWebhookDispatcher.class, "catch:config_json parse", ex);
      return Map.of();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> asDataMap(Object data) {
    if (data instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return Map.of();
  }

  private String normalizeEventType(String eventType) {
    return eventType == null ? "UNKNOWN" : eventType.trim().toUpperCase(Locale.ROOT);
  }

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

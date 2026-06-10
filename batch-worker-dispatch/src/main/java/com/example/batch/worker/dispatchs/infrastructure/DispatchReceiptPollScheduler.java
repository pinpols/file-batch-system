package com.example.batch.worker.dispatchs.infrastructure;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.security.DnsResolveGuard;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.dispatchs.config.DispatchReceiptPollProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 异步回执轮询器：定时从 {@code file_dispatch_record} 中取出 {@code receipt_status=PENDING} 的记录， 向渠道配置的 {@code
 * receipt_poll_url} 发 GET 请求查询投递确认状态。
 *
 * <p>若响应中 {@code acknowledged=true} / {@code status=ACKED} / {@code receipt_status=SUCCESS}，
 * 则标记该记录为 {@code ACKED} 并将 {@code file_record.file_status} 推进为 {@code DISPATCHED}； 状态冲突（CAS 返回 0
 * 行）记录 warn 跳过——另一节点已处理。
 *
 * <p>ShedLock 防止多节点重复轮询同一批记录；Micrometer 指标 {@code batch.dispatch.receipt.poll.failures/successes}
 * 用于告警监控。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchReceiptPollScheduler {

  // L-4：receipt 响应体上限（1MB）。正常 ACK payload 在 KB 级；超过说明上游异常。
  private static final int MAX_RECEIPT_BODY_BYTES = 1_048_576;

  private final DispatchReceiptPollProperties properties;
  private final FileDispatchRepository fileDispatchRepository;
  private final ObjectMapper objectMapper;
  private final PlatformFileRuntimeRepository runtimeRepository;
  private final MeterRegistry meterRegistry;
  private final BatchSecurityProperties securityProperties;
  // R2-P0-4：OkHttp 默认 0 超时（永不超时）。stale 远端 → @SchedulerLock 持锁、调度线程被阻塞 → 整 receipt 轮询死锁。
  // 显式 connect/read/write/call 超时；进程 shutdown 时显式回收 dispatcher / connectionPool（默认 keepalive 60s
  // 非守护线程）。
  // SSRF: receipt_poll_url 是渠道配置的自由文本(运营/租户可写),与本模块其他 HTTP 出口一致,
  // 必须经 DnsResolveGuard 做 resolve-then-connect IP 校验,挡内网/云 metadata(169.254.169.254)。
  // 因 guardedDns 依赖注入的 securityProperties,httpClient 改在 @PostConstruct 构造(字段初始化器拿不到)。
  private OkHttpClient httpClient;
  private final AtomicBoolean stopping = new AtomicBoolean(false);
  private final AtomicLong pollFailures = new AtomicLong();
  private final AtomicLong pollSuccesses = new AtomicLong();

  @PostConstruct
  void initializeMeters() {
    this.httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(15))
            .writeTimeout(Duration.ofSeconds(5))
            .callTimeout(Duration.ofSeconds(30))
            .dns(guardedDns())
            .build();
    meterRegistry.gauge("batch.dispatch.receipt.poll.failures", pollFailures);
    meterRegistry.gauge("batch.dispatch.receipt.poll.successes", pollSuccesses);
  }

  /**
   * resolve-then-connect 防 SSRF：在 OkHttp 真正建连前回调,解析主机名并对每个 IP 做 {@link DnsResolveGuard}
   * 黑名单校验(loopback/link-local/private/metadata),命中即抛 {@link UnknownHostException}
   * 阻止建连。bypass-mode(非 prod 联调)直接走系统解析。与 {@code HttpDispatchChannelAdapter} 同款。
   */
  private Dns guardedDns() {
    return new Dns() {
      @Override
      public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (securityProperties.isBypassMode()) {
          return Dns.SYSTEM.lookup(hostname);
        }
        return List.of(DnsResolveGuard.resolveAndValidate(hostname));
      }
    };
  }

  /**
   * R2-P0-4：进程退出前回收 OkHttp 内部资源——dispatcher 的 ExecutorService 是非守护线程，未 shutdown 会让 JVM 等 ~60s（idle
   * keepalive）才真正退出；connectionPool.evictAll 关闭所有空闲连接。
   */
  @EventListener(ContextClosedEvent.class)
  void stopOnContextClosed(ContextClosedEvent event) {
    stopHttpClient("context-closed");
  }

  @PreDestroy
  void shutdown() {
    stopHttpClient("pre-destroy");
  }

  private void stopHttpClient(String source) {
    if (!stopping.compareAndSet(false, true)) {
      return;
    }
    if (httpClient == null) {
      return;
    }
    try {
      httpClient.dispatcher().cancelAll();
      httpClient.dispatcher().executorService().shutdown();
      httpClient.connectionPool().evictAll();
      log.info("dispatch receipt poll http client stopped: source={}", source);
    } catch (RuntimeException ex) {
      log.warn("OkHttp shutdown cleanup error: {}", ex.getMessage(), ex);
    }
  }

  @Scheduled(fixedDelayString = "${batch.worker.dispatch.receipt-poll.interval-millis:60000}")
  @SchedulerLock(name = "dispatch_receipt_poll", lockAtMostFor = "PT3M", lockAtLeastFor = "PT30S")
  public void poll() {
    if (!properties.isEnabled() || stopping.get()) {
      return;
    }
    List<Map<String, Object>> rows;
    try {
      rows =
          fileDispatchRepository.listPendingReceiptPolls(
              properties.getBatchSize(), properties.getPendingMaxAgeSeconds());
    } catch (RuntimeException exception) {
      if (stopping.get()) {
        log.info("dispatch receipt poll skipped during shutdown: error={}", exception.getMessage());
        return;
      }
      throw exception;
    }
    for (Map<String, Object> row : rows) {
      if (stopping.get()) {
        return;
      }
      try {
        pollOne(row);
      } catch (Exception exception) {
        if (stopping.get()) {
          log.info(
              "dispatch receipt poll interrupted during shutdown: error={}",
              exception.getMessage());
          return;
        }
        pollFailures.incrementAndGet();
        if (isTransientConnectivityFailure(exception)) {
          // 已知瞬时连通性异常 (上游不可达 / 超时 / DNS) — 仅 message,不打 stack。
          // 否则每分钟同一个 PENDING 受体会刷一次 ~66 行 stack trace 把日志淹掉。
          log.warn(
              "dispatch receipt poll failed (transient): error={}, row={}",
              exception.getMessage(),
              row);
        } else {
          log.warn(
              "dispatch receipt poll failed: error={}, row={}",
              exception.getMessage(),
              row,
              exception);
        }
      }
    }
  }

  static boolean isTransientConnectivityFailure(Throwable t) {
    for (Throwable cur = t; cur != null; cur = cur.getCause()) {
      if (cur instanceof ConnectException
          || cur instanceof SocketTimeoutException
          || cur instanceof UnknownHostException) {
        return true;
      }
    }
    return false;
  }

  private void pollOne(Map<String, Object> row) throws Exception {
    String tenantId = String.valueOf(row.get("tenant_id"));
    Long fileId = toLong(row.get("file_id"));
    String channelCode = String.valueOf(row.get("channel_code"));
    String externalRequestId =
        row.get("external_request_id") == null
            ? null
            : String.valueOf(row.get("external_request_id"));
    if (fileId == null || !Texts.hasText(channelCode) || !Texts.hasText(externalRequestId)) {
      return;
    }
    Map<String, Object> channelRow = fileDispatchRepository.loadChannel(tenantId, channelCode);
    if (channelRow.isEmpty()) {
      return;
    }
    Map<String, Object> channel = ChannelConfigMerge.merge(channelRow, objectMapper);
    String pollUrl =
        channel.get("receipt_poll_url") == null
            ? null
            : String.valueOf(channel.get("receipt_poll_url"));
    if (!Texts.hasText(pollUrl)) {
      return;
    }
    String sep = pollUrl.contains("?") ? "&" : "?";
    String url =
        pollUrl
            + sep
            + "externalRequestId="
            + URLEncoder.encode(externalRequestId, StandardCharsets.UTF_8);
    Request request = new Request.Builder().url(url).get().build();
    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        // P2:之前静默吞,排障时只能看到 metric 计数无原因。补 warn + 失败计数,
        // 上游 404/5xx 立即能在 dashboard 关联到失败率上扬。
        log.warn(
            "receipt poll got non-2xx: status={}, externalRequestId={}, channelCode={}",
            response.code(),
            externalRequestId,
            channelCode);
        pollFailures.incrementAndGet();
        return;
      }
      // L-4：之前 response.body().string() 把整个响应一次性读进堆内存，异常响应体（如
      // 上游误返回大量调试信息）可能打爆 JVM。改流式读取 + maxReceiptBodyBytes 硬上限
      JsonNode root;
      try (InputStream bodyStream = response.body().byteStream()) {
        byte[] limited = bodyStream.readNBytes(MAX_RECEIPT_BODY_BYTES);
        if (limited.length == MAX_RECEIPT_BODY_BYTES && bodyStream.read() != -1) {
          log.warn(
              "receipt poll response body exceeds {} bytes — truncating: externalRequestId={}",
              MAX_RECEIPT_BODY_BYTES,
              externalRequestId);
        }
        root = objectMapper.readTree(limited);
      }
      boolean ack =
          root.path("acknowledged").asBoolean(false)
              || "ACKED".equalsIgnoreCase(root.path("status").asText())
              || "SUCCESS".equalsIgnoreCase(root.path("receipt_status").asText());
      if (!ack) {
        return;
      }
      String receiptCode = root.path("receiptCode").asText(null);
      if (!Texts.hasText(receiptCode)) {
        receiptCode = externalRequestId;
      }
      int n = fileDispatchRepository.markAcked(tenantId, fileId, channelCode, receiptCode);
      if (n > 0) {
        pollSuccesses.incrementAndGet();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("channelCode", channelCode);
        meta.put("externalRequestId", externalRequestId);
        meta.put("receiptCode", receiptCode);
        runtimeRepository.updateFileStatus(fileId, "DISPATCHED", meta);
        log.info(
            "dispatch receipt acknowledged: tenantId={}, fileId={}, channelCode={}, receiptCode={}",
            tenantId,
            fileId,
            channelCode,
            receiptCode);
      } else {
        log.warn(
            "dispatch receipt ack skipped by state conflict: tenantId={}, fileId={},"
                + " channelCode={}, receiptCode={}",
            tenantId,
            fileId,
            channelCode,
            receiptCode);
      }
    }
  }

  private static Long toLong(Object v) {
    if (v instanceof Number n) {
      return n.longValue();
    }
    if (v == null) {
      return null;
    }
    return Long.parseLong(String.valueOf(v));
  }
}

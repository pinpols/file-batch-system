package com.example.batch.worker.dispatchs.infrastructure;

import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.dispatchs.config.DispatchReceiptPollProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
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
  private final OkHttpClient httpClient = new OkHttpClient();
  private final AtomicLong pollFailures = new AtomicLong();
  private final AtomicLong pollSuccesses = new AtomicLong();

  @PostConstruct
  void initializeMeters() {
    meterRegistry.gauge("batch.dispatch.receipt.poll.failures", pollFailures);
    meterRegistry.gauge("batch.dispatch.receipt.poll.successes", pollSuccesses);
  }

  @Scheduled(fixedDelayString = "${batch.worker.dispatch.receipt-poll.interval-millis:60000}")
  @SchedulerLock(name = "dispatch_receipt_poll", lockAtMostFor = "PT3M", lockAtLeastFor = "PT30S")
  public void poll() {
    if (!properties.isEnabled()) {
      return;
    }
    List<Map<String, Object>> rows =
        fileDispatchRepository.listPendingReceiptPolls(properties.getBatchSize());
    for (Map<String, Object> row : rows) {
      try {
        pollOne(row);
      } catch (Exception exception) {
        pollFailures.incrementAndGet();
        log.warn(
            "dispatch receipt poll failed: error={}, row={}",
            exception.getMessage(),
            row,
            exception);
      }
    }
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
        return;
      }
      // L-4：之前 response.body().string() 把整个响应一次性读进堆内存，异常响应体（如
      // 上游误返回大量调试信息）可能打爆 JVM。改流式读取 + maxReceiptBodyBytes 硬上限
      JsonNode root;
      try (java.io.InputStream bodyStream = response.body().byteStream()) {
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

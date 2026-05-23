package com.example.batch.console.support.push;

import com.example.batch.console.config.ConsolePushProperties;
import com.example.batch.console.domain.entity.ConsolePushApprovalNotificationEntity;
import com.example.batch.console.mapper.ConsolePushApprovalNotificationMapper;
import com.example.batch.console.support.push.ConsolePushSender.PushPayload;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 周期扫描进入终态(APPROVED / REJECTED / EXECUTED)的 approval_command,把结果回执推送给 {@code requester_id}。
 *
 * <p>调度模型同 {@link ConsolePushJobNotifier};幂等 / 防重逻辑相同。
 *
 * <p>开关:{@code batch.console.push.approval-notify.enabled=false}(默认关)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsolePushApprovalNotifier {

  private final ConsolePushProperties properties;
  private final ConsolePushApprovalNotificationMapper notificationMapper;
  private final ConsolePushSender pushSender;

  private ScheduledExecutorService scheduler;

  @PostConstruct
  void start() {
    if (!properties.isEnabled() || !properties.getApprovalNotify().isEnabled()) {
      log.info(
          "[push] ConsolePushApprovalNotifier disabled (push.enabled or approval-notify.enabled = false)");
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "push-approval-notifier");
              t.setDaemon(true);
              return t;
            });
    long intervalMillis = properties.getApprovalNotify().getPollIntervalMillis();
    scheduler.scheduleWithFixedDelay(
        this::pollSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    log.info(
        "[push] ConsolePushApprovalNotifier started, intervalMillis={} lookbackMinutes={} batchSize={}",
        intervalMillis,
        properties.getApprovalNotify().getLookbackMinutes(),
        properties.getApprovalNotify().getBatchSize());
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  void pollSafely() {
    try {
      pollOnce();
    } catch (RuntimeException e) {
      log.error("[push] approval-notify poll failed", e);
    }
  }

  void pollOnce() {
    List<PendingApprovalNotification> pending =
        notificationMapper.findPending(
            properties.getApprovalNotify().getLookbackMinutes(),
            properties.getApprovalNotify().getBatchSize());
    if (pending.isEmpty()) {
      return;
    }
    for (PendingApprovalNotification p : pending) {
      ConsolePushApprovalNotificationEntity record = new ConsolePushApprovalNotificationEntity();
      record.setTenantId(p.getTenantId());
      record.setApprovalNo(p.getApprovalNo());
      int inserted = notificationMapper.insertIgnore(record);
      if (inserted == 0) {
        continue;
      }
      PushPayload payload = buildPayload(p);
      pushSender.sendToUser(p.getTenantId(), p.getRequesterId(), payload);
    }
  }

  private static PushPayload buildPayload(PendingApprovalNotification p) {
    String statusLabel = statusLabel(p.getApprovalStatus());
    String typeLabel = typeLabel(p.getApprovalType());
    String title = String.format("%s 申请%s", typeLabel, statusLabel);
    String reason =
        "REJECTED".equals(p.getApprovalStatus()) ? p.getRejectionReason() : p.getApprovalReason();
    String body =
        reason == null || reason.isBlank()
            ? String.format("approver=%s · %s", nullToDash(p.getApproverId()), p.getApprovalNo())
            : String.format(
                "approver=%s · %s",
                nullToDash(p.getApproverId()), truncate(reason, 80));
    String tag = "approval-" + p.getApprovalNo();
    // 前端 /m/approvals 是列表页;带 ?id 让列表页打开后高亮/滚动到目标条。
    // 如果未来 FE 加了详情页 /m/approvals/:no,把这里改成 path 段即可。
    String url = "/m/approvals?id=" + p.getApprovalNo();
    return new PushPayload(title, body, tag, url);
  }

  private static String typeLabel(String type) {
    return switch (type) {
      case "CATCH_UP" -> "补跑";
      case "COMPENSATION" -> "补偿";
      case "DLQ_REPLAY" -> "死信重放";
      case "DOWNLOAD" -> "下载";
      default -> type;
    };
  }

  private static String statusLabel(String status) {
    return switch (status) {
      case "APPROVED" -> "已批准";
      case "REJECTED" -> "已驳回";
      case "EXECUTED" -> "已执行";
      default -> status;
    };
  }

  private static String nullToDash(String s) {
    return s == null || s.isBlank() ? "-" : s;
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }
}

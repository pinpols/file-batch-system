package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.security.CryptoAlgorithms;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.JobPushDeliveryMapper;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.PushPayload;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Called only after the first Job terminal receipt insert, before that transaction commits. */
@Service
@RequiredArgsConstructor
public class JobPushIntakeService {
  private final ConsolePushProperties properties;
  private final ConsolePushSubscriptionMapper subscriptions;
  private final JobPushDeliveryMapper deliveries;

  @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
  public void accept(String tenant, String eventKey, Map<String, Object> job) {
    Object rawOperator = job.get("operatorId");
    if (EmptyChecks.isNotNull(rawOperator) && !(rawOperator instanceof String)) {
      throw new IllegalArgumentException("invalid Job terminal operator identity");
    }
    String operator = (String) rawOperator;
    JobInstanceStatus state = JobInstanceStatus.fromCodeOrNull((String)job.get("instanceStatus"));
    if (EmptyChecks.isNull(state)) throw new IllegalArgumentException("invalid Job terminal status");
    long jobId = ((Number) job.get("jobInstanceId")).longValue();
    String eventType = "JOB_" + job.get("instanceStatus");
    String decision = Boolean.TRUE.equals(job.get("dryRun")) || state.isDryRunTerminal() ? "DRY_RUN"
        : !properties.isEnabled() || !properties.getJobNotify().isEnabled() ? "DISABLED"
        : EmptyChecks.isBlank(operator) ? "NO_OPERATOR" : "QUEUED";
    List<ConsolePushSubscriptionEntity> devices = "QUEUED".equals(decision)
        ? subscriptions.findByTenantAndUser(tenant, operator) : List.of();
    if ("QUEUED".equals(decision) && devices.isEmpty()) decision = "NO_SUBSCRIPTIONS";
    for (var device : devices) {
      if (!tenant.equals(device.getTenantId()) || !operator.equals(device.getUsername())
          || EmptyChecks.isNull(device.getId()) || device.getId() <= 0) {
        throw new IllegalStateException("Push device does not belong to the frozen Job operator");
      }
    }
    String payload = JsonUtils.toJson(new PushPayload(
        "任务 " + job.get("jobCode") + " " + statusLabel((String)job.get("instanceStatus")),
        "tenant=" + tenant + " · instance #" + jobId,
        "job-" + digest(eventKey),
        "/m/jobs/" + jobId + "?tenantId=" + URLEncoder.encode(tenant, StandardCharsets.UTF_8)));
    if (deliveries.insertDecision(tenant, eventKey, jobId, operator, decision, eventType, payload) != 1) {
      throw new IllegalStateException("first Job Push decision was not inserted");
    }
    for (var device : devices) {
      if (deliveries.insertDevice(tenant, eventKey, jobId, operator, device.getId(),
          fingerprint(device), eventType, payload) != 1) {
        throw new IllegalStateException("first Job Push device intention was not inserted");
      }
    }
  }

  static String fingerprint(ConsolePushSubscriptionEntity device) {
    return digest(JsonUtils.toJson(List.of(device.getTenantId(), device.getUsername(), device.getId(),
        device.getEndpoint(), device.getP256dhKey(), device.getAuthSecret())));
  }

  private static String statusLabel(String status) {
    return switch(status) {
      case "SUCCESS" -> "已成功";
      case "FAILED" -> "已失败";
      case "PARTIAL_FAILED" -> "部分失败";
      case "CANCELLED" -> "已取消";
      case "TERMINATED" -> "已终止";
      default -> status;
    };
  }

  private static String digest(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(CryptoAlgorithms.SHA_256)
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}

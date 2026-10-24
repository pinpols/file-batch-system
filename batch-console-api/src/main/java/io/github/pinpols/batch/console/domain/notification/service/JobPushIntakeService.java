package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.security.CryptoAlgorithms;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.JobPushDeliveryMapper;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDecisionInsertParam;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDeviceInsertParam;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.PushPayload;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 仅在首次插入 Job 终态回执之后、该事务提交之前调用。 */
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
    JobInstanceStatus state = JobInstanceStatus.fromCodeOrNull((String) job.get("instanceStatus"));
    if (EmptyChecks.isNull(state))
      throw new IllegalArgumentException("invalid Job terminal status");
    long jobId = ((Number) job.get("jobInstanceId")).longValue();
    String eventType = "JOB_" + job.get("instanceStatus");
    String decision = Boolean.TRUE.equals(job.get("dryRun")) || state.isDryRunTerminal()
        ? "DRY_RUN"
        : !properties.isEnabled() || !properties.getJobNotify().isEnabled()
            ? "DISABLED"
            : EmptyChecks.isBlank(operator) ? "NO_OPERATOR" : "QUEUED";
    List<ConsolePushSubscriptionEntity> devices =
        "QUEUED".equals(decision) ? subscriptions.findByTenantAndUser(tenant, operator) : List.of();
    if ("QUEUED".equals(decision) && EmptyChecks.isEmpty(Objects.requireNonNull(devices))) decision = "NO_SUBSCRIPTIONS";
    for (ConsolePushSubscriptionEntity device : devices) {
      boolean deviceDoesNotMatchFrozenJobOperator =
          !tenant.equals(device.getTenantId())
          || !operator.equals(device.getUsername())
          || EmptyChecks.isNull(device.getId())
          || device.getId() <= 0;
      if (deviceDoesNotMatchFrozenJobOperator) {
        throw new IllegalStateException("Push device does not belong to the frozen Job operator");
      }
    }
    Object jobCode = job.get("jobCode");
    String instanceStatus = (String) job.get("instanceStatus");
    String title = "任务 " + jobCode + " " + statusLabel(instanceStatus);
    String body = "tenant=" + tenant + " · instance #" + jobId;
    String tag = "job-" + digest(eventKey);
    String url =
        "/m/jobs/" + jobId + "?tenantId=" + URLEncoder.encode(tenant, StandardCharsets.UTF_8);
    PushPayload pushPayload = new PushPayload(title, body, tag, url);
    String payload = JsonUtils.toJson(pushPayload);
    JobPushDecisionInsertParam decisionParam = new JobPushDecisionInsertParam(
        tenant, eventKey, jobId, operator, decision, eventType, payload);
    if (deliveries.insertDecision(decisionParam) != 1) {
      throw new IllegalStateException("first Job Push decision was not inserted");
    }
    for (ConsolePushSubscriptionEntity device : devices) {
      JobPushDeviceInsertParam deviceParam = new JobPushDeviceInsertParam(
          tenant,
          eventKey,
          jobId,
          operator,
          device.getId(),
          fingerprint(device),
          eventType,
          payload);
      if (deliveries.insertDevice(deviceParam) != 1) {
        throw new IllegalStateException("first Job Push device intention was not inserted");
      }
    }
  }

  static String fingerprint(ConsolePushSubscriptionEntity device) {
    List<Object> fingerprintFields = List.of(
        device.getTenantId(),
        device.getUsername(),
        device.getId(),
        device.getEndpoint(),
        device.getP256dhKey(),
        device.getAuthSecret());
    String serializedFingerprint = JsonUtils.toJson(fingerprintFields);
    return digest(serializedFingerprint);
  }

  private static String statusLabel(String status) {
    return switch (status) {
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
      MessageDigest sha256 = MessageDigest.getInstance(CryptoAlgorithms.SHA_256);
      byte[] inputBytes = value.getBytes(StandardCharsets.UTF_8);
      byte[] digest = sha256.digest(inputBytes);
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}

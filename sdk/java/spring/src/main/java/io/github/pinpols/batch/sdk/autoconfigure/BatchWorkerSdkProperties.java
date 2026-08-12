package io.github.pinpols.batch.sdk.autoconfigure;

import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "batch.worker-sdk")
public class BatchWorkerSdkProperties {

  private boolean enabled = true;
  private String baseUrl;
  private String apiKey;
  private String tenantId;
  private String workerCode;
  private String kafkaBootstrap;
  private String kafkaTopicPattern;
  private String kafkaGroupId;
  private String buildId;
  private Duration httpTimeout = Duration.ofSeconds(10);
  private Duration heartbeatInterval = Duration.ofSeconds(30);
  private int maxConcurrentTasks = 4;
  private Duration kafkaPollInterval = Duration.ofMillis(200);
  private Duration leaseRenewInterval = Duration.ofSeconds(60);
  private int claimMax5xxRetries = 3;
  private Duration claimRetryBaseDelay = Duration.ofMillis(200);
  private int clientErrorFailFastThreshold = 5;
  private String kafkaSecurityProtocol;
  private String kafkaSaslMechanism;
  private String kafkaSaslJaasConfig;
  // R3-4 时序校验严格度开关(默认 fail-fast);对齐 BatchPlatformClientConfig.strictTimingValidation。
  private boolean strictTimingValidation = true;
  // 写请求 HMAC 签名开关(默认关);对齐 BatchPlatformClientConfig.requestSigningEnabled。
  // 缺此键时 Spring 用户无法开启请求签名。
  private boolean requestSigningEnabled = false;

  BatchPlatformClientConfig toConfig() {
    return BatchPlatformClientConfig.builder()
        .baseUrl(baseUrl)
        .apiKey(apiKey)
        .tenantId(tenantId)
        .workerCode(workerCode)
        .kafkaBootstrap(kafkaBootstrap)
        .kafkaTopicPattern(kafkaTopicPattern)
        .kafkaGroupId(kafkaGroupId)
        .buildId(buildId)
        .httpTimeout(httpTimeout)
        .heartbeatInterval(heartbeatInterval)
        .maxConcurrentTasks(maxConcurrentTasks)
        .kafkaPollInterval(kafkaPollInterval)
        .leaseRenewInterval(leaseRenewInterval)
        .claimMax5xxRetries(claimMax5xxRetries)
        .claimRetryBaseDelay(claimRetryBaseDelay)
        .clientErrorFailFastThreshold(clientErrorFailFastThreshold)
        .kafkaSecurityProtocol(kafkaSecurityProtocol)
        .kafkaSaslMechanism(kafkaSaslMechanism)
        .kafkaSaslJaasConfig(kafkaSaslJaasConfig)
        .strictTimingValidation(strictTimingValidation)
        .requestSigningEnabled(requestSigningEnabled)
        .build();
  }
}

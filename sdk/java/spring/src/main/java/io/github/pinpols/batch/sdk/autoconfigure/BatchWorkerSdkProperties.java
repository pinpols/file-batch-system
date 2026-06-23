package io.github.pinpols.batch.sdk.autoconfigure;

import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
        .build();
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getWorkerCode() {
    return workerCode;
  }

  public void setWorkerCode(String workerCode) {
    this.workerCode = workerCode;
  }

  public String getKafkaBootstrap() {
    return kafkaBootstrap;
  }

  public void setKafkaBootstrap(String kafkaBootstrap) {
    this.kafkaBootstrap = kafkaBootstrap;
  }

  public String getKafkaTopicPattern() {
    return kafkaTopicPattern;
  }

  public void setKafkaTopicPattern(String kafkaTopicPattern) {
    this.kafkaTopicPattern = kafkaTopicPattern;
  }

  public String getKafkaGroupId() {
    return kafkaGroupId;
  }

  public void setKafkaGroupId(String kafkaGroupId) {
    this.kafkaGroupId = kafkaGroupId;
  }

  public String getBuildId() {
    return buildId;
  }

  public void setBuildId(String buildId) {
    this.buildId = buildId;
  }

  public Duration getHttpTimeout() {
    return httpTimeout;
  }

  public void setHttpTimeout(Duration httpTimeout) {
    this.httpTimeout = httpTimeout;
  }

  public Duration getHeartbeatInterval() {
    return heartbeatInterval;
  }

  public void setHeartbeatInterval(Duration heartbeatInterval) {
    this.heartbeatInterval = heartbeatInterval;
  }

  public int getMaxConcurrentTasks() {
    return maxConcurrentTasks;
  }

  public void setMaxConcurrentTasks(int maxConcurrentTasks) {
    this.maxConcurrentTasks = maxConcurrentTasks;
  }

  public Duration getKafkaPollInterval() {
    return kafkaPollInterval;
  }

  public void setKafkaPollInterval(Duration kafkaPollInterval) {
    this.kafkaPollInterval = kafkaPollInterval;
  }

  public Duration getLeaseRenewInterval() {
    return leaseRenewInterval;
  }

  public void setLeaseRenewInterval(Duration leaseRenewInterval) {
    this.leaseRenewInterval = leaseRenewInterval;
  }

  public int getClaimMax5xxRetries() {
    return claimMax5xxRetries;
  }

  public void setClaimMax5xxRetries(int claimMax5xxRetries) {
    this.claimMax5xxRetries = claimMax5xxRetries;
  }

  public Duration getClaimRetryBaseDelay() {
    return claimRetryBaseDelay;
  }

  public void setClaimRetryBaseDelay(Duration claimRetryBaseDelay) {
    this.claimRetryBaseDelay = claimRetryBaseDelay;
  }

  public int getClientErrorFailFastThreshold() {
    return clientErrorFailFastThreshold;
  }

  public void setClientErrorFailFastThreshold(int clientErrorFailFastThreshold) {
    this.clientErrorFailFastThreshold = clientErrorFailFastThreshold;
  }

  public String getKafkaSecurityProtocol() {
    return kafkaSecurityProtocol;
  }

  public void setKafkaSecurityProtocol(String kafkaSecurityProtocol) {
    this.kafkaSecurityProtocol = kafkaSecurityProtocol;
  }

  public String getKafkaSaslMechanism() {
    return kafkaSaslMechanism;
  }

  public void setKafkaSaslMechanism(String kafkaSaslMechanism) {
    this.kafkaSaslMechanism = kafkaSaslMechanism;
  }

  public String getKafkaSaslJaasConfig() {
    return kafkaSaslJaasConfig;
  }

  public void setKafkaSaslJaasConfig(String kafkaSaslJaasConfig) {
    this.kafkaSaslJaasConfig = kafkaSaslJaasConfig;
  }
}

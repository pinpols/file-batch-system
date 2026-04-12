package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.file-governance")
public class FileGovernanceProperties {

  private final Latency latency = new Latency();
  private final Archive archive = new Archive();
  private final Reconcile reconcile = new Reconcile();
  private final Arrival arrival = new Arrival();
  private final Access access = new Access();

  @Data
  public static class Latency {
    private boolean enabled = true;
    private long pollIntervalMillis = 30000L;
    private long arrivalDelayThresholdSeconds = 600L;
    private long processingDelayThresholdSeconds = 900L;
    private int sampleSize = 20;
  }

  @Data
  public static class Archive {
    private boolean enabled = true;
    private long cleanupIntervalMillis = 60000L;
    private int cleanupBatchSize = 100;
    private int retentionDays = 7;
  }

  @Data
  public static class Reconcile {
    private boolean enabled = true;
    private long pollIntervalMillis = 60000L;
    private int batchSize = 200;
    private String defaultTenantId = "default-tenant";
    private String prefix = "";
    private boolean includeTemporaryObjects = false;
  }

  @Data
  public static class Arrival {
    private boolean enabled = true;
    private long pollIntervalMillis = 30000L;
    private int batchSize = 200;
    private String defaultTimeoutAction = "MANUAL_CONFIRM";
    private boolean triggerOnComplete = true;
    private long manualWaitExtensionSeconds = 1800L;
  }

  @Data
  public static class Access {
    private boolean enabled = true;
    private int presignExpirySeconds = 600;
  }
}

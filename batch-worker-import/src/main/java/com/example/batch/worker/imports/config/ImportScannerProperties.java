package com.example.batch.worker.imports.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.import.scanner")
public class ImportScannerProperties {

  private boolean enabled = true;
  private long pollIntervalMillis = 30000L;
  private int batchSize = 200;
  private String prefix = "ingress/";
  private boolean requireDoneFile = false;
  private long stabilityWindowSeconds = 30L;
  private String sourceType = "SYSTEM";
  private String defaultBizType = "IMPORT_SCAN";
  private String defaultBizDate = "";
  private final Arrival arrival = new Arrival();

  @Data
  public static class Arrival {
    private boolean enabled = false;
    private String fileGroupCode = "";
    private String waitFileGroupMode = "ALL_OF";
    private String requiredFileSet = "";
    private String arrivalTimeoutAction = "MANUAL_CONFIRM";
    private long expectedArrivalDelaySeconds = 0L;
    private long latestTolerableDelaySeconds = 600L;
    private boolean triggerOnComplete = true;
    private boolean allowEmptyRun = false;
    private boolean allowSkipBizDate = false;
    private boolean notifyManual = true;
    private String notifyChannels = "";
  }
}

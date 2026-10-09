package io.github.pinpols.batch.console.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 新建作业时采用的平台级监控默认值；运行耗时告警默认关闭，启动宽限按调度语义启用。 */
@Data
@Validated
@ConfigurationProperties(prefix = "batch.job-monitoring.defaults")
public class JobMonitoringDefaultsProperties {

  @Min(0)
  @Max(86_400)
  private int softRuntimeSeconds;

  @Min(0)
  @Max(86_400)
  private int startGraceSeconds = 300;
}

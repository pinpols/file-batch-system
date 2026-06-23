package io.github.pinpols.batch.common.health;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "batch.health.hikari")
@Validated
public class HikariSaturationProperties {

  /** 饱和度阈值(active / max),≥ 该值健康端点报 DOWN。默认 0.9 = 90%。 */
  @DecimalMin("0.1")
  @DecimalMax("1.0")
  private double threshold = 0.9;

  /** 整个探针开关;false 时 AutoConfiguration 不注册 bean。 */
  private boolean enabled = true;

  public double getThreshold() {
    return threshold;
  }

  public void setThreshold(double threshold) {
    this.threshold = threshold;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}

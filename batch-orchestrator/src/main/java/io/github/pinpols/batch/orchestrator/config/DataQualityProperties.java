package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 数据质量规则执行与结果版本门禁配置。 */
@Data
@ConfigurationProperties(prefix = "batch.data-quality")
public class DataQualityProperties {

  /** 默认保持既有阻断语义；灰度期间可切换为 SHADOW，紧急回退可切换为 OFF。 */
  private Mode mode = Mode.ENFORCE;

  public enum Mode {
    OFF,
    SHADOW,
    ENFORCE
  }
}

package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** JOB asset freshness policy 扫描配置。 */
@Data
@ConfigurationProperties(prefix = "batch.asset-freshness")
public class AssetFreshnessPolicyProperties {

  private boolean enabled = true;
  private int batchLimit = 500;
}

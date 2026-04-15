package com.example.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.datasource.business")
public class BusinessDataSourceProperties {

  private String url;
  private String username;
  private String password;
  private String schema;

  // A-3.3: Worker 业务库 HikariCP 配置，避免使用默认值导致连接耗尽
  private int maximumPoolSize = 20;
  private int minimumIdle = 5;
  private long connectionTimeoutMs = 5000L;
  private long leakDetectionThresholdMs = 30000L;
}

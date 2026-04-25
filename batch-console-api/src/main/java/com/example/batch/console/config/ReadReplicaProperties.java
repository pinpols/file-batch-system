package com.example.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P2-4: Console-api 读写分离配置（{@code batch.console.read-replica}）。
 *
 * <p>启用后：
 * <ul>
 *   <li>{@code @Transactional(readOnly = true)} 标注的查询路由到从库 HikariPool
 *   <li>读写事务（默认 readOnly=false）走主库 HikariPool
 *   <li>未在事务内的偶发查询走主库（保守策略）
 * </ul>
 *
 * <p>未启用（默认）时不创建从库连接池，行为同历史（Spring Boot 主 DataSource 自动配置）。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.read-replica")
public class ReadReplicaProperties {

  private boolean enabled = false;

  private Primary primary = new Primary();

  private Replica replica = new Replica();

  @Data
  public static class Primary {
    private String url;
    private String username;
    private String password;
    private String driverClassName = "org.postgresql.Driver";
    private int maximumPoolSize = 16;
    private int minimumIdle = 2;
  }

  @Data
  public static class Replica {
    private String url;
    private String username;
    private String password;
    private String driverClassName = "org.postgresql.Driver";
    private int maximumPoolSize = 16;
    private int minimumIdle = 2;
  }
}

package io.github.pinpols.batch.worker.exports.config;

import com.zaxxer.hikari.HikariConfig;
import io.github.pinpols.batch.common.config.BatchPgSessionProperties;
import io.github.pinpols.batch.worker.core.config.WorkerDataSourceSupport;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * 平台数据源配置，提供导出 Worker 所需的平台库 MyBatis SqlSession（Primary）。装配逻辑委托 {@link WorkerDataSourceSupport}（三个
 * worker 模块共用）。
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
@RequiredArgsConstructor
public class PlatformDataSourceConfiguration {

  private final BatchPgSessionProperties pgSessionProperties;
  private final Environment environment;

  @Bean(name = "exportPlatformHikariConfig")
  @ConfigurationProperties("spring.datasource.hikari")
  public HikariConfig exportPlatformHikariConfig() {
    return new HikariConfig();
  }

  @Bean(name = "exportPlatformDataSource")
  @Primary
  public DataSource exportPlatformDataSource(
      DataSourceProperties properties,
      @Qualifier("exportPlatformHikariConfig") HikariConfig hikariConfig) {
    return WorkerDataSourceSupport.buildPlatformDataSource(
        properties, hikariConfig, pgSessionProperties, environment, "batch-worker-export");
  }

  @Bean(name = "exportPlatformSqlSessionFactory")
  public SqlSessionFactory exportPlatformSqlSessionFactory(
      @Qualifier("exportPlatformDataSource") DataSource dataSource) throws Exception {
    return WorkerDataSourceSupport.buildPlatformSqlSessionFactory(dataSource);
  }

  @Bean(name = "exportPlatformSqlSessionTemplate")
  public SqlSessionTemplate exportPlatformSqlSessionTemplate(
      @Qualifier("exportPlatformSqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
    return new SqlSessionTemplate(sqlSessionFactory);
  }
}

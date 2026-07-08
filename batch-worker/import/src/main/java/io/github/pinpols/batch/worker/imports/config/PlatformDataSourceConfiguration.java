package io.github.pinpols.batch.worker.imports.config;

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

/** 平台数据源配置，装配逻辑委托 {@link WorkerDataSourceSupport}（三个 worker 模块共用）。 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
@RequiredArgsConstructor
public class PlatformDataSourceConfiguration {

  private final BatchPgSessionProperties pgSessionProperties;
  private final Environment environment;

  @Bean(name = "importPlatformHikariConfig")
  @ConfigurationProperties("spring.datasource.hikari")
  public HikariConfig importPlatformHikariConfig() {
    return new HikariConfig();
  }

  @Bean(name = "importPlatformDataSource")
  @Primary
  public DataSource importPlatformDataSource(
      DataSourceProperties properties,
      @Qualifier("importPlatformHikariConfig") HikariConfig hikariConfig) {
    return WorkerDataSourceSupport.buildPlatformDataSource(
        properties, hikariConfig, pgSessionProperties, environment, "batch-worker-import");
  }

  @Bean(name = "importPlatformSqlSessionFactory")
  public SqlSessionFactory importPlatformSqlSessionFactory(
      @Qualifier("importPlatformDataSource") DataSource dataSource) throws Exception {
    return WorkerDataSourceSupport.buildPlatformSqlSessionFactory(dataSource);
  }

  @Bean(name = "importPlatformSqlSessionTemplate")
  public SqlSessionTemplate importPlatformSqlSessionTemplate(
      @Qualifier("importPlatformSqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
    return new SqlSessionTemplate(sqlSessionFactory);
  }
}

package io.github.pinpols.batch.worker.processes.config;

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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 平台数据源配置，提供 PROCESS Worker 所需的平台库 MyBatis SqlSession。装配逻辑委托 {@link WorkerDataSourceSupport}（三个
 * worker 模块共用）。
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
@RequiredArgsConstructor
public class PlatformDataSourceConfiguration {

  private final BatchPgSessionProperties pgSessionProperties;
  private final Environment environment;

  @Bean(name = "processPlatformHikariConfig")
  @ConfigurationProperties("spring.datasource.hikari")
  public HikariConfig processPlatformHikariConfig() {
    return new HikariConfig();
  }

  @Bean(name = "processPlatformDataSource")
  @Primary
  public DataSource processPlatformDataSource(
      DataSourceProperties properties,
      @Qualifier("processPlatformHikariConfig") HikariConfig hikariConfig) {
    return WorkerDataSourceSupport.buildPlatformDataSource(
        properties, hikariConfig, pgSessionProperties, environment, "batch-worker-process");
  }

  @Bean(name = "processPlatformSqlSessionFactory")
  public SqlSessionFactory processPlatformSqlSessionFactory(
      @Qualifier("processPlatformDataSource") DataSource dataSource) throws Exception {
    return WorkerDataSourceSupport.buildPlatformSqlSessionFactory(dataSource);
  }

  @Bean(name = "processPlatformSqlSessionTemplate")
  public SqlSessionTemplate processPlatformSqlSessionTemplate(
      @Qualifier("processPlatformSqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
    return new SqlSessionTemplate(sqlSessionFactory);
  }

  /**
   * 平台库 TransactionManager（区别于 {@code processBusinessTransactionManager}）——process 特有：process
   * 模块存在需要跨平台库事务管理的场景，import/export 未见对应需求，保留为模块差异（详见任务报告）。
   */
  @Bean(name = "transactionManager")
  @Primary
  public DataSourceTransactionManager transactionManager(
      @Qualifier("processPlatformDataSource") DataSource processPlatformDataSource) {
    return WorkerDataSourceSupport.buildTransactionManager(processPlatformDataSource);
  }
}

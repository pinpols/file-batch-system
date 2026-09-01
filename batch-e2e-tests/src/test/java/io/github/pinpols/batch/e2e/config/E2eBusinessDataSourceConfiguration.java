package io.github.pinpols.batch.e2e.config;

import io.github.pinpols.batch.worker.core.config.WorkerDataSourceSupport;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * E2E 业务库连接配置。
 *
 * <p>E2E 基础设施会启动独立的 business PostgreSQL 容器。业务表、业务 mapper 和 {@code @Sql} 业务 schema
 * 初始化必须显式使用本配置提供的数据源，不能回退到平台库的主数据源。
 */
@Configuration(proxyBeanMethods = false)
public class E2eBusinessDataSourceConfiguration {

  @Bean(name = "e2eBusinessDataSource")
  public DataSource e2eBusinessDataSource(
      @Value("${batch.datasource.business.url}") String url,
      @Value("${batch.datasource.business.username}") String username,
      @Value("${batch.datasource.business.password}") String password) {
    return DataSourceBuilder.create()
        .url(url)
        .username(username)
        .password(password)
        .driverClassName("org.postgresql.Driver")
        .build();
  }

  @Bean(name = "e2eBusinessTransactionManager", autowireCandidate = false)
  public DataSourceTransactionManager e2eBusinessTransactionManager(
      @Qualifier("e2eBusinessDataSource") DataSource e2eBusinessDataSource) {
    return new DataSourceTransactionManager(e2eBusinessDataSource);
  }

  @Bean(name = "e2eBusinessSqlSessionFactory")
  public SqlSessionFactory e2eBusinessSqlSessionFactory(
      @Qualifier("e2eBusinessDataSource") DataSource e2eBusinessDataSource) throws Exception {
    return WorkerDataSourceSupport.buildBusinessSqlSessionFactory(e2eBusinessDataSource);
  }

  @Bean(name = "e2eBusinessSqlSessionTemplate")
  public SqlSessionTemplate e2eBusinessSqlSessionTemplate(
      @Qualifier("e2eBusinessSqlSessionFactory") SqlSessionFactory e2eBusinessSqlSessionFactory) {
    return new SqlSessionTemplate(e2eBusinessSqlSessionFactory);
  }
}

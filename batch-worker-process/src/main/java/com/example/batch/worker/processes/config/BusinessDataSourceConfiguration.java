package com.example.batch.worker.processes.config;

import com.example.batch.common.config.BusinessDataSourceProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 业务数据源配置，提供 PROCESS 配置驱动 SQL 加工访问业务库的连接池。 */
@Configuration("processWorkerBusinessDataSourceConfiguration")
@EnableConfigurationProperties(BusinessDataSourceProperties.class)
public class BusinessDataSourceConfiguration {

  @Bean(name = "processBusinessHikariConfig")
  @ConfigurationProperties("batch.datasource.business.hikari")
  public HikariConfig processBusinessHikariConfig() {
    return new HikariConfig();
  }

  @Bean(name = "processBusinessDataSource")
  public DataSource processBusinessDataSource(
      BusinessDataSourceProperties properties,
      @Qualifier("processBusinessHikariConfig") HikariConfig hikariConfig) {
    hikariConfig.setJdbcUrl(properties.getUrl());
    hikariConfig.setUsername(properties.getUsername());
    hikariConfig.setPassword(properties.getPassword());
    if (hikariConfig.getDriverClassName() == null || hikariConfig.getDriverClassName().isBlank()) {
      hikariConfig.setDriverClassName("org.postgresql.Driver");
    }
    if (hikariConfig.getMaximumPoolSize() <= 1) {
      hikariConfig.setMaximumPoolSize(properties.getMaximumPoolSize());
    }
    if (hikariConfig.getMinimumIdle() < 0) {
      hikariConfig.setMinimumIdle(properties.getMinimumIdle());
    }
    if (hikariConfig.getConnectionTimeout() <= 0) {
      hikariConfig.setConnectionTimeout(properties.getConnectionTimeoutMs());
    }
    if (hikariConfig.getLeakDetectionThreshold() <= 0) {
      hikariConfig.setLeakDetectionThreshold(properties.getLeakDetectionThresholdMs());
    }
    return new HikariDataSource(hikariConfig);
  }

  @Bean(name = "processBusinessSqlSessionFactory")
  public SqlSessionFactory processBusinessSqlSessionFactory(
      @Qualifier("processBusinessDataSource") DataSource processBusinessDataSource)
      throws Exception {
    SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
    factoryBean.setDataSource(processBusinessDataSource);
    Resource[] businessMappers =
        new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/business/*.xml");
    if (businessMappers.length > 0) {
      factoryBean.setMapperLocations(businessMappers);
    }
    org.apache.ibatis.session.Configuration configuration =
        new org.apache.ibatis.session.Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    factoryBean.setConfiguration(configuration);
    return factoryBean.getObject();
  }

  @Bean(name = "processBusinessSqlSessionTemplate")
  public SqlSessionTemplate processBusinessSqlSessionTemplate(
      @Qualifier("processBusinessSqlSessionFactory")
          SqlSessionFactory processBusinessSqlSessionFactory) {
    return new SqlSessionTemplate(processBusinessSqlSessionFactory);
  }

  @Bean(name = "processBusinessTransactionManager")
  public DataSourceTransactionManager processBusinessTransactionManager(
      @Qualifier("processBusinessDataSource") DataSource processBusinessDataSource) {
    return new DataSourceTransactionManager(processBusinessDataSource);
  }
}

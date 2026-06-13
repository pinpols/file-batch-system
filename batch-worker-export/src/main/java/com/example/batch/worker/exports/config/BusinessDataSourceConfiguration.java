package com.example.batch.worker.exports.config;

import com.example.batch.common.config.BatchPgSessionProperties;
import com.example.batch.common.config.BusinessDataSourceProperties;
import com.example.batch.common.config.HikariPgSessionSupport;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** 业务数据源配置，提供导出任务所需的业务库 MyBatis SqlSession。 */
@Configuration("exportWorkerBusinessDataSourceConfiguration")
@EnableConfigurationProperties(BusinessDataSourceProperties.class)
@RequiredArgsConstructor
public class BusinessDataSourceConfiguration {

  private final BatchPgSessionProperties pgSessionProperties;
  private final Environment environment;

  @Bean(name = "exportBusinessHikariConfig")
  @ConfigurationProperties("batch.datasource.business.hikari")
  public HikariConfig exportBusinessHikariConfig() {
    return new HikariConfig();
  }

  @Bean(name = "exportBusinessDataSource")
  public DataSource exportBusinessDataSource(
      BusinessDataSourceProperties properties,
      @Qualifier("exportBusinessHikariConfig") HikariConfig hikariConfig) {
    hikariConfig.setJdbcUrl(properties.getUrl());
    hikariConfig.setUsername(properties.getUsername());
    hikariConfig.setPassword(properties.getPassword());
    if (hikariConfig.getDriverClassName() == null || hikariConfig.getDriverClassName().isBlank()) {
      hikariConfig.setDriverClassName("org.postgresql.Driver");
    }
    // A-3.3: 业务库连接池显式配置
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
    // HA:主备切换硬化——max-lifetime 主动回收旧连接(keepalive 由下方 applyBusiness 兜底)
    if (properties.getMaxLifetimeMs() > 0) {
      hikariConfig.setMaxLifetime(properties.getMaxLifetimeMs());
    }
    String appName = environment.getProperty("spring.application.name", "batch-worker-export");
    HikariPgSessionSupport.applyBusiness(hikariConfig, pgSessionProperties, appName + "-business");
    return new HikariDataSource(hikariConfig);
  }

  @Bean(name = "exportBusinessSqlSessionFactory")
  public SqlSessionFactory exportBusinessSqlSessionFactory(
      @Qualifier("exportBusinessDataSource") DataSource exportBusinessDataSource) throws Exception {
    SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
    factoryBean.setDataSource(exportBusinessDataSource);
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

  @Bean(name = "exportBusinessSqlSessionTemplate")
  public SqlSessionTemplate exportBusinessSqlSessionTemplate(
      @Qualifier("exportBusinessSqlSessionFactory")
          SqlSessionFactory exportBusinessSqlSessionFactory) {
    return new SqlSessionTemplate(exportBusinessSqlSessionFactory);
  }
}

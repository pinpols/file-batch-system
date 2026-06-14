package com.example.batch.worker.imports.config;

import com.example.batch.common.config.BatchPgSessionProperties;
import com.example.batch.common.config.BusinessDataSourceProperties;
import com.example.batch.common.config.HikariPgSessionSupport;
import com.example.batch.common.tenant.routing.BusinessRoutingDataSourceFactory;
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

@Configuration("importWorkerBusinessDataSourceConfiguration")
@EnableConfigurationProperties(BusinessDataSourceProperties.class)
@RequiredArgsConstructor
public class BusinessDataSourceConfiguration {

  private final BatchPgSessionProperties pgSessionProperties;
  private final Environment environment;

  @Bean(name = "importBusinessHikariConfig")
  @ConfigurationProperties("batch.datasource.business.hikari")
  public HikariConfig importBusinessHikariConfig() {
    return new HikariConfig();
  }

  @Bean(name = "importBusinessDataSource")
  public DataSource importBusinessDataSource(
      BusinessDataSourceProperties properties,
      @Qualifier("importBusinessHikariConfig") HikariConfig hikariConfig) {
    hikariConfig.setJdbcUrl(properties.getUrl());
    hikariConfig.setUsername(properties.getUsername());
    hikariConfig.setPassword(properties.getPassword());
    if (hikariConfig.getDriverClassName() == null || hikariConfig.getDriverClassName().isBlank()) {
      hikariConfig.setDriverClassName("org.postgresql.Driver");
    }
    // A-3.3: 业务库连接池显式配置，避免默认值导致连接耗尽
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
    // HA:主备切换硬化——主动回收旧连接 + 校验快速失败,避免切换后坏连接被借出。
    // keepalive(空闲保活探测)由下方 HikariPgSessionSupport.applyBusiness 统一兜底,不在此重复设。
    if (properties.getMaxLifetimeMs() > 0) {
      hikariConfig.setMaxLifetime(properties.getMaxLifetimeMs());
    }
    if (properties.getValidationTimeoutMs() > 0) {
      hikariConfig.setValidationTimeout(properties.getValidationTimeoutMs());
    }
    String appName = environment.getProperty("spring.application.name", "batch-worker-import");
    HikariPgSessionSupport.applyBusiness(hikariConfig, pgSessionProperties, appName + "-business");
    // P1-2:把现有 Hikari 包成 biz 路由 DataSource(单片 shard-0=现库,零行为变更),
    // 建立按租户路由 seam;P2 扩多片只改装配,下游(SqlSessionFactory)不动。
    return BusinessRoutingDataSourceFactory.singleShard(new HikariDataSource(hikariConfig));
  }

  @Bean(name = "importBusinessSqlSessionFactory")
  public SqlSessionFactory importBusinessSqlSessionFactory(
      @Qualifier("importBusinessDataSource") DataSource importBusinessDataSource) throws Exception {
    SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
    factoryBean.setDataSource(importBusinessDataSource);
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

  @Bean(name = "importBusinessSqlSessionTemplate")
  public SqlSessionTemplate importBusinessSqlSessionTemplate(
      @Qualifier("importBusinessSqlSessionFactory")
          SqlSessionFactory importBusinessSqlSessionFactory) {
    return new SqlSessionTemplate(importBusinessSqlSessionFactory);
  }
}

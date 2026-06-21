package com.example.batch.worker.imports.config;

import com.example.batch.common.config.BatchPgSessionProperties;
import com.example.batch.common.config.BusinessDataSourceBuilder;
import com.example.batch.common.config.BusinessDataSourceProperties;
import com.example.batch.common.config.BusinessRoutingProperties;
import com.example.batch.common.mapper.BusinessTenantPlacementMapper;
import com.example.batch.common.tenant.routing.MyBatisTenantPlacementRepository;
import com.zaxxer.hikari.HikariConfig;
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
@EnableConfigurationProperties({
  BusinessDataSourceProperties.class,
  BusinessRoutingProperties.class
})
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
      BusinessRoutingProperties routingProperties,
      BusinessTenantPlacementMapper placementMapper,
      @Qualifier("importBusinessHikariConfig") HikariConfig hikariConfig) {
    String appName = environment.getProperty("spring.application.name", "batch-worker-import");
    // 构造 + pg-session 回退 + 路由包裹统一收敛到 BusinessDataSourceBuilder;routing 默认关=单片无损,开=多片
    // placement mapper 供 TABLE 模式 resolver 读 placement 表(单片/CONFIG 模式忽略)
    return BusinessDataSourceBuilder.build(
        hikariConfig,
        properties,
        pgSessionProperties,
        routingProperties,
        new MyBatisTenantPlacementRepository(placementMapper),
        appName);
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

package com.example.batch.worker.exports.config;

import com.example.batch.common.config.BatchPgSessionProperties;
import com.example.batch.common.config.BusinessDataSourceBuilder;
import com.example.batch.common.config.BusinessDataSourceProperties;
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
    String appName = environment.getProperty("spring.application.name", "batch-worker-export");
    // 构造 + pg-session 兜底 + 单片路由包裹统一收敛到 BusinessDataSourceBuilder(消除 3 worker 重复)
    return BusinessDataSourceBuilder.build(hikariConfig, properties, pgSessionProperties, appName);
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

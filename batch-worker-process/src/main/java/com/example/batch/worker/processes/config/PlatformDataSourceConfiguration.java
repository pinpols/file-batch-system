package com.example.batch.worker.processes.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 平台数据源配置，提供 PROCESS Worker 所需的平台库 MyBatis SqlSession。 */
@org.springframework.context.annotation.Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class PlatformDataSourceConfiguration {

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
    hikariConfig.setJdbcUrl(properties.determineUrl());
    hikariConfig.setUsername(properties.determineUsername());
    hikariConfig.setPassword(properties.determinePassword());
    String driverClassName = properties.determineDriverClassName();
    if (hikariConfig.getDriverClassName() == null || hikariConfig.getDriverClassName().isBlank()) {
      hikariConfig.setDriverClassName(driverClassName);
    }
    return new HikariDataSource(hikariConfig);
  }

  @Bean(name = "processPlatformSqlSessionFactory")
  public SqlSessionFactory processPlatformSqlSessionFactory(
      @Qualifier("processPlatformDataSource") DataSource dataSource) throws Exception {
    SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
    factoryBean.setDataSource(dataSource);
    Resource[] platformMappers =
        new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml");
    if (platformMappers.length > 0) {
      factoryBean.setMapperLocations(platformMappers);
    }
    Configuration configuration = new Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    factoryBean.setConfiguration(configuration);
    return factoryBean.getObject();
  }

  @Bean(name = "processPlatformSqlSessionTemplate")
  public SqlSessionTemplate processPlatformSqlSessionTemplate(
      @Qualifier("processPlatformSqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
    return new SqlSessionTemplate(sqlSessionFactory);
  }

  @Bean(name = "transactionManager")
  @Primary
  public DataSourceTransactionManager transactionManager(
      @Qualifier("processPlatformDataSource") DataSource processPlatformDataSource) {
    return new DataSourceTransactionManager(processPlatformDataSource);
  }
}

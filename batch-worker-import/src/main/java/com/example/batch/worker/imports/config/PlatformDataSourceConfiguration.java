package com.example.batch.worker.imports.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class PlatformDataSourceConfiguration {

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
    hikariConfig.setJdbcUrl(properties.determineUrl());
    hikariConfig.setUsername(properties.determineUsername());
    hikariConfig.setPassword(properties.determinePassword());
    String driverClassName = properties.determineDriverClassName();
    if (hikariConfig.getDriverClassName() == null || hikariConfig.getDriverClassName().isBlank()) {
      hikariConfig.setDriverClassName(driverClassName);
    }
    return new HikariDataSource(hikariConfig);
  }

  @Bean(name = "importPlatformSqlSessionFactory")
  public SqlSessionFactory importPlatformSqlSessionFactory(
      @Qualifier("importPlatformDataSource") DataSource dataSource) throws Exception {
    SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
    factoryBean.setDataSource(dataSource);
    Resource[] platformMappers =
        new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml");
    if (platformMappers.length > 0) {
      factoryBean.setMapperLocations(platformMappers);
    }
    org.apache.ibatis.session.Configuration configuration =
        new org.apache.ibatis.session.Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    factoryBean.setConfiguration(configuration);
    return factoryBean.getObject();
  }

  @Bean(name = "importPlatformSqlSessionTemplate")
  public SqlSessionTemplate importPlatformSqlSessionTemplate(
      @Qualifier("importPlatformSqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
    return new SqlSessionTemplate(sqlSessionFactory);
  }
}

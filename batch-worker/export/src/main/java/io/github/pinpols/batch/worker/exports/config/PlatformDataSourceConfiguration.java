package io.github.pinpols.batch.worker.exports.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.pinpols.batch.common.config.BatchPgSessionProperties;
import io.github.pinpols.batch.common.config.HikariPgSessionSupport;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
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
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** 平台数据源配置，提供导出 Worker 所需的平台库 MyBatis SqlSession（Primary）。 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
@RequiredArgsConstructor
public class PlatformDataSourceConfiguration {

  private final BatchPgSessionProperties pgSessionProperties;
  private final Environment environment;

  @Bean(name = "exportPlatformHikariConfig")
  @ConfigurationProperties("spring.datasource.hikari")
  public HikariConfig exportPlatformHikariConfig() {
    return new HikariConfig();
  }

  @Bean(name = "exportPlatformDataSource")
  @Primary
  public DataSource exportPlatformDataSource(
      DataSourceProperties properties,
      @Qualifier("exportPlatformHikariConfig") HikariConfig hikariConfig) {
    hikariConfig.setJdbcUrl(properties.determineUrl());
    hikariConfig.setUsername(properties.determineUsername());
    hikariConfig.setPassword(properties.determinePassword());
    String driverClassName = properties.determineDriverClassName();
    if (hikariConfig.getDriverClassName() == null || hikariConfig.getDriverClassName().isBlank()) {
      hikariConfig.setDriverClassName(driverClassName);
    }
    String appName = environment.getProperty("spring.application.name", "batch-worker-export");
    HikariPgSessionSupport.applyPlatform(hikariConfig, pgSessionProperties, appName + "-platform");
    return new HikariDataSource(hikariConfig);
  }

  @Bean(name = "exportPlatformSqlSessionFactory")
  public SqlSessionFactory exportPlatformSqlSessionFactory(
      @Qualifier("exportPlatformDataSource") DataSource dataSource) throws Exception {
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

  @Bean(name = "exportPlatformSqlSessionTemplate")
  public SqlSessionTemplate exportPlatformSqlSessionTemplate(
      @Qualifier("exportPlatformSqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
    return new SqlSessionTemplate(sqlSessionFactory);
  }
}

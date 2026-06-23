package io.github.pinpols.batch.e2e.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class E2eExportWorkerDataSourceConfiguration {

  @Bean(name = "exportPlatformDataSource")
  public DataSource exportPlatformDataSource(@Qualifier("dataSource") DataSource dataSource) {
    return dataSource;
  }

  @Bean(name = "exportBusinessDataSource")
  public DataSource exportBusinessDataSource(@Qualifier("dataSource") DataSource dataSource) {
    return dataSource;
  }
}

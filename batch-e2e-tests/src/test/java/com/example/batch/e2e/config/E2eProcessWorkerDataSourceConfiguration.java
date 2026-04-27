package com.example.batch.e2e.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class E2eProcessWorkerDataSourceConfiguration {

  @Bean(name = "processPlatformDataSource")
  public DataSource processPlatformDataSource(@Qualifier("dataSource") DataSource dataSource) {
    return dataSource;
  }

  @Bean(name = "processBusinessDataSource")
  public DataSource processBusinessDataSource(@Qualifier("dataSource") DataSource dataSource) {
    return dataSource;
  }
}

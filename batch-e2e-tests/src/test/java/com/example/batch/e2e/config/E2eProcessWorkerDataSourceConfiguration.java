package com.example.batch.e2e.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

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

  @Bean(name = "transactionManager")
  @Primary
  public DataSourceTransactionManager transactionManager(
      @Qualifier("dataSource") DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  @Bean(name = "processBusinessTransactionManager")
  public DataSourceTransactionManager processBusinessTransactionManager(
      @Qualifier("processBusinessDataSource") DataSource processBusinessDataSource) {
    return new DataSourceTransactionManager(processBusinessDataSource);
  }
}

package io.github.pinpols.batch.e2e.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Configuration(proxyBeanMethods = false)
public class E2eExportWorkerDataSourceConfiguration {

  @Bean(name = "exportPlatformDataSource")
  public DataSource exportPlatformDataSource(@Qualifier("dataSource") DataSource dataSource) {
    return dataSource;
  }

  @Bean(name = "exportBusinessDataSource")
  public DataSource exportBusinessDataSource(
      @Qualifier("e2eBusinessDataSource") DataSource dataSource) {
    return dataSource;
  }

  /** 多数据源 E2E 切片需要显式的平台事务管理器。 */
  @Bean(name = "transactionManager")
  @ConditionalOnMissingBean(name = "transactionManager")
  public DataSourceTransactionManager transactionManager(
      @Qualifier("dataSource") DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }
}

package io.github.pinpols.batch.e2e.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

  /**
   * Boot JDBC 通常会注册同名 bean；缺失时（Process E2E 裁剪 auto-config）回退。{@link
   * #processBusinessTransactionManager} 标记 {@code autowireCandidate=false}，避免按类型注入 {@code
   * TransactionManager} 时出现双候选。
   */
  @Bean(name = "transactionManager")
  @ConditionalOnMissingBean(name = "transactionManager")
  public DataSourceTransactionManager transactionManager(
      @Qualifier("dataSource") DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  @Bean(name = "processBusinessTransactionManager", autowireCandidate = false)
  public DataSourceTransactionManager processBusinessTransactionManager(
      @Qualifier("processBusinessDataSource") DataSource processBusinessDataSource) {
    return new DataSourceTransactionManager(processBusinessDataSource);
  }
}

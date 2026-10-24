package io.github.pinpols.batch.orchestrator.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.pinpols.batch.common.config.ApplicationNameProvider;
import io.github.pinpols.batch.common.config.BatchPgSessionProperties;
import io.github.pinpols.batch.common.config.BusinessDataSourceBuilder;
import io.github.pinpols.batch.common.config.BusinessDataSourceProperties;
import io.github.pinpols.batch.common.config.BusinessRoutingProperties;
import io.github.pinpols.batch.common.config.HikariPgSessionSupport;
import io.github.pinpols.batch.common.mapper.BusinessTenantPlacementMapper;
import io.github.pinpols.batch.common.tenant.routing.MyBatisTenantPlacementRepository;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** DB_ROW_EXISTS 所需的租户路由业务库连接及只读事务。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "batch.datasource.business", name = "url")
@EnableConfigurationProperties({
  DataSourceProperties.class,
  BusinessDataSourceProperties.class,
  BusinessRoutingProperties.class
})
public class WorkflowSensorDataSourceConfiguration {

  private final BatchPgSessionProperties pgSessionProperties;
  private final Environment environment;

  public WorkflowSensorDataSourceConfiguration(
      BatchPgSessionProperties pgSessionProperties, Environment environment) {
    this.pgSessionProperties = pgSessionProperties;
    this.environment = environment;
  }

  @Bean(name = "orchestratorPlatformHikariConfig")
  @ConfigurationProperties("spring.datasource.hikari")
  public HikariConfig orchestratorPlatformHikariConfig() {
    return new HikariConfig();
  }

  /** 将现有 Boot 平台数据源保留为主 {@code dataSource} Bean。 */
  @Bean(name = "orchestratorPlatformDataSource", destroyMethod = "close")
  @Primary
  public HikariDataSource dataSource(
      DataSourceProperties properties,
      @Qualifier("orchestratorPlatformHikariConfig") HikariConfig hikariConfig) {
    hikariConfig.setJdbcUrl(properties.determineUrl());
    hikariConfig.setUsername(properties.determineUsername());
    hikariConfig.setPassword(properties.determinePassword());
    if (EmptyChecks.isNull(hikariConfig.getDriverClassName())
        || EmptyChecks.isBlank(Objects.requireNonNull(hikariConfig.getDriverClassName()))) {
      hikariConfig.setDriverClassName(properties.determineDriverClassName());
    }
    String applicationName = ApplicationNameProvider.resolve(environment, "batch-orchestrator");
    HikariPgSessionSupport.applyPlatform(
        hikariConfig, pgSessionProperties, applicationName + "-platform");
    return new HikariDataSource(hikariConfig);
  }

  @Bean(name = "workflowSensorBusinessHikariConfig")
  @ConfigurationProperties("batch.datasource.business.hikari")
  public HikariConfig workflowSensorBusinessHikariConfig() {
    return new HikariConfig();
  }

  @Bean(name = "orchestratorBusinessDataSource", destroyMethod = "close")
  public DataSource orchestratorBusinessDataSource(
      BusinessDataSourceProperties properties,
      BusinessRoutingProperties routingProperties,
      BusinessTenantPlacementMapper placementMapper,
      @Qualifier("workflowSensorBusinessHikariConfig") HikariConfig hikariConfig) {
    MyBatisTenantPlacementRepository tenantPlacementRepository =
        new MyBatisTenantPlacementRepository(placementMapper);
    String applicationName = ApplicationNameProvider.resolve(environment, "batch-orchestrator");
    return BusinessDataSourceBuilder.build(
        hikariConfig,
        properties,
        pgSessionProperties,
        routingProperties,
        tenantPlacementRepository,
        applicationName);
  }

  @Bean(name = "workflowSensorBusinessTransactionTemplate")
  public TransactionTemplate workflowSensorBusinessTransactionTemplate(
      @Qualifier("orchestratorBusinessDataSource") DataSource dataSource) {
    DataSourceTransactionManager transactionManager =
        new DataSourceTransactionManager(dataSource);
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setReadOnly(true);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }
}

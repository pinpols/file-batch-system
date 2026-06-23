package io.github.pinpols.batch.worker.core.reportoutbox;

import io.github.pinpols.batch.worker.core.infrastructure.OrchestratorReportHttpSubmitter;
import io.github.pinpols.batch.worker.core.infrastructure.WorkerTaskLeaseRenewer;
import io.github.pinpols.batch.worker.core.mapper.WorkerReportOutboxPgMapper;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.WorkerReportOutboxSqliteMapper;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.WorkerReportOutboxSqliteSessionFactorySupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "batch.worker.report-outbox",
    name = "enabled",
    havingValue = "true")
public class WorkerReportOutboxConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      prefix = "batch.worker.report-outbox",
      name = "storage",
      havingValue = "SQLITE")
  static class SqliteWorkerReportOutboxConfiguration {

    @Bean(name = "workerReportOutboxDataSource")
    DataSource workerReportOutboxDataSource(WorkerReportOutboxProperties props) throws Exception {
      Path path = props.resolveSqlitePath();
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      DriverManagerDataSource ds = new DriverManagerDataSource();
      ds.setDriverClassName("org.sqlite.JDBC");
      ds.setUrl("jdbc:sqlite:" + path.toAbsolutePath());
      return ds;
    }

    @Bean(name = "workerReportOutboxJdbcTemplate")
    JdbcTemplate workerReportOutboxJdbcTemplate(
        @Qualifier("workerReportOutboxDataSource") DataSource dataSource) {
      return new JdbcTemplate(dataSource);
    }

    @Bean(name = "workerReportOutboxTransactionManager")
    PlatformTransactionManager workerReportOutboxTransactionManager(
        @Qualifier("workerReportOutboxDataSource") DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean(name = "workerReportOutboxTransactionTemplate")
    TransactionTemplate workerReportOutboxTransactionTemplate(
        @Qualifier("workerReportOutboxTransactionManager")
            PlatformTransactionManager transactionManager) {
      TransactionTemplate tt = new TransactionTemplate(transactionManager);
      tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      return tt;
    }

    @Bean(name = "workerReportOutboxSqliteSqlSessionFactory")
    SqlSessionFactory workerReportOutboxSqliteSqlSessionFactory(
        @Qualifier("workerReportOutboxDataSource") DataSource dataSource) throws Exception {
      return WorkerReportOutboxSqliteSessionFactorySupport.createSqlSessionFactory(dataSource);
    }

    @Bean(name = "workerReportOutboxSqliteSqlSessionTemplate")
    SqlSessionTemplate workerReportOutboxSqliteSqlSessionTemplate(
        @Qualifier("workerReportOutboxSqliteSqlSessionFactory")
            SqlSessionFactory sqlSessionFactory) {
      return new SqlSessionTemplate(sqlSessionFactory);
    }

    @Bean
    WorkerReportOutboxSqliteMapper workerReportOutboxSqliteMapper(
        @Qualifier("workerReportOutboxSqliteSqlSessionTemplate")
            SqlSessionTemplate sqlSessionTemplate) {
      return sqlSessionTemplate.getMapper(WorkerReportOutboxSqliteMapper.class);
    }

    @Bean
    WorkerReportOutboxRepository workerReportOutboxRepository(
        WorkerReportOutboxSqliteMapper sqliteMapper,
        @Qualifier("workerReportOutboxJdbcTemplate") JdbcTemplate jdbcTemplate,
        WorkerReportOutboxProperties props) {
      return new WorkerReportOutboxRepository(
          props, WorkerReportOutboxDialect.SQLITE, null, sqliteMapper, jdbcTemplate);
    }

    @Bean
    WorkerReportOutboxPollClaimer workerReportOutboxPollClaimer(
        WorkerReportOutboxRepository workerReportOutboxRepository,
        @Qualifier("workerReportOutboxTransactionTemplate")
            TransactionTemplate transactionTemplate) {
      return new WorkerReportOutboxPollClaimer(workerReportOutboxRepository, transactionTemplate);
    }

    @Bean
    WorkerReportOutboxCoordinator workerReportOutboxCoordinator(
        WorkerReportOutboxRepository repository,
        WorkerReportOutboxProperties props,
        @Lazy OrchestratorReportHttpSubmitter httpSubmitter,
        ObjectProvider<MeterRegistry> meterRegistryProvider,
        ObjectProvider<WorkerTaskLeaseRenewer> leaseRenewerProvider,
        WorkerReportOutboxPollClaimer pollClaimer) {
      return new WorkerReportOutboxCoordinator(
          repository,
          props,
          httpSubmitter,
          meterRegistryProvider,
          leaseRenewerProvider,
          pollClaimer);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      prefix = "batch.worker.report-outbox",
      name = "storage",
      havingValue = "PLATFORM_PG",
      matchIfMissing = true)
  static class PlatformPgWorkerReportOutboxConfiguration {

    @Bean(name = "workerReportOutboxTransactionTemplate")
    TransactionTemplate workerReportOutboxTransactionTemplate(
        @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
      TransactionTemplate tt = new TransactionTemplate(transactionManager);
      tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      return tt;
    }

    @Bean
    WorkerReportOutboxRepository workerReportOutboxRepository(
        WorkerReportOutboxPgMapper pgMapper, WorkerReportOutboxProperties props) {
      return new WorkerReportOutboxRepository(
          props, WorkerReportOutboxDialect.POSTGRESQL, pgMapper, null, null);
    }

    @Bean
    WorkerReportOutboxPollClaimer workerReportOutboxPollClaimer(
        WorkerReportOutboxRepository workerReportOutboxRepository,
        @Qualifier("workerReportOutboxTransactionTemplate")
            TransactionTemplate transactionTemplate) {
      return new WorkerReportOutboxPollClaimer(workerReportOutboxRepository, transactionTemplate);
    }

    @Bean
    WorkerReportOutboxCoordinator workerReportOutboxCoordinator(
        WorkerReportOutboxRepository repository,
        WorkerReportOutboxProperties props,
        @Lazy OrchestratorReportHttpSubmitter httpSubmitter,
        ObjectProvider<MeterRegistry> meterRegistryProvider,
        ObjectProvider<WorkerTaskLeaseRenewer> leaseRenewerProvider,
        WorkerReportOutboxPollClaimer pollClaimer) {
      return new WorkerReportOutboxCoordinator(
          repository,
          props,
          httpSubmitter,
          meterRegistryProvider,
          leaseRenewerProvider,
          pollClaimer);
    }
  }
}

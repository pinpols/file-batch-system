package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.ItemStats;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import io.github.pinpols.batch.console.config.JobMonitoringDefaultsProperties;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.param.FileChannelConfigUpsertParam;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionVersionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.infrastructure.workflow.DefaultSingleJobWorkflowProvisioner;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionDagInspector;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionWriteSupport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** 在真实 PostgreSQL 的事务完成边界注入故障，核对统计与持久化事实及后续目标执行。 */
@DisplayName("租户配置批量初始化的事务完成与统计一致性")
@ExtendWith(MockitoExtension.class)
class TenantConfigInitCommitOutcomeIntegrationTest extends AbstractIntegrationTest {

  @Mock
  private TenantConfigInitTenantExecutor executor;

  @Test
  @DisplayName("单项 savepoint 已回滚时只计失败，后续配置正常提交且统计与库中事实一致")
  void shouldCountOnlyCommittedItems_whenNestedItemRollsBackAtCompletion() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        PLATFORM_POSTGRES.getJdbcUrl(),
        PLATFORM_POSTGRES.getUsername(),
        PLATFORM_POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String table =
        "public.config_init_item_probe_" + UUID.randomUUID().toString().replace("-", "");
    String createSql = "create table %s (code varchar(64) primary key)".formatted(table);
    String insertSql = "insert into %s (code) values (?)".formatted(table);
    String selectSql = "select code from %s order by code".formatted(table);
    String dropSql = "drop table %s".formatted(table);
    DataSourceTransactionManager delegate = new DataSourceTransactionManager(dataSource);
    AtomicBoolean rollbackFirstItem = new AtomicBoolean(true);
    PlatformTransactionManager manager = new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return delegate.getTransaction(definition);
      }

      @Override
      public void commit(TransactionStatus status) {
        if (status.hasSavepoint() && rollbackFirstItem.compareAndSet(true, false)) {
          delegate.rollback(status);
          throw new UnexpectedRollbackException("item transaction rolled back");
        }
        delegate.commit(status);
      }

      @Override
      public void rollback(TransactionStatus status) {
        delegate.rollback(status);
      }
    };
    FileChannelConfigMapper channels = mock(FileChannelConfigMapper.class);
    when(channels.upsertFileChannelConfig(any())).thenAnswer(invocation -> {
      FileChannelConfigUpsertParam parameters = invocation.getArgument(0);
      return jdbc.update(insertSql, parameters.getChannelCode());
    });
    TenantConfigInitApplyHandlers handlers = fileHandlers(manager, channels);
    FileChannelSpec first = new FileChannelSpec();
    first.setChannelCode("first");
    FileChannelSpec second = new FileChannelSpec();
    second.setChannelCode("second");
    TenantConfigInitApplyHandlers.ApplyContext context =
        new TenantConfigInitApplyHandlers.ApplyContext(
            "tenant-a", TenantConfigBatchInitRequest.InitMode.SKIP_EXISTING, "admin", false);
    TransactionTemplate outer = new TransactionTemplate(manager);
    jdbc.execute(createSql);
    try {
      ItemStats stats =
          outer.execute(status -> handlers.applyFileChannels(List.of(first, second), context));

      assertThat(jdbc.queryForList(selectSql, String.class)).containsExactly("second");
      assertThat(stats.created()).isEqualTo(1);
      assertThat(stats.failed()).isEqualTo(1);
      assertThat(stats.details()).hasSize(2);
    } finally {
      jdbc.execute(dropSql);
    }
  }

  private static TenantConfigInitApplyHandlers fileHandlers(
      PlatformTransactionManager manager, FileChannelConfigMapper channels) {
    JobDefinitionMapper jobs = mock(JobDefinitionMapper.class);
    WorkflowDefinitionMapper workflows = mock(WorkflowDefinitionMapper.class);
    WorkflowNodeMapper nodes = mock(WorkflowNodeMapper.class);
    WorkflowEdgeMapper edges = mock(WorkflowEdgeMapper.class);
    TenantDefinitionConfigMappers definitions =
        new TenantDefinitionConfigMappers(jobs, workflows, nodes, edges);
    TenantFileConfigApplySupport files = new TenantFileConfigApplySupport(
        channels, mock(FileTemplateConfigMapper.class), new ObjectMapper());
    TenantOperationalConfigApplySupport operational = new TenantOperationalConfigApplySupport(
        mock(ResourceQueueMapper.class), mock(BatchWindowMapper.class),
        mock(BusinessCalendarMapper.class), mock(CalendarHolidayMapper.class),
        mock(TenantQuotaPolicyMapper.class), mock(AlertRoutingConfigMapper.class));
    WorkflowDefinitionWriteSupport snapshots = new WorkflowDefinitionWriteSupport(
        nodes, edges, mock(WorkflowDefinitionVersionMapper.class), new ObjectMapper());
    JobMonitoringDefaultsProperties defaults = new JobMonitoringDefaultsProperties();
    DefaultSingleJobWorkflowProvisioner provisioner =
        mock(DefaultSingleJobWorkflowProvisioner.class);
    TenantJobDefinitionApplySupport jobSupport =
        new TenantJobDefinitionApplySupport(jobs, defaults, provisioner);
    WorkflowDefinitionDagInspector inspector = new WorkflowDefinitionDagInspector(jobs);
    TenantWorkflowDefinitionApplySupport workflowSupport =
        new TenantWorkflowDefinitionApplySupport(definitions, snapshots, inspector);
    return new TenantConfigInitApplyHandlers(
        jobSupport, manager, files, operational, workflowSupport);
  }

  @Test
  @DisplayName("目标事务已提交但确认异常时，保留已提交事实并停止后续目标")
  void shouldPropagateUnknownOutcome_whenPostgresCommittedBeforeAcknowledgementFailure() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        PLATFORM_POSTGRES.getJdbcUrl(),
        PLATFORM_POSTGRES.getUsername(),
        PLATFORM_POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String table = "public.config_init_commit_probe_" + suffix;
    String createSql = """
        create table %s (tenant_id varchar(64) primary key)
        """.formatted(table);
    String insertSql = """
        insert into %s (tenant_id) values (?)
        """.formatted(table);
    String selectSql = """
        select tenant_id from %s order by tenant_id
        """.formatted(table);
    String dropSql = """
        drop table %s
        """.formatted(table);
    TransactionSystemException acknowledgementFailure =
        new TransactionSystemException("commit acknowledgement unavailable");
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource) {
      @Override
      protected void doCommit(DefaultTransactionStatus status) {
        super.doCommit(status);
        // 数据库提交是真实行为；故障仅注入在确认返回边界，不把回滚模拟成未知结果。
        throw acknowledgementFailure;
      }
    };
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    when(executor.execute(any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
      String tenantId = invocation.getArgument(0);
      return transaction.execute(status -> {
        jdbc.update(insertSql, tenantId);
        return TenantInitResult.builder().tenantId(tenantId).success(true).build();
      });
    });
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of("first", "second"));
    DefaultConsoleTenantConfigInitApplicationService service =
        new DefaultConsoleTenantConfigInitApplicationService(executor);
    jdbc.execute(createSql);
    try {
      assertThatThrownBy(() -> service.batchInit(request, "admin", "commit-unknown"))
          .isSameAs(acknowledgementFailure);
      List<String> committedTenants = jdbc.queryForList(selectSql, String.class);
      assertThat(committedTenants).containsExactly("first");
    } finally {
      jdbc.execute(dropSql);
    }
  }
}

package io.github.pinpols.batch.orchestrator.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyWaitBacklog;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("依赖等待指标查询设置事务级租户 RLS")
class JobDependencyWaitMetricsQueryTest {

  @AfterEach
  void clearTenantContext() {
    RlsTenantContextHolder.clear();
  }

  @DisplayName("查询依赖积压前设置租户会话")
  @Test
  void shouldSetTenantSessionBeforeQueryingBacklog() throws Exception {
    final String selectStatementSql1 = "SELECT set_config(?, ?, true)";
    String tenantId = "tenant-a";
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    JobDependencyWaitingLaunchMapper mapper = mock(JobDependencyWaitingLaunchMapper.class);
    JobDependencyWaitBacklog expected = new JobDependencyWaitBacklog(2L, 60L);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(selectStatementSql1)).thenReturn(statement);
    when(mapper.selectBacklogMetrics(tenantId)).thenReturn(expected);
    RlsTenantContextHolder.set(tenantId);

    JobDependencyWaitBacklog result =
        new JobDependencyWaitMetricsQuery(mapper, dataSource).selectForTenant(tenantId);

    assertThat(result).isSameAs(expected);
    verify(statement).setString(1, "app.tenant_id");
    verify(statement).setString(2, tenantId);
    verify(statement).execute();
    verify(mapper).selectBacklogMetrics(tenantId);
  }
}

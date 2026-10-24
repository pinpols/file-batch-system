package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Replay operation PostgreSQL mapper: 父子身份和终态汇总")
class ReplayOperationMapperIntegrationTest {

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();

  @Test
  @DisplayName("operation mapper 能创建子 session 并从 PostgreSQL 聚合终态")
  void shouldPersistParentLinkAndAggregateChildTerminalState() throws Exception {
    Flyway flyway = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load();
    flyway.migrate();

    try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
      String tenantId = "replay-mapper-test";
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      final String setTenantContextSql = "select set_config('app.tenant_id', ?, false)";
      final String updateChildStatusSql = """
          update batch.batch_day_replay_session
          set status = 'SUCCEEDED'
          where tenant_id = ? and id = ?
          """;
      String appliedTenantContext = jdbc.queryForObject(
          setTenantContextSql, String.class, tenantId);
      assertThat(appliedTenantContext).isEqualTo(tenantId);
      SqlSessionFactory sessionFactory = sqlSessionFactory(dataSource);
      try (SqlSession sqlSession = sessionFactory.openSession()) {
        ReplayOperationMapper operationMapper = sqlSession.getMapper(ReplayOperationMapper.class);
        BatchDayReplaySessionMapper childMapper = sqlSession.getMapper(BatchDayReplaySessionMapper.class);
        Instant now = Instant.parse("2026-10-22T12:00:00Z");
        String operationKey = UUID.randomUUID().toString();
        ReplayOperationEntity operation = ReplayOperationEntity.builder()
            .tenantId(tenantId)
            .operationKey(operationKey)
            .operationType("BATCH_DAY_RANGE")
            .status("PENDING_APPROVAL")
            .calendarCode("CAL-TEST")
            .fromDate(LocalDate.of(2026, 10, 20))
            .throughDate(LocalDate.of(2026, 10, 22))
            .scope("ALL_FAILED")
            .executionMode("REPLAY")
            .candidateSource("EXISTING_INSTANCES")
            .scopePayload("{}")
            .requestHash("a".repeat(64))
            .snapshotHash("b".repeat(64))
            .snapshotPayload("[]")
            .reason("integration")
            .requestedBy("operator")
            .totalCount(1)
            .succeededCount(0)
            .failedCount(0)
            .inFlightCount(1)
            .createdAt(now)
            .updatedAt(now)
            .build();
        operationMapper.insert(operation);
        ReplayOperationEntity persistedOperation = operationMapper.selectByIdentity(tenantId, operationKey);
        assertThat(persistedOperation).isNotNull();

        BatchDayReplaySessionEntity child = BatchDayReplaySessionEntity.builder()
            .tenantId(tenantId)
            .replayOperationId(persistedOperation.id())
            .calendarCode("CAL-TEST")
            .bizDate(LocalDate.of(2026, 10, 20))
            .scope("ALL_FAILED")
            .executionMode("REPLAY")
            .candidateSource("EXISTING_INSTANCES")
            .scopePayload("{}")
            .resultPolicy("CREATE_NEW_VERSION")
            .configVersionPolicy("USE_ORIGINAL_CONFIG")
            .reason("integration")
            .status("PENDING_APPROVAL")
            .totalCount(1)
            .succeededCount(0)
            .failedCount(0)
            .inFlightCount(1)
            .requestedBy("operator")
            .createdAt(now)
            .updatedAt(now)
            .build();
        childMapper.insert(child);
        BatchDayReplaySessionEntity persistedChild = childMapper.selectActiveByCalendarBizDate(
            tenantId, "CAL-TEST", LocalDate.of(2026, 10, 20));
        assertThat(persistedChild).isNotNull();
        assertThat(persistedChild.replayOperationId()).isEqualTo(persistedOperation.id());
        assertThat(operationMapper.selectChildSessionIds(tenantId, persistedOperation.id()))
            .containsExactly(persistedChild.id());

        int updatedChildCount = jdbc.update(updateChildStatusSql, tenantId, persistedChild.id());
        assertThat(updatedChildCount).isEqualTo(1);
        assertThat(operationMapper.refreshFromChildSessions(tenantId, persistedOperation.id(), now.plusSeconds(1)))
            .isEqualTo(1);
        ReplayOperationEntity completedOperation = operationMapper.selectById(tenantId, persistedOperation.id());
        assertThat(completedOperation.status()).isEqualTo("SUCCEEDED");
        assertThat(completedOperation.totalCount()).isEqualTo(1);
        assertThat(completedOperation.succeededCount()).isEqualTo(1);
        assertThat(completedOperation.inFlightCount()).isZero();
      }
    }
  }

  private static SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
    Configuration configuration = new Configuration(new Environment(
        "test", new JdbcTransactionFactory(), dataSource));
    parse(configuration, "/mapper/ReplayOperationMapper.xml");
    parse(configuration, "/mapper/ReplayOperationPreviewTokenMapper.xml");
    parse(configuration, "/mapper/BatchDayReplaySessionMapper.xml");
    return new SqlSessionFactoryBuilder().build(configuration);
  }

  private static void parse(Configuration configuration, String resource) throws Exception {
    try (InputStream input = ReplayOperationMapperIntegrationTest.class.getResourceAsStream(resource)) {
      XMLMapperBuilder parser = new XMLMapperBuilder(
          input, configuration, resource, configuration.getSqlFragments());
      parser.parse();
    }
  }
}

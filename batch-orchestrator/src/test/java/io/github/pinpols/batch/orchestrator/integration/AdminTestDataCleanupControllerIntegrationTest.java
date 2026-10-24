package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper.CleanupTarget;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/** Orchestrator 内部测试数据清理接口的真 DB 验证。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true"})
@DisplayName("内部测试数据清理接口的真库验证:按前缀清理只命中受试数据,按租户标识清理覆盖全部映射删除目标")
class AdminTestDataCleanupControllerIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort
  private int port;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @MockitoSpyBean
  private AdminTestDataCleanupMapper cleanupMapper;

  private RestClient restClient;
  private final List<CleanupFixtureIdentity> cleanupFixtureIdentities = new ArrayList<>();
  private final List<CleanupInstanceIdentity> cleanupInstanceIdentities = new ArrayList<>();
  private final List<CleanupInstanceIdentity> cleanupColdInstanceIdentities = new ArrayList<>();
  private static final LocalDate CLEANUP_BIZ_DATE = LocalDate.of(2094, 4, 3);

  private static final String PREFIX = "itadmin";
  private static final String NEIGHBOR = "itadmin2-job";

  @BeforeEach
  void setUp() {
    restClient = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    cleanup();
    seed();
  }

  @AfterEach
  void tearDown() {
    // 历史 NodeRun 仅存数字 ID；先清本测试的节点引用，再精确删除跨日重号实例。
    for (CleanupFixtureIdentity identity : cleanupFixtureIdentities) {
      cleanupOwnedWorkflowNodeRuns(identity);
    }
    for (CleanupFixtureIdentity identity : cleanupFixtureIdentities) {
      cleanupOwnedWorkflowFixture(identity);
    }
    cleanupFixtureIdentities.clear();
    cleanupInstanceIdentities.clear();
    cleanupColdInstanceIdentities.clear();
    cleanup();
  }

  private void cleanup() {
    final String deleteWorkflowDefinitionSql = """
        DELETE FROM batch.workflow_definition WHERE workflow_code LIKE 'itadmin%%'\s\
        OR single_job_definition_id IN (SELECT id FROM batch.job_definition WHERE job_code LIKE 'itadmin%%'\s\
        OR job_code LIKE 'it_admin%%')\
        """;
    final String deleteJobDefinitionSql = """
        DELETE FROM batch.job_definition WHERE job_code LIKE 'itadmin%%' OR job_code LIKE 'it_admin%%'\
        """;
    final String deleteConsoleUserAccountSql = """
        DELETE FROM batch.console_user_account WHERE username LIKE 'itadmin%%' OR username LIKE 'op-itadmin%%'\
        """;

    jdbcTemplate.update(
        deleteWorkflowDefinitionSql);
    jdbcTemplate.update(
        deleteJobDefinitionSql);
    jdbcTemplate.update(
        deleteConsoleUserAccountSql);
  }

  private void seed() {
  final String insertJobDefinitionSql = """
      INSERT INTO batch.job_definition (tenant_id, job_code, job_name, job_type, biz_type, schedule_type,\s\
      timezone, trigger_mode, queue_code, worker_group, window_code, priority, enabled, created_at, updated_at)\s\
      VALUES ('t1','itadmin2-job','邻近不该被删','GENERAL','TEST','MANUAL','Asia/Shanghai',\s\
      'SCHEDULED','q','IMPORT','always_open',5,true,now(),now())\
      """;
  final String insertJobDefinitionSql2 = """
      INSERT INTO batch.job_definition (tenant_id, job_code, job_name, job_type, biz_type, schedule_type,\s\
      timezone, trigger_mode, queue_code, worker_group, window_code, priority, enabled, created_at, updated_at)\s\
      VALUES ('t1','itadmin-job1','受试','GENERAL','TEST','MANUAL','Asia/Shanghai',\s\
      'SCHEDULED','q','IMPORT','always_open',5,true,now(),now())\
      """;
  final String insertWorkflowDefinitionSql = """
      INSERT INTO batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
      enabled, created_at, updated_at) VALUES ('t1','itadmin-wf1','受试 WF','DAG',1,true,now(),now())\
      """;
  final String selectIdSql = """
      SELECT id FROM batch.job_definition WHERE tenant_id='t1' AND job_code='itadmin-job1'
  """;
  final String insertWorkflowDefinitionSql2 = """
      INSERT INTO batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type,\s\
      single_job_definition_id, version, enabled, created_at, updated_at)\s\
      VALUES ('t1','managed-itadmin-job1','受管单作业 WF','DAG',?,1,true,now(),now())\
      """;

    jdbcTemplate.update(
        insertJobDefinitionSql);
    jdbcTemplate.update(
        insertJobDefinitionSql2);
    jdbcTemplate.update(
        insertWorkflowDefinitionSql);
    Long managedJobId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class);
    jdbcTemplate.update(
        insertWorkflowDefinitionSql2,
        managedJobId);
  }

  @Test
  @DisplayName("按前缀清理只删除连字符前缀的作业与流程定义,邻近的同名前缀作业保留")
  void shouldDeleteOnlyHyphenPrefixedRecords_whenCleaningByPrefix() {
  final String selectCountSql = """
      SELECT count(*)::int FROM batch.job_definition WHERE job_code LIKE 'itadmin-%'
  """;
  final String selectCountSql2 = """
      SELECT count(*)::int FROM batch.job_definition WHERE job_code = ?
  """;
  final String selectCountSql3 = """
      SELECT count(*)::int FROM batch.workflow_definition WHERE workflow_code LIKE 'itadmin-%%'\
      """;
  final String selectCountSql4 = """
      SELECT count(*)::int FROM batch.workflow_definition WHERE workflow_code='managed-itadmin-job1'\
      """;

    Integer selectedCount = jdbcTemplate.queryForObject(selectCountSql, Integer.class);
    assertThat(selectedCount).isEqualTo(1);
    Integer selectedCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, NEIGHBOR);
    assertThat(selectedCountRead2).isEqualTo(1);

    Map<String, Integer> response = restClient
        .delete()
        .uri("/internal/admin/test-data?prefix=" + PREFIX)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
    assertThat(response)
        .containsEntry("job_definition", 1)
        .containsEntry("workflow_definition", 2);

    Integer selectedCountRead3 = jdbcTemplate.queryForObject(selectCountSql, Integer.class);
    assertThat(selectedCountRead3).isZero();
    Integer selectedCountRead4 = jdbcTemplate.queryForObject(selectCountSql3, Integer.class);
    assertThat(selectedCountRead4).isZero();
    Integer selectedCountRead5 = jdbcTemplate.queryForObject(selectCountSql4, Integer.class);
    assertThat(selectedCountRead5).isZero();
    Integer selectedCountRead6 =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, NEIGHBOR);
    assertThat(selectedCountRead6).isEqualTo(1);
  }

  @Test
  @DisplayName("按租户标识清理返回全部映射的删除目标且各目标命中数为零,不返回内部父级清理步骤")
  void shouldExecuteEveryMappedDeletionTarget_whenCleaningByTenantIds() {
    String tenantId = "itcleanup_" + System.nanoTime();

    Map<String, Integer> response = restClient
        .delete()
        .uri("/internal/admin/test-data/by-ids?ids=" + tenantId)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});

    assertThat(response)
        .containsKeys(
            "workflow_node_run",
            "pipeline_step_run",
            "job_instance",
            "file_record",
            "pipeline_instance",
            "tenant")
        .doesNotContainKeys(
            "clear_job_instance_parent", "pipeline_definition", "pipeline_step_definition");
    assertThat(response.values()).containsOnly(0);
  }

  @Test
  @DisplayName("精确租户清理删除新模型运行请求与等待闭包，保留同请求号邻居并可重复执行")
  void shouldDeleteWorkflowClosure_whenCleaningExactTenant() {
    String suffix = Long.toUnsignedString(System.nanoTime(), 36);
    String tenantId = "itexact_" + suffix;
    List<String> requestIds = newCleanupRequestIds();
    WorkflowCleanupFixture owned = seedCleanupWorkflowFixture("itexact" + suffix, tenantId, requestIds);
    WorkflowCleanupFixture neighbor = seedCleanupWorkflowFixture("itneighbor" + suffix, "t1", requestIds);
    CollisionNeighbor sameIdParent = seedCollisionNeighbor(owned, "t1");
    // 邻居自己的父实例恰好重用所选租户的数字 ID，清理不得清空它的有效父引用。
    String setNeighborParentSql = """
        update batch.job_instance set parent_instance_id = ?
        where tenant_id = ? and id = ? and biz_date = ?
        """;
    Long parentId = sameIdParent.instance().id();
    jdbcTemplate.update(setNeighborParentSql, parentId, "t1", neighbor.jobInstanceId(), CLEANUP_BIZ_DATE);
    Map<String, Object> parentBefore = readCollisionNeighborRows(sameIdParent);
    Map<String, Object> neighborBefore = readCleanupFixtureRows(neighbor);
    assertCleanupFixtureCounts(owned, true);

    Map<String, Integer> response = cleanupExactTenantThroughApi(tenantId);

    assertThat(response)
        .containsEntry("workflow_definition", 1)
        .containsEntry("trigger_request", 2)
        .containsEntry("trigger_outbox_event", 2)
        .containsEntry("outbox_event", 1)
        .containsEntry("workflow_launch_wait", 1);
    assertCleanupFixtureCounts(owned, false);
    assertCleanupFixtureCounts(neighbor, true);
    Map<String, Object> neighborAfter = readCleanupFixtureRows(neighbor);
    assertThat(neighborAfter).isEqualTo(neighborBefore);
    Map<String, Object> parentAfter = readCollisionNeighborRows(sameIdParent);
    assertThat(parentAfter).isEqualTo(parentBefore);
    Map<String, Integer> repeated = cleanupExactTenantThroughApi(tenantId);
    assertThat(repeated.values()).containsOnly(0);
  }

  @Test
  @DisplayName("精确租户清理中途失败回滚已删除的请求、等待和Outbox，重试仍可完成")
  void shouldRollbackWorkflowClosure_whenExactTenantCleanupFails() {
    String suffix = Long.toUnsignedString(System.nanoTime(), 36);
    String tenantId = "itexact_" + suffix;
    List<String> requestIds = newCleanupRequestIds();
    WorkflowCleanupFixture owned = seedCleanupWorkflowFixture("itexact" + suffix, tenantId, requestIds);
    Map<String, Object> before = readCleanupFixtureRows(owned);
    DataIntegrityViolationException failure = new DataIntegrityViolationException("controlled exact cleanup failure");
    List<String> tenantIds = List.of(tenantId);
    doThrow(failure).doCallRealMethod()
        .when(cleanupMapper).cleanupByTenantIds(CleanupTarget.WORKFLOW_DEFINITION, tenantIds);

    assertThatThrownBy(() -> cleanupExactTenantThroughApi(tenantId)).isInstanceOf(HttpServerErrorException.class);

    assertCleanupFixtureCounts(owned, true);
    Map<String, Object> after = readCleanupFixtureRows(owned);
    assertThat(after).isEqualTo(before);
    cleanupExactTenantThroughApi(tenantId);
    assertCleanupFixtureCounts(owned, false);
  }

  private Map<String, Integer> cleanupExactTenantThroughApi(String tenantId) {
    return restClient.delete()
        .uri(uriBuilder -> uriBuilder.path("/internal/admin/test-data/by-ids").queryParam("ids", tenantId).build())
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
  }

  @Test
  @DisplayName("受管流程运行后的前缀清理删除请求及两类Outbox依赖,保留邻居和其它租户并可重复执行")
  void shouldDeleteOwnedWorkflowRequestsAndOutboxes_whenCleaningManagedWorkflowPrefix() {
    String prefix = "itowned" + Long.toUnsignedString(System.nanoTime(), 36);
    List<String> requestIds = newCleanupRequestIds();
    WorkflowCleanupFixture owned = seedCleanupWorkflowFixture(prefix, "t1", requestIds);
    List<String> neighborRequestIds = newCleanupRequestIds();
    WorkflowCleanupFixture neighbor = seedCleanupWorkflowFixture(prefix + "2", "t1", neighborRequestIds);
    // 不同租户复用相同请求号/事件键，防止删除 SQL 只按业务号匹配。
    WorkflowCleanupFixture otherTenant = seedCleanupWorkflowFixture(prefix + "x", "t2", requestIds);
    CleanupInstanceIdentity mirror = seedSameIdentityColdMirror(owned);
    String mirrorBefore = readColdInstance(mirror);
    assertCleanupFixtureCounts(owned, true);
    assertCleanupFixtureCounts(neighbor, true);
    assertCleanupFixtureCounts(otherTenant, true);

    Map<String, Integer> response = cleanupThroughApi(prefix);

    assertThat(response)
        .containsEntry("workflow_definition", 1)
        .containsEntry("workflow_run", 1)
        .containsEntry("workflow_node_run", 1)
        .containsEntry("job_instance", 1)
        .containsEntry("trigger_request", 2)
        .containsEntry("trigger_outbox_event", 2)
        .containsEntry("outbox_event", 1)
        .containsEntry("event_delivery_log", 1)
        .containsEntry("event_outbox_retry", 1)
        .containsEntry("outbox_event_dedup_key", 1);
    assertCleanupFixtureCounts(owned, false);
    String mirrorAfter = readColdInstance(mirror);
    assertThat(mirrorAfter).isEqualTo(mirrorBefore);
    assertCleanupFixtureCounts(neighbor, true);
    assertCleanupFixtureCounts(otherTenant, true);

    Map<String, Integer> repeatedResponse = cleanupThroughApi(prefix);

    assertThat(repeatedResponse.values()).containsOnly(0);
    assertCleanupFixtureCounts(neighbor, true);
    assertCleanupFixtureCounts(otherTenant, true);
  }

  @Test
  @DisplayName("定义删除中途失败时真实事务回滚已删除的运行请求及Outbox,不遗失原有数据")
  void shouldRollbackOwnedCleanup_whenDefinitionDeletionFails() {
    String prefix = "itrollback" + Long.toUnsignedString(System.nanoTime(), 36);
    List<String> requestIds = newCleanupRequestIds();
    WorkflowCleanupFixture owned = seedCleanupWorkflowFixture(prefix, "t1", requestIds);
    String prefixLike = prefix + "-%";
    DataIntegrityViolationException failure = new DataIntegrityViolationException("controlled cleanup failure");
    doThrow(failure)
        .when(cleanupMapper)
        .cleanupByPrefix(eq(CleanupTarget.WORKFLOW_DEFINITION), argThat(scope -> prefixLike.equals(scope.prefixLike())));

    assertThatThrownBy(() -> cleanupThroughApi(prefix)).isInstanceOf(HttpServerErrorException.class);

    assertCleanupFixtureCounts(owned, true);
  }

  @Test
  @DisplayName("同租户跨日重号且邻居无请求时拒绝弱引用清理,所选流程及邻居完整数据不变")
  void shouldRejectAmbiguousInstanceId_whenNeighborIsInSameTenant() {
    assertAmbiguousCleanupRejected("t1", false);
  }

  @Test
  @DisplayName("跨租户跨日重号且邻居无请求时拒绝弱引用清理,所选流程及邻居完整数据不变")
  void shouldRejectAmbiguousInstanceId_whenNeighborIsInAnotherTenant() {
    assertAmbiguousCleanupRejected("t2", false);
  }

  @Test
  @DisplayName("冷历史跨日重号时拒绝将邻居热Outbox误判为所选实例,冷行及全部热数据保留")
  void shouldRejectAmbiguousInstanceId_whenNeighborIsArchived() {
    assertAmbiguousCleanupRejected("t1", true);
  }

  private void assertAmbiguousCleanupRejected(String neighborTenantId, boolean cold) {
    String prefix = "itambig" + Long.toUnsignedString(System.nanoTime(), 36);
    List<String> requestIds = newCleanupRequestIds();
    String eventKey = "cleanup-event-" + requestIds.getFirst();
    String sharedJobCode = "shared-" + prefix;
    String workflowCode = prefix + "-workflow";
    CleanupFixtureIdentity identity =
        new CleanupFixtureIdentity(prefix, "t1", requestIds, eventKey, sharedJobCode, workflowCode);
    WorkflowCleanupFixture owned = seedCleanupWorkflowFixture(identity);
    CollisionNeighbor neighbor = cold
        ? seedColdCollisionNeighbor(owned, neighborTenantId)
        : seedCollisionNeighbor(owned, neighborTenantId);
    if (cold) {
      String selectHotIdCountSql = """
          select count(*) from batch.job_instance where id = ?
          """;
      Long hotIdCount = jdbcTemplate.queryForObject(selectHotIdCountSql, Long.class, owned.jobInstanceId());
      assertThat(hotIdCount).as("only the selected hot identity remains").isEqualTo(1L);
    }
    assertCleanupFixtureCounts(owned, true);
    Map<String, Object> ownedBefore = readCleanupFixtureRows(owned);
    Map<String, Object> neighborBefore = readCollisionNeighborRows(neighbor);
    assertThat(neighborBefore.values()).doesNotContainNull();

    assertThatThrownBy(() -> cleanupThroughApi(prefix)).isInstanceOf(HttpClientErrorException.Conflict.class);

    assertCleanupFixtureCounts(owned, true);
    Map<String, Object> ownedAfter = readCleanupFixtureRows(owned);
    Map<String, Object> neighborAfter = readCollisionNeighborRows(neighbor);
    assertThat(ownedAfter).isEqualTo(ownedBefore);
    assertThat(neighborAfter).isEqualTo(neighborBefore);
  }

  private CollisionNeighbor seedCollisionNeighbor(WorkflowCleanupFixture owned, String tenantId) {
    String prefix = "itoutside" + Long.toUnsignedString(System.nanoTime(), 36);
    List<String> requestIds = newCleanupRequestIds();
    String eventKey = "cleanup-event-" + requestIds.getFirst();
    CleanupFixtureIdentity identity = new CleanupFixtureIdentity(prefix, tenantId, requestIds, eventKey);
    cleanupFixtureIdentities.add(identity);
    Long jobDefinitionId = seedCleanupJob(identity);
    LocalDate neighborDate = CLEANUP_BIZ_DATE.plusDays(1);
    CleanupInstanceIdentity instance = new CleanupInstanceIdentity(identity, owned.jobInstanceId(), neighborDate);
    cleanupInstanceIdentities.add(instance);
    String insertNeighborSql = """
        insert into batch.job_instance (
            id, tenant_id, job_definition_id, job_code, instance_no, biz_date,
            trigger_type, instance_status, dedup_key
        ) values (?, ?, ?, ?, ?, ?, 'MANUAL', 'SUCCESS', ?)
        """;
    String instanceNo = prefix + "-instance";
    Object[] parameters = {instance.id(), tenantId, jobDefinitionId, identity.jobCode(),
        instanceNo, neighborDate, instanceNo};
    // 特意不提供 trigger_request_id，覆盖 NOT(归属谓词) 会漏判的 SQL NULL 分支。
    jdbcTemplate.update(insertNeighborSql, parameters);
    String insertPartitionSql = """
        insert into batch.job_partition (
            tenant_id, job_instance_id, job_instance_biz_date, partition_no, partition_status
        ) values (?, ?, ?, 1, 'SUCCESS')
        """;
    jdbcTemplate.update(insertPartitionSql, tenantId, instance.id(), neighborDate);
    return new CollisionNeighbor(instance, jobDefinitionId, null);
  }

  private CleanupInstanceIdentity seedSameIdentityColdMirror(WorkflowCleanupFixture owned) {
    CleanupInstanceIdentity mirror = new CleanupInstanceIdentity(owned.identity(), owned.jobInstanceId(), CLEANUP_BIZ_DATE);
    cleanupColdInstanceIdentities.add(mirror);
    String insertMirrorSql = """
        insert into archive.job_instance_archive
        select (jsonb_populate_record(null::archive.job_instance_archive, to_jsonb(source))).*
          from batch.job_instance source where source.tenant_id = ? and source.id = ? and source.biz_date = ?
        """;
    jdbcTemplate.update(insertMirrorSql, owned.identity().tenantId(), owned.jobInstanceId(), CLEANUP_BIZ_DATE);
    return mirror;
  }

  private String readColdInstance(CleanupInstanceIdentity instance) {
    String selectColdInstanceSql = """
        select to_jsonb(instance)::text from archive.job_instance_archive instance
         where tenant_id = ? and id = ? and biz_date = ?
        """;
    Object[] parameters = {instance.owner().tenantId(), instance.id(), instance.bizDate()};
    return jdbcTemplate.queryForObject(selectColdInstanceSql, String.class, parameters);
  }

  private CollisionNeighbor seedColdCollisionNeighbor(WorkflowCleanupFixture owned, String tenantId) {
    String prefix = "itcold" + Long.toUnsignedString(System.nanoTime(), 36);
    List<String> requestIds = newCleanupRequestIds();
    String eventKey = "cleanup-event-" + requestIds.getFirst();
    CleanupFixtureIdentity identity = new CleanupFixtureIdentity(prefix, tenantId, requestIds, eventKey);
    cleanupFixtureIdentities.add(identity);
    Long jobDefinitionId = seedCleanupJob(identity);
    LocalDate neighborDate = CLEANUP_BIZ_DATE.plusDays(1);
    CleanupInstanceIdentity instance = new CleanupInstanceIdentity(identity, owned.jobInstanceId(), neighborDate);
    cleanupColdInstanceIdentities.add(instance);
    String insertColdNeighborSql = """
        insert into archive.job_instance_archive
        select (jsonb_populate_record(null::archive.job_instance_archive,
            to_jsonb(source) || jsonb_build_object(
                'tenant_id', ?::varchar, 'job_definition_id', ?::bigint, 'job_code', ?::varchar,
                'instance_no', ?::varchar, 'dedup_key', ?::varchar, 'biz_date', ?::date,
                'trigger_request_id', null, 'parent_instance_id', null))).*
          from batch.job_instance source where source.tenant_id = ? and source.id = ? and source.biz_date = ?
        """;
    String instanceNo = prefix + "-instance";
    Object[] parameters = {tenantId, jobDefinitionId, identity.jobCode(), instanceNo, instanceNo,
        neighborDate, owned.identity().tenantId(), owned.jobInstanceId(), CLEANUP_BIZ_DATE};
    jdbcTemplate.update(insertColdNeighborSql, parameters);
    Long eventId = seedCleanupOutboxChildren(identity, instance.id(), "JOB_INSTANCE");
    return new CollisionNeighbor(instance, jobDefinitionId, eventId);
  }

  private Map<String, Object> readColdCollisionNeighborRows(CollisionNeighbor neighbor) {
    String selectColdRowsSql = """
        with owned as (
            select ?::varchar tenant_id, ?::bigint instance_id, ?::date biz_date,
                   ?::bigint job_id, ?::bigint event_id
        )
        select
            (select to_jsonb(d)::text from batch.job_definition d, owned
                where d.tenant_id = owned.tenant_id and d.id = owned.job_id) job_definition,
            (select to_jsonb(i)::text from archive.job_instance_archive i, owned
                where i.tenant_id = owned.tenant_id and i.id = owned.instance_id
                  and i.biz_date = owned.biz_date) cold_instance,
            (select to_jsonb(o)::text from batch.outbox_event o, owned
                where o.tenant_id = owned.tenant_id and o.id = owned.event_id) outbox_event,
            (select jsonb_agg(to_jsonb(d) order by d.id)::text from batch.event_delivery_log d, owned
                where d.tenant_id = owned.tenant_id and d.outbox_event_id = owned.event_id) delivery,
            (select jsonb_agg(to_jsonb(r) order by r.id)::text from batch.event_outbox_retry r, owned
                where r.tenant_id = owned.tenant_id and r.outbox_event_id = owned.event_id) retry,
            (select to_jsonb(k)::text from batch.outbox_event_dedup_key k, owned
                where k.tenant_id = owned.tenant_id and k.first_event_id = owned.event_id) dedup_key
        """;
    CleanupInstanceIdentity instance = neighbor.instance();
    Object[] parameters = {instance.owner().tenantId(), instance.id(), instance.bizDate(),
        neighbor.jobDefinitionId(), neighbor.outboxEventId()};
    return jdbcTemplate.queryForMap(selectColdRowsSql, parameters);
  }

  private Map<String, Object> readCollisionNeighborRows(CollisionNeighbor neighbor) {
    if (neighbor.outboxEventId() != null) {
      return readColdCollisionNeighborRows(neighbor);
    }
    String selectNeighborRowsSql = """
        with owned as (
            select ?::varchar tenant_id, ?::bigint instance_id, ?::date biz_date, ?::bigint job_id
        )
        select
            (select to_jsonb(d)::text from batch.job_definition d, owned
                where d.tenant_id = owned.tenant_id and d.id = owned.job_id) job_definition,
            (select to_jsonb(i)::text from batch.job_instance i, owned
                where i.tenant_id = owned.tenant_id and i.id = owned.instance_id
                  and i.biz_date = owned.biz_date) job_instance,
            (select jsonb_agg(to_jsonb(p) order by p.id)::text from batch.job_partition p, owned
                where p.tenant_id = owned.tenant_id and p.job_instance_id = owned.instance_id
                  and p.job_instance_biz_date = owned.biz_date) job_partition
        """;
    CleanupInstanceIdentity instance = neighbor.instance();
    Object[] parameters = {instance.owner().tenantId(), instance.id(), instance.bizDate(), neighbor.jobDefinitionId()};
    return jdbcTemplate.queryForMap(selectNeighborRowsSql, parameters);
  }

  private List<String> newCleanupRequestIds() {
    String rootRequestId = "root-" + UUID.randomUUID();
    String childRequestId = "child-" + UUID.randomUUID();
    return List.of(rootRequestId, childRequestId);
  }

  private Map<String, Integer> cleanupThroughApi(String prefix) {
    return restClient
        .delete()
        .uri(uriBuilder -> uriBuilder.path("/internal/admin/test-data").queryParam("prefix", prefix).build())
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
  }

  private WorkflowCleanupFixture seedCleanupWorkflowFixture(
      String prefix, String tenantId, List<String> requestIds) {
    String eventKey = "cleanup-event-" + requestIds.getFirst();
    CleanupFixtureIdentity identity = new CleanupFixtureIdentity(prefix, tenantId, requestIds, eventKey);
    return seedCleanupWorkflowFixture(identity);
  }

  private WorkflowCleanupFixture seedCleanupWorkflowFixture(CleanupFixtureIdentity identity) {
    // 先登记稳定身份，初始化中途失败时也能精确清理已写入的记录。
    cleanupFixtureIdentities.add(identity);
    Long jobDefinitionId = seedCleanupJob(identity);
    Long workflowDefinitionId = seedCleanupManagedWorkflow(identity, jobDefinitionId);
    Long rootRequestId = seedCleanupRootRequest(identity, workflowDefinitionId);
    Long workflowRunId = seedCleanupWorkflowRun(identity, workflowDefinitionId, rootRequestId);
    Long childRequestId = seedCleanupChildRequest(identity, workflowRunId);
    Long jobInstanceId = seedCleanupJobInstance(identity, jobDefinitionId, childRequestId);
    seedCleanupNodeRun(workflowRunId, jobInstanceId);
    seedCleanupTriggerOutboxes(identity);
    seedCleanupOccurrenceHistory(identity, workflowDefinitionId, rootRequestId);
    seedCleanupWaitHistory(identity, workflowDefinitionId);
    seedCleanupExpectationDefinitions(identity, workflowDefinitionId);
    Long outboxEventId = seedCleanupOutboxChildren(identity, workflowRunId);
    return new WorkflowCleanupFixture(
        identity, jobDefinitionId, workflowDefinitionId, workflowRunId, jobInstanceId, outboxEventId);
  }

  private Long seedCleanupJob(CleanupFixtureIdentity identity) {
    String insertJobSql = """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
            trigger_mode, queue_code, worker_group, window_code, priority, enabled
        ) values (?, ?, '清理受试作业', 'GENERAL', 'TEST', 'MANUAL', 'Asia/Shanghai',
            'MANUAL', 'q', 'IMPORT', 'always_open', 5, false)
        returning id
        """;
    String jobCode = identity.jobCode();
    return jdbcTemplate.queryForObject(insertJobSql, Long.class, identity.tenantId(), jobCode);
  }

  private Long seedCleanupManagedWorkflow(CleanupFixtureIdentity identity, Long jobDefinitionId) {
    String insertWorkflowSql = """
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, single_job_definition_id,
            version, enabled, calendar_code
        ) values (?, ?, '清理受试受管流程', 'DAG', ?, 1, false, 'cleanup-calendar')
        returning id
        """;
    String workflowCode = identity.workflowCode();
    return jdbcTemplate.queryForObject(
        insertWorkflowSql, Long.class, identity.tenantId(), workflowCode, jobDefinitionId);
  }

  private Long seedCleanupRootRequest(CleanupFixtureIdentity identity, Long workflowDefinitionId) {
    String insertRootRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, workflow_definition_id,
            biz_date, dedup_key, request_status
        ) values (?, ?, 'CATCH_UP', ?, date '2094-04-03', ?, 'LAUNCHED')
        returning id
        """;
    String requestId = identity.requestIds().getFirst();
    String dedupKey = "dedup-" + requestId;
    return jdbcTemplate.queryForObject(
        insertRootRequestSql, Long.class, identity.tenantId(), requestId, workflowDefinitionId, dedupKey);
  }

  private Long seedCleanupWorkflowRun(
      CleanupFixtureIdentity identity, Long workflowDefinitionId, Long rootRequestId) {
    String insertRunSql = """
        insert into batch.workflow_run (
            tenant_id, workflow_definition_id, trigger_request_id, biz_date, run_status
        ) values (?, ?, ?, date '2094-04-03', 'PAUSED')
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertRunSql, Long.class, identity.tenantId(), workflowDefinitionId, rootRequestId);
  }

  private Long seedCleanupChildRequest(CleanupFixtureIdentity identity, Long workflowRunId) {
    String insertChildRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, parent_workflow_run_id,
            parent_node_code, biz_date, dedup_key, request_status
        ) values (?, ?, 'MANUAL', ?, ?, 'JOB', date '2094-04-03', ?, 'LAUNCHED')
        returning id
        """;
    String requestId = identity.requestIds().get(1);
    String dedupKey = "dedup-" + requestId;
    String jobCode = identity.jobCode();
    return jdbcTemplate.queryForObject(
        insertChildRequestSql, Long.class, identity.tenantId(), requestId, jobCode, workflowRunId, dedupKey);
  }

  private Long seedCleanupJobInstance(
      CleanupFixtureIdentity identity, Long jobDefinitionId, Long childRequestId) {
    String insertInstanceSql = """
        insert into batch.job_instance (
            tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,
            biz_date, trigger_type, instance_status, dedup_key
        ) values (?, ?, ?, ?, ?, date '2094-04-03', 'MANUAL', 'SUCCESS', ?)
        returning id
        """;
    String jobCode = identity.jobCode();
    String instanceNo = identity.prefix() + "-instance";
    String dedupKey = "instance-" + identity.requestIds().get(1);
    Long instanceId = jdbcTemplate.queryForObject(
        insertInstanceSql, Long.class, identity.tenantId(), jobDefinitionId, childRequestId,
        jobCode, instanceNo, dedupKey);
    CleanupInstanceIdentity instance = new CleanupInstanceIdentity(identity, instanceId, CLEANUP_BIZ_DATE);
    cleanupInstanceIdentities.add(instance);
    return instanceId;
  }

  private void seedCleanupNodeRun(Long workflowRunId, Long jobInstanceId) {
    String insertNodeRunSql = """
        insert into batch.workflow_node_run (
            workflow_run_id, node_code, node_type, node_status, job_instance_id
        ) values (?, 'JOB', 'JOB', 'SUCCESS', ?)
        """;
    jdbcTemplate.update(insertNodeRunSql, workflowRunId, jobInstanceId);
  }

  private void seedCleanupTriggerOutboxes(CleanupFixtureIdentity identity) {
    String insertTriggerOutboxesSql = """
        insert into batch.trigger_outbox_event (tenant_id, request_id, payload, publish_status)
        select tenant_id, request_id, '{}'::jsonb, 'PUBLISHED'
          from batch.trigger_request
         where tenant_id = ? and request_id in (?, ?)
        """;
    jdbcTemplate.update(
        insertTriggerOutboxesSql, identity.tenantId(), identity.requestIds().getFirst(), identity.requestIds().get(1));
  }

  private void seedCleanupOccurrenceHistory(
      CleanupFixtureIdentity identity, Long workflowDefinitionId, Long rootRequestId) {
    String insertOccurrenceSql = """
        insert into batch.scheduled_occurrence (
            tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
            scheduled_fire_time, biz_date, occurrence_status, request_id
        ) values (?, ?, ?, 1, 'cleanup-calendar', timestamptz '2094-04-03T00:00:00Z',
            date '2094-04-03', 'REQUESTED', ?)
        """;
    String workflowCode = identity.workflowCode();
    String requestId = identity.requestIds().getFirst();
    jdbcTemplate.update(insertOccurrenceSql, identity.tenantId(), workflowDefinitionId, workflowCode, requestId);
    String insertAuditSql = """
        insert into batch.scheduled_occurrence_catch_up_audit (
            tenant_id, workflow_definition_id, scheduled_fire_time, biz_date, request_id, requested_by, reason
        ) values (?, ?, timestamptz '2094-04-03T00:00:00Z', date '2094-04-03', ?, 'cleanup-fixture', 'test catch-up')
        """;
    jdbcTemplate.update(insertAuditSql, identity.tenantId(), workflowDefinitionId, requestId);
    String insertCoverageSql = """
        insert into batch.scheduled_occurrence_coverage (
            tenant_id, workflow_definition_id, schedule_version, calendar_code, coverage_from_at, coverage_through_at
        ) values (?, ?, 1, 'cleanup-calendar', timestamptz '2094-04-03T00:00:00Z', timestamptz '2094-04-04T00:00:00Z')
        """;
    jdbcTemplate.update(insertCoverageSql, identity.tenantId(), workflowDefinitionId);
    String insertExpectedWorkSql = """
        insert into batch.expected_work_occurrence (
            tenant_id, calendar_code, biz_date, workflow_definition_id, source_type,
            source_key, occurrence_status, trigger_request_id
        ) values (?, 'cleanup-calendar', date '2094-04-03', ?, 'WORKFLOW', ?, 'SATISFIED', ?)
        """;
    jdbcTemplate.update(insertExpectedWorkSql, identity.tenantId(), workflowDefinitionId, identity.prefix(), requestId);
    String insertMisfireSql = """
        insert into batch.trigger_misfire_pending (
            tenant_id, workflow_definition_id, scheduled_fire_time, status, catch_up_request_id
        ) values (?, ?, timestamptz '2094-04-03T00:00:00Z', 'APPROVED', ?)
        """;
    jdbcTemplate.update(insertMisfireSql, identity.tenantId(), workflowDefinitionId, rootRequestId);
    String insertAliasSql = """
        insert into batch.trigger_request_idempotency_alias (tenant_id, idempotency_key, request_id)
        values (?, ?, ?)
        """;
    jdbcTemplate.update(insertAliasSql, identity.tenantId(), "alias-" + requestId, requestId);
  }

  private void seedCleanupWaitHistory(CleanupFixtureIdentity identity, Long workflowDefinitionId) {
    String insertWorkflowWaitSql = """
        insert into batch.workflow_launch_wait (
            tenant_id, request_id, workflow_definition_id, biz_date, launch_payload, wait_status
        ) values (?, ?, ?, date '2094-04-03', '{}'::jsonb, 'RESUMED')
        """;
    jdbcTemplate.update(
        insertWorkflowWaitSql, identity.tenantId(), identity.requestIds().getFirst(), workflowDefinitionId);
    String insertDependencyWaitSql = """
        insert into batch.job_dependency_waiting_launch (
            tenant_id, request_id, job_code, biz_date, dependency_snapshot, launch_payload, wait_status
        ) values (?, ?, ?, date '2094-04-03', '[]'::jsonb, '{}'::jsonb, 'RELEASED')
        """;
    String jobCode = identity.jobCode();
    String childRequestId = identity.requestIds().get(1);
    jdbcTemplate.update(insertDependencyWaitSql, identity.tenantId(), childRequestId, jobCode);
    String insertBatchDayWaitSql = """
        insert into batch.batch_day_waiting_launch (
            tenant_id, calendar_code, job_code, biz_date, request_id,
            trigger_type, wait_reason, launch_payload, wait_status
        ) values (?, 'cleanup-calendar', ?, date '2094-04-03', ?, 'MANUAL', 'test', '{}'::jsonb, 'RELEASED')
        """;
    jdbcTemplate.update(insertBatchDayWaitSql, identity.tenantId(), jobCode, childRequestId);
  }

  private void seedCleanupExpectationDefinitions(CleanupFixtureIdentity identity, Long workflowDefinitionId) {
    String insertFileExpectationSql = """
        insert into batch.file_group_expectation_definition (
            tenant_id, calendar_code, workflow_definition_id, file_group_code, expected_local_time, enabled
        ) values (?, 'cleanup-calendar', ?, ?, time '01:00:00', false)
        """;
    jdbcTemplate.update(insertFileExpectationSql, identity.tenantId(), workflowDefinitionId, identity.prefix());
    String insertEventExpectationSql = """
        insert into batch.external_event_expectation_definition (
            tenant_id, calendar_code, workflow_definition_id, event_code, expected_local_time, enabled
        ) values (?, 'cleanup-calendar', ?, ?, time '01:00:00', false)
        """;
    jdbcTemplate.update(insertEventExpectationSql, identity.tenantId(), workflowDefinitionId, identity.prefix());
  }

  private Long seedCleanupOutboxChildren(CleanupFixtureIdentity identity, Long workflowRunId) {
    return seedCleanupOutboxChildren(identity, workflowRunId, "WORKFLOW_RUN");
  }

  private Long seedCleanupOutboxChildren(CleanupFixtureIdentity identity, Long aggregateId, String aggregateType) {
    String insertOutboxSql = """
        insert into batch.outbox_event (
            tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json, publish_status
        ) values (?, ?, ?, ?, ?, '{}'::jsonb, 'PUBLISHED')
        returning id
        """;
    String eventType = "JOB_INSTANCE".equals(aggregateType) ? "JOB_TERMINAL" : "WORKFLOW_TERMINAL";
    Long eventId = jdbcTemplate.queryForObject(
        insertOutboxSql, Long.class, identity.tenantId(), aggregateType, aggregateId, eventType, identity.eventKey());
    String insertDedupSql = """
        insert into batch.outbox_event_dedup_key (tenant_id, event_key, first_event_id)
        values (?, ?, ?)
        """;
    jdbcTemplate.update(insertDedupSql, identity.tenantId(), identity.eventKey(), eventId);
    String insertDeliverySql = """
        insert into batch.event_delivery_log (
            tenant_id, outbox_event_id, outbox_event_created_at, event_type,
            event_key, target_topic, delivery_status
        ) select tenant_id, id, created_at, event_type, event_key, 'cleanup-topic', 'PUBLISHED'
            from batch.outbox_event where tenant_id = ? and id = ?
        """;
    jdbcTemplate.update(insertDeliverySql, identity.tenantId(), eventId);
    String insertRetrySql = """
        insert into batch.event_outbox_retry (
            tenant_id, outbox_event_id, outbox_event_created_at, event_key, retry_status
        ) select tenant_id, id, created_at, event_key, 'SUCCESS'
            from batch.outbox_event where tenant_id = ? and id = ?
        """;
    jdbcTemplate.update(insertRetrySql, identity.tenantId(), eventId);
    return eventId;
  }

  private void assertCleanupFixtureCounts(WorkflowCleanupFixture fixture, boolean present) {
    String selectFixtureCountsSql = """
        with owned as (
            select ?::varchar tenant_id, ?::bigint job_id, ?::bigint workflow_id,
                   ?::bigint run_id, ?::bigint instance_id, ?::bigint event_id,
                   ?::varchar root_request, ?::varchar child_request, ?::varchar event_key, ?::date biz_date
        )
        select
            (select count(*) from batch.job_definition d, owned where d.tenant_id = owned.tenant_id and d.id = owned.job_id) job_definition,
            (select count(*) from batch.workflow_definition d, owned where d.tenant_id = owned.tenant_id and d.id = owned.workflow_id) workflow_definition,
            (select count(*) from batch.workflow_run r, owned where r.tenant_id = owned.tenant_id and r.id = owned.run_id) workflow_run,
            (select count(*) from batch.workflow_node_run n, owned where n.workflow_run_id = owned.run_id) workflow_node_run,
            (select count(*) from batch.job_instance i, owned where i.tenant_id = owned.tenant_id and i.id = owned.instance_id
                  and i.biz_date = owned.biz_date) job_instance,
            (select count(*) from batch.trigger_request r, owned where r.tenant_id = owned.tenant_id and r.request_id in (owned.root_request, owned.child_request)) trigger_request,
            (select count(*) from batch.trigger_outbox_event o, owned where o.tenant_id = owned.tenant_id and o.request_id in (owned.root_request, owned.child_request)) trigger_outbox_event,
            (select count(*) from batch.outbox_event o, owned where o.tenant_id = owned.tenant_id and o.id = owned.event_id) outbox_event,
            (select count(*) from batch.event_delivery_log d, owned where d.tenant_id = owned.tenant_id and d.outbox_event_id = owned.event_id) event_delivery_log,
            (select count(*) from batch.event_outbox_retry r, owned where r.tenant_id = owned.tenant_id and r.outbox_event_id = owned.event_id) event_outbox_retry,
            (select count(*) from batch.outbox_event_dedup_key k, owned where k.tenant_id = owned.tenant_id and k.event_key = owned.event_key) outbox_event_dedup_key
        """;
    CleanupFixtureIdentity identity = fixture.identity();
    Object[] parameters = {identity.tenantId(), fixture.jobDefinitionId(), fixture.workflowDefinitionId(),
        fixture.workflowRunId(), fixture.jobInstanceId(), fixture.outboxEventId(),
        identity.requestIds().getFirst(), identity.requestIds().get(1), identity.eventKey(), CLEANUP_BIZ_DATE};
    Map<String, Object> counts = jdbcTemplate.queryForMap(selectFixtureCountsSql, parameters);
    long one = present ? 1L : 0L;
    long two = present ? 2L : 0L;
    assertThat(counts)
        .containsEntry("job_definition", one)
        .containsEntry("workflow_definition", one)
        .containsEntry("workflow_run", one)
        .containsEntry("workflow_node_run", one)
        .containsEntry("job_instance", one)
        .containsEntry("trigger_request", two)
        .containsEntry("trigger_outbox_event", two)
        .containsEntry("outbox_event", one)
        .containsEntry("event_delivery_log", one)
        .containsEntry("event_outbox_retry", one)
        .containsEntry("outbox_event_dedup_key", one);
    assertCleanupDependencyCounts(fixture, present);
  }

  private void assertCleanupDependencyCounts(WorkflowCleanupFixture fixture, boolean present) {
    String selectDependencyCountsSql = """
        with owned as (
            select ?::varchar tenant_id, ?::bigint workflow_id,
                   ?::varchar root_request, ?::varchar child_request
        )
        select
            (select count(*) from batch.scheduled_occurrence_catch_up_audit a, owned
                where a.tenant_id = owned.tenant_id and a.workflow_definition_id = owned.workflow_id) catch_up_audit,
            (select count(*) from batch.scheduled_occurrence o, owned
                where o.tenant_id = owned.tenant_id and o.workflow_definition_id = owned.workflow_id) scheduled_occurrence,
            (select count(*) from batch.scheduled_occurrence_coverage c, owned
                where c.tenant_id = owned.tenant_id and c.workflow_definition_id = owned.workflow_id) coverage,
            (select count(*) from batch.expected_work_occurrence e, owned
                where e.tenant_id = owned.tenant_id and e.workflow_definition_id = owned.workflow_id) expected_work,
            (select count(*) from batch.trigger_misfire_pending m, owned
                where m.tenant_id = owned.tenant_id and m.workflow_definition_id = owned.workflow_id) misfire_pending,
            (select count(*) from batch.workflow_launch_wait w, owned
                where w.tenant_id = owned.tenant_id and w.workflow_definition_id = owned.workflow_id) workflow_wait,
            (select count(*) from batch.job_dependency_waiting_launch w, owned
                where w.tenant_id = owned.tenant_id and w.request_id = owned.child_request) dependency_wait,
            (select count(*) from batch.batch_day_waiting_launch w, owned
                where w.tenant_id = owned.tenant_id and w.request_id = owned.child_request) batch_day_wait,
            (select count(*) from batch.file_group_expectation_definition d, owned
                where d.tenant_id = owned.tenant_id and d.workflow_definition_id = owned.workflow_id) file_expectation,
            (select count(*) from batch.external_event_expectation_definition d, owned
                where d.tenant_id = owned.tenant_id and d.workflow_definition_id = owned.workflow_id) event_expectation,
            (select count(*) from batch.trigger_request_idempotency_alias a, owned
                where a.tenant_id = owned.tenant_id and a.request_id = owned.root_request) request_alias
        """;
    CleanupFixtureIdentity identity = fixture.identity();
    Object[] parameters = {identity.tenantId(), fixture.workflowDefinitionId(),
        identity.requestIds().getFirst(), identity.requestIds().get(1)};
    Map<String, Object> counts = jdbcTemplate.queryForMap(selectDependencyCountsSql, parameters);
    long expectedCount = present ? 1L : 0L;
    assertThat(counts.values()).containsOnly(expectedCount);
  }

  private void cleanupOwnedWorkflowRequestDependents(CleanupFixtureIdentity identity) {
    String deleteAuditSql = """
        delete from batch.scheduled_occurrence_catch_up_audit where tenant_id = ? and request_id = ?
        """;
    String deleteOccurrenceSql = """
        delete from batch.scheduled_occurrence where tenant_id = ? and request_id = ?
        """;
    String deleteExpectedWorkSql = """
        delete from batch.expected_work_occurrence where tenant_id = ? and trigger_request_id = ?
        """;
    String deleteWorkflowWaitSql = """
        delete from batch.workflow_launch_wait where tenant_id = ? and request_id = ?
        """;
    String deleteDependencyWaitSql = """
        delete from batch.job_dependency_waiting_launch where tenant_id = ? and request_id = ?
        """;
    String deleteBatchDayWaitSql = """
        delete from batch.batch_day_waiting_launch where tenant_id = ? and request_id = ?
        """;
    String rootRequestId = identity.requestIds().getFirst();
    String childRequestId = identity.requestIds().get(1);
    jdbcTemplate.update(deleteAuditSql, identity.tenantId(), rootRequestId);
    jdbcTemplate.update(deleteOccurrenceSql, identity.tenantId(), rootRequestId);
    jdbcTemplate.update(deleteExpectedWorkSql, identity.tenantId(), rootRequestId);
    jdbcTemplate.update(deleteWorkflowWaitSql, identity.tenantId(), rootRequestId);
    jdbcTemplate.update(deleteDependencyWaitSql, identity.tenantId(), childRequestId);
    jdbcTemplate.update(deleteBatchDayWaitSql, identity.tenantId(), childRequestId);
    cleanupOwnedWorkflowDefinitionDependents(identity);
  }

  private void cleanupOwnedWorkflowDefinitionDependents(CleanupFixtureIdentity identity) {
    String deleteCoverageSql = """
        delete from batch.scheduled_occurrence_coverage where tenant_id = ? and workflow_definition_id in (
            select id from batch.workflow_definition where tenant_id = ? and workflow_code = ?)
        """;
    String deleteMisfireSql = """
        delete from batch.trigger_misfire_pending where tenant_id = ? and workflow_definition_id in (
            select id from batch.workflow_definition where tenant_id = ? and workflow_code = ?)
        """;
    String deleteFileExpectationSql = """
        delete from batch.file_group_expectation_definition where tenant_id = ? and workflow_definition_id in (
            select id from batch.workflow_definition where tenant_id = ? and workflow_code = ?)
        """;
    String deleteEventExpectationSql = """
        delete from batch.external_event_expectation_definition where tenant_id = ? and workflow_definition_id in (
            select id from batch.workflow_definition where tenant_id = ? and workflow_code = ?)
        """;
    Object[] parameters = {identity.tenantId(), identity.tenantId(), identity.workflowCode()};
    jdbcTemplate.update(deleteCoverageSql, parameters);
    jdbcTemplate.update(deleteMisfireSql, parameters);
    jdbcTemplate.update(deleteFileExpectationSql, parameters);
    jdbcTemplate.update(deleteEventExpectationSql, parameters);
  }

  private Map<String, Object> readCleanupFixtureRows(WorkflowCleanupFixture fixture) {
    String selectFixtureRowsSql = """
        with owned as (
            select ?::varchar tenant_id, ?::bigint job_id, ?::bigint workflow_id,
                   ?::bigint run_id, ?::bigint instance_id, ?::bigint event_id,
                   ?::varchar root_request, ?::varchar child_request, ?::varchar event_key, ?::date biz_date
        )
        select
            (select jsonb_agg(to_jsonb(d) order by d.id)::text from batch.job_definition d, owned
                where d.tenant_id = owned.tenant_id and d.id = owned.job_id) job_definition,
            (select jsonb_agg(to_jsonb(d) order by d.id)::text from batch.workflow_definition d, owned
                where d.tenant_id = owned.tenant_id and d.id = owned.workflow_id) workflow_definition,
            (select jsonb_agg(to_jsonb(r) order by r.id)::text from batch.workflow_run r, owned
                where r.tenant_id = owned.tenant_id and r.id = owned.run_id) workflow_run,
            (select jsonb_agg(to_jsonb(n) order by n.id)::text from batch.workflow_node_run n, owned
                where n.workflow_run_id = owned.run_id) workflow_node_run,
            (select jsonb_agg(to_jsonb(i) order by i.id)::text from batch.job_instance i, owned
                where i.tenant_id = owned.tenant_id and i.id = owned.instance_id and i.biz_date = owned.biz_date) job_instance,
            (select jsonb_agg(to_jsonb(r) order by r.id)::text from batch.trigger_request r, owned
                where r.tenant_id = owned.tenant_id and r.request_id in (owned.root_request, owned.child_request)) trigger_request,
            (select jsonb_agg(to_jsonb(o) order by o.id)::text from batch.trigger_outbox_event o, owned
                where o.tenant_id = owned.tenant_id and o.request_id in (owned.root_request, owned.child_request)) trigger_outbox_event,
            (select jsonb_agg(to_jsonb(o) order by o.id)::text from batch.outbox_event o, owned
                where o.tenant_id = owned.tenant_id and o.id = owned.event_id) outbox_event,
            (select jsonb_agg(to_jsonb(d) order by d.id)::text from batch.event_delivery_log d, owned
                where d.tenant_id = owned.tenant_id and d.outbox_event_id = owned.event_id) event_delivery_log,
            (select jsonb_agg(to_jsonb(r) order by r.id)::text from batch.event_outbox_retry r, owned
                where r.tenant_id = owned.tenant_id and r.outbox_event_id = owned.event_id) event_outbox_retry,
            (select jsonb_agg(to_jsonb(k) order by k.event_key)::text from batch.outbox_event_dedup_key k, owned
                where k.tenant_id = owned.tenant_id and k.event_key = owned.event_key) outbox_event_dedup_key,
            (select jsonb_agg(to_jsonb(a) order by a.id)::text from batch.scheduled_occurrence_catch_up_audit a, owned
                where a.tenant_id = owned.tenant_id and a.workflow_definition_id = owned.workflow_id) catch_up_audit,
            (select jsonb_agg(to_jsonb(o) order by o.id)::text from batch.scheduled_occurrence o, owned
                where o.tenant_id = owned.tenant_id and o.workflow_definition_id = owned.workflow_id) scheduled_occurrence,
            (select jsonb_agg(to_jsonb(c) order by c.workflow_definition_id)::text from batch.scheduled_occurrence_coverage c, owned
                where c.tenant_id = owned.tenant_id and c.workflow_definition_id = owned.workflow_id) coverage,
            (select jsonb_agg(to_jsonb(e) order by e.id)::text from batch.expected_work_occurrence e, owned
                where e.tenant_id = owned.tenant_id and e.workflow_definition_id = owned.workflow_id) expected_work,
            (select jsonb_agg(to_jsonb(m) order by m.id)::text from batch.trigger_misfire_pending m, owned
                where m.tenant_id = owned.tenant_id and m.workflow_definition_id = owned.workflow_id) misfire_pending,
            (select jsonb_agg(to_jsonb(w) order by w.id)::text from batch.workflow_launch_wait w, owned
                where w.tenant_id = owned.tenant_id and w.workflow_definition_id = owned.workflow_id) workflow_wait,
            (select jsonb_agg(to_jsonb(w) order by w.id)::text from batch.job_dependency_waiting_launch w, owned
                where w.tenant_id = owned.tenant_id and w.request_id = owned.child_request) dependency_wait,
            (select jsonb_agg(to_jsonb(w) order by w.id)::text from batch.batch_day_waiting_launch w, owned
                where w.tenant_id = owned.tenant_id and w.request_id = owned.child_request) batch_day_wait,
            (select jsonb_agg(to_jsonb(d) order by d.id)::text from batch.file_group_expectation_definition d, owned
                where d.tenant_id = owned.tenant_id and d.workflow_definition_id = owned.workflow_id) file_expectation,
            (select jsonb_agg(to_jsonb(d) order by d.id)::text from batch.external_event_expectation_definition d, owned
                where d.tenant_id = owned.tenant_id and d.workflow_definition_id = owned.workflow_id) event_expectation,
            (select jsonb_agg(to_jsonb(a) order by a.idempotency_key)::text from batch.trigger_request_idempotency_alias a, owned
                where a.tenant_id = owned.tenant_id and a.request_id = owned.root_request) request_alias
        """;
    CleanupFixtureIdentity identity = fixture.identity();
    Object[] parameters = {identity.tenantId(), fixture.jobDefinitionId(), fixture.workflowDefinitionId(),
        fixture.workflowRunId(), fixture.jobInstanceId(), fixture.outboxEventId(),
        identity.requestIds().getFirst(), identity.requestIds().get(1), identity.eventKey(), CLEANUP_BIZ_DATE};
    return jdbcTemplate.queryForMap(selectFixtureRowsSql, parameters);
  }

  private void cleanupOwnedWorkflowFixture(CleanupFixtureIdentity identity) {
    String deleteDeliverySql = """
        delete from batch.event_delivery_log where tenant_id = ? and event_key = ?
        """;
    String deleteRetrySql = """
        delete from batch.event_outbox_retry where tenant_id = ? and event_key = ?
        """;
    String deleteOutboxSql = """
        delete from batch.outbox_event where tenant_id = ? and event_key = ?
        """;
    String deleteOutboxKeySql = """
        delete from batch.outbox_event_dedup_key where tenant_id = ? and event_key = ?
        """;
    jdbcTemplate.update(deleteDeliverySql, identity.tenantId(), identity.eventKey());
    jdbcTemplate.update(deleteRetrySql, identity.tenantId(), identity.eventKey());
    jdbcTemplate.update(deleteOutboxSql, identity.tenantId(), identity.eventKey());
    jdbcTemplate.update(deleteOutboxKeySql, identity.tenantId(), identity.eventKey());
    cleanupOwnedWorkflowRequestDependents(identity);
    cleanupOwnedWorkflowExecution(identity);
    String deleteTriggerOutboxSql = """
        delete from batch.trigger_outbox_event where tenant_id = ? and request_id in (?, ?)
        """;
    String deleteRequestSql = """
        delete from batch.trigger_request where tenant_id = ? and request_id in (?, ?)
        """;
    Object[] requestParameters = {identity.tenantId(), identity.requestIds().getFirst(), identity.requestIds().get(1)};
    jdbcTemplate.update(deleteTriggerOutboxSql, requestParameters);
    jdbcTemplate.update(deleteRequestSql, requestParameters);
    String deleteWorkflowSql = """
        delete from batch.workflow_definition where tenant_id = ? and workflow_code = ?
        """;
    String deleteJobSql = """
        delete from batch.job_definition where tenant_id = ? and job_code = ?
        """;
    jdbcTemplate.update(deleteWorkflowSql, identity.tenantId(), identity.workflowCode());
    jdbcTemplate.update(deleteJobSql, identity.tenantId(), identity.jobCode());
  }

  private void cleanupOwnedWorkflowNodeRuns(CleanupFixtureIdentity identity) {
    String deleteNodeRunSql = """
        delete from batch.workflow_node_run where workflow_run_id in (
            select run.id from batch.workflow_run run join batch.workflow_definition definition
                on definition.tenant_id = run.tenant_id and definition.id = run.workflow_definition_id
             where definition.tenant_id = ? and definition.workflow_code = ?)
        """;
    jdbcTemplate.update(deleteNodeRunSql, identity.tenantId(), identity.workflowCode());
  }

  private void cleanupOwnedWorkflowExecution(CleanupFixtureIdentity identity) {
    cleanupOwnedWorkflowNodeRuns(identity);
    String deleteRunSql = """
        delete from batch.workflow_run where tenant_id = ? and workflow_definition_id in (
            select id from batch.workflow_definition where tenant_id = ? and workflow_code = ?)
        """;
    jdbcTemplate.update(deleteRunSql, identity.tenantId(), identity.tenantId(), identity.workflowCode());
    for (CleanupInstanceIdentity instance : cleanupInstanceIdentities) {
      if (identity.equals(instance.owner())) {
        cleanupOwnedInstance(instance);
      }
    }
    String deleteColdSql = """
        delete from archive.job_instance_archive where tenant_id = ? and id = ? and biz_date = ?
        """;
    for (CleanupInstanceIdentity instance : cleanupColdInstanceIdentities) {
      if (identity.equals(instance.owner())) {
        jdbcTemplate.update(deleteColdSql, identity.tenantId(), instance.id(), instance.bizDate());
      }
    }
  }

  private void cleanupOwnedInstance(CleanupInstanceIdentity instance) {
    String deletePartitionSql = """
        delete from batch.job_partition
         where tenant_id = ? and job_instance_id = ? and job_instance_biz_date = ?
        """;
    String deleteInstanceSql = """
        delete from batch.job_instance where tenant_id = ? and id = ? and biz_date = ?
        """;
    Object[] parameters = {instance.owner().tenantId(), instance.id(), instance.bizDate()};
    jdbcTemplate.update(deletePartitionSql, parameters);
    jdbcTemplate.update(deleteInstanceSql, parameters);
  }

  private record CleanupFixtureIdentity(
      String prefix, String tenantId, List<String> requestIds, String eventKey, String jobCode, String workflowCode) {
    private CleanupFixtureIdentity(String prefix, String tenantId, List<String> requestIds, String eventKey) {
      this(prefix, tenantId, requestIds, eventKey, prefix + "-job", "managed-" + prefix);
    }
  }

  private record CleanupInstanceIdentity(CleanupFixtureIdentity owner, Long id, LocalDate bizDate) {}

  private record CollisionNeighbor(
      CleanupInstanceIdentity instance, Long jobDefinitionId, Long outboxEventId) {}

  private record WorkflowCleanupFixture(
      CleanupFixtureIdentity identity, Long jobDefinitionId, Long workflowDefinitionId,
      Long workflowRunId, Long jobInstanceId, Long outboxEventId) {}
}

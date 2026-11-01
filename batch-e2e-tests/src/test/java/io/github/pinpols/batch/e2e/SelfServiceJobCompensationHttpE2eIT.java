package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.dto.WorkerHeartbeatDto;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleAuthTokenResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplaySubmitCommand;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionPromoteService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDayReplayDispatcher;
import io.github.pinpols.batch.orchestrator.service.WorkerRegistryServerService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.awaitility.core.ThrowingRunnable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/** 通过真实认证的 Console 自助审批完成 Workflow 与 Worker 重跑。 */
@E2eSpringBootTest(
    classes = E2eConsoleImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.import.worker-type=IMPORT",
      "batch.security.bypass-mode=true",
      "batch.security.internal-secret=self-service-e2e-internal-secret-32chars",
      "batch.console.security.default-tenant-id=t1",
      "batch.console.security.allowed-tenants=t1",
      "batch.console.orchestrator.base-url=http://127.0.0.1:${local.server.port}",
      "batch.console.trigger.base-url=http://127.0.0.1:${local.server.port}"
    })
@E2eBusinessSchema
@Sql(scripts = E2eTestSql.IMPORT_TEMPLATE_SEED)
@Tag("critical")
@DisplayName("租户自助 JOB 补偿经真实认证审批与 Worker 重跑")
class SelfServiceJobCompensationHttpE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  // 手工跳过用例使用未截止业务日，避免长跑时被结算调度关闭。
  private static final LocalDate BIZ_DATE = LocalDate.now(ZoneOffset.UTC).plusDays(1);
  private static final LocalDate WORKFLOW_REPLAY_BIZ_DATE = LocalDate.of(2099, 1, 15);
  private static final String INTERNAL_SECRET = "self-service-e2e-internal-secret-32chars";
  private static final String JAVA_BIN =
      Path.of(System.getProperty("java.home"), "bin", "java").toString();
  private static final long WORKER_BUILD_TIMEOUT_MINUTES = 10L;
  private static final String INSERT_WORKFLOW_REPLAY_EXPORT_TEMPLATE_SQL = """
      insert into batch.file_template_config (
        tenant_id, template_code, template_name, template_type, biz_type,
        file_format_type, charset, target_charset, delimiter, quote_char, escape_char,
        checksum_type, naming_rule, field_mappings, query_param_schema,
        streaming_enabled, page_size, fetch_size, chunk_size, enabled, version,
        created_by, export_data_ref
      ) values (
        ?, ?, ?, 'EXPORT', 'WORKFLOW_REPLAY', 'DELIMITED', 'UTF-8', 'UTF-8',
        ',', '"', '"', 'SHA-256', ?, ?::jsonb, ?::jsonb,
        true, 100, 100, 100, true, ?, 'r25-e2e', 'jdbc_mapped_export'
      )
      """;
  private static final String SELECT_WORKFLOW_REPLAY_OPERATION_SQL = """
      select id, status, from_date, through_date, source_workflow_run_id, target_request_id,
        target_workflow_run_id, total_count, succeeded_count, failed_count, in_flight_count
      from batch.replay_operation
      where tenant_id=? and operation_type='WORKFLOW_RUN' and operation_key=?::uuid
      """;
  private static final String COUNT_WORKFLOW_REPLAY_OPERATION_BY_KEY_SQL = """
      select count(1)::int from batch.replay_operation
      where tenant_id=? and operation_type='WORKFLOW_RUN' and operation_key=?::uuid
      """;
  private static final String COUNT_UNCONSUMED_WORKFLOW_REPLAY_PREVIEW_TOKEN_BY_KEY_SQL = """
      select count(1)::int from batch.replay_operation_preview_token
      where tenant_id=? and operation_key=?::uuid and consumed_at is null
      """;
  private static final String SELECT_WORKFLOW_REPLAY_JOB_SQL = """
      select node_run.job_instance_id, node_run.node_status,
        job_instance.instance_status, workflow_run.run_status
      from batch.workflow_node_run node_run
      join batch.workflow_run workflow_run
        on workflow_run.tenant_id=? and workflow_run.id=node_run.workflow_run_id
      join batch.job_instance job_instance
        on job_instance.tenant_id=workflow_run.tenant_id
          and job_instance.id=node_run.job_instance_id
      where workflow_run.id=? and node_run.node_type='JOB'
      """;
  private static final String SELECT_WORKFLOW_REPLAY_EXPORT_FILE_SQL = """
      select id, file_status, file_name, file_size_bytes, checksum_value as checksum,
        metadata_json->>'templateCode' template_code
      from batch.file_record
      where tenant_id=? and source_ref=? and metadata_json->>'templateCode'=?
      """;
  private static final String SELECT_WORKFLOW_REPLAY_RESULT_VERSION_SQL = """
      select id, status, business_key, version_no, job_instance_id, payload_storage
      from batch.result_version where tenant_id=? and job_instance_id=?
      """;
  private static final String SELECT_DATE_RANGE_REPLAY_OPERATION_SQL = """
      select id, status, from_date, through_date, total_count, succeeded_count,
        failed_count, in_flight_count, approved_by
      from batch.replay_operation
      where tenant_id=? and operation_type='BATCH_DAY_RANGE' and operation_key=?::uuid
      """;
  private static final String SELECT_DATE_RANGE_REPLAY_SESSIONS_SQL = """
      select id, biz_date, status, total_count, succeeded_count, failed_count, in_flight_count
      from batch.batch_day_replay_session
      where tenant_id=? and replay_operation_id=?
      order by biz_date
      """;
  private static final String SELECT_DATE_RANGE_REPLAY_ENTRIES_SQL = """
      select session.biz_date, entry.job_code, entry.source_instance_id,
        entry.rerun_instance_id, entry.status as entry_status,
        source.biz_date as source_biz_date, source.instance_status as source_status,
        rerun.biz_date as rerun_biz_date, rerun.instance_status as rerun_status,
        workflow.run_status as rerun_workflow_status
      from batch.batch_day_replay_session session
      join batch.batch_day_replay_entry entry
        on entry.tenant_id=session.tenant_id and entry.session_id=session.id
      join batch.job_instance source
        on source.tenant_id=entry.tenant_id and source.id=entry.source_instance_id
      join batch.job_instance rerun
        on rerun.tenant_id=entry.tenant_id and rerun.id=entry.rerun_instance_id
      join batch.workflow_node_run node_run
        on node_run.job_instance_id=rerun.id and node_run.node_type='JOB'
      join batch.workflow_run workflow
        on workflow.tenant_id=session.tenant_id and workflow.id=node_run.workflow_run_id
      where session.tenant_id=? and session.replay_operation_id=?
      order by session.biz_date, entry.job_code
      """;
  private static final String SELECT_DATE_RANGE_REPLAY_ENTRIES_FOR_RETRY_SQL = """
      select session.id as session_id, session.biz_date, session.status as session_status,
        entry.id as entry_id, entry.status as entry_status, entry.rerun_instance_id
      from batch.batch_day_replay_session session
      join batch.batch_day_replay_entry entry
        on entry.tenant_id=session.tenant_id and entry.session_id=session.id
      where session.tenant_id=? and session.replay_operation_id=?
      order by session.biz_date, entry.job_code
      """;
  private static final String SELECT_DATE_RANGE_REPLAY_FAILED_ENTRIES_SQL = """
      select session.id as session_id, session.biz_date, entry.id as entry_id,
        entry.status as entry_status, entry.rerun_instance_id
      from batch.batch_day_replay_session session
      join batch.batch_day_replay_entry entry
        on entry.tenant_id=session.tenant_id and entry.session_id=session.id
      where session.tenant_id=? and session.replay_operation_id=? and entry.status='FAILED'
      order by session.biz_date, entry.job_code
      """;
  private static final String UPDATE_DATE_RANGE_REPLAY_WORKFLOW_ENABLED_SQL = """
      update batch.workflow_definition set enabled=? where tenant_id=? and id=?
      """;
  private static final String UPDATE_WORKFLOW_REPLAY_JOB_CROSS_DAY_SQL = """
      update batch.workflow_node set cross_day_dependencies=?::jsonb,
        cross_day_dependency_timeout_seconds=600
      where tenant_id=? and workflow_definition_id=? and node_code='JOB'
      """;
  private static final String INSERT_WORKFLOW_REPLAY_PRIOR_JOB_INSTANCE_SQL = """
      insert into batch.job_instance (
        tenant_id, job_definition_id, trigger_request_id, job_code, instance_no, biz_date,
        trigger_type, instance_status, queue_code, worker_group, priority, dedup_key,
        run_attempt, version, expected_partition_count, success_partition_count,
        failed_partition_count, params_snapshot
      )
      select ?, definition.id, request.id, definition.job_code, ?, ?, 'API', 'SUCCESS',
        'e2e-q', 'DISPATCH', 5, ?, 1, 0, 0, 0, 0, '{}'::jsonb
      from batch.job_definition definition
      join batch.trigger_request request on request.tenant_id=definition.tenant_id
      where definition.tenant_id=? and definition.job_code=? and request.request_id=?
      returning id
      """;
  private static final String UPDATE_WORKFLOW_REPLAY_PRIOR_TRIGGER_SQL = """
      update batch.trigger_request set request_status='LAUNCHED'
      where tenant_id=? and request_id=?
      """;
  private static final String INSERT_WORKFLOW_REPLAY_PRIOR_RESULT_VERSION_SQL = """
      insert into batch.result_version (
        tenant_id, business_key, version_no, job_instance_id, status, effective_at,
        payload_storage, payload_json, generated_at, generated_by, promotion_policy
      ) values (?, ?, 1, ?, 'EFFECTIVE', current_timestamp, 'INLINE_JSON',
        '{"sourceBatch":"replay-prior-day-v1"}'::jsonb, current_timestamp,
        'workflow-replay-lineage-e2e', 'AUTO_LATEST')
      returning id
      """;
  private static final String INSERT_WORKFLOW_REPLAY_NEWER_RESULT_VERSION_SQL = """
      insert into batch.result_version (
        tenant_id, business_key, version_no, job_instance_id, status, payload_storage,
        payload_json, generated_at, generated_by, promotion_policy
      ) values (?, ?, 2, ?, 'PENDING', 'INLINE_JSON',
        '{"sourceBatch":"replay-prior-day-v2"}'::jsonb, current_timestamp,
        'workflow-replay-lineage-e2e-v2', 'MANUAL_APPROVAL')
      returning id
      """;
  private static final String SELECT_WORKFLOW_REPLAY_CROSS_DAY_VERSION_ID_SQL = """
      select params_snapshot->'effectiveParams'->'crossDay'->'prior_day'->>'resultVersionId'
      from batch.job_instance where tenant_id=? and id=?
      """;
  private static final String SELECT_WORKFLOW_REPLAY_CROSS_DAY_SOURCE_BATCH_SQL = """
      select params_snapshot->'effectiveParams'->'crossDay'->'prior_day'->'output'->>'sourceBatch'
      from batch.job_instance where tenant_id=? and id=?
      """;
  private static final String SELECT_WORKFLOW_REPLAY_RESULT_VERSION_STATUS_SQL = """
      select status from batch.result_version where tenant_id=? and id=?
      """;
  private static final String SELECT_EXPORT_WORKER_ONLINE_COUNT_SQL = """
      select count(1)::int from batch.worker_registry
      where tenant_id=? and coalesce(worker_pool_code, worker_code)=?
        and status='ONLINE' and worker_group='EXPORT'
      """;
  private static final String MARK_EXPORT_WORKER_OFFLINE_SQL = """
      update batch.worker_registry set status='OFFLINE'
      where tenant_id=? and worker_code=?
      """;
  private static final String SELECT_LATEST_WORKFLOW_REPLAY_TEMPLATE_VERSION_SQL = """
      select max(version) from batch.file_template_config
      where tenant_id=? and template_code=?
      """;
  private static final String DELETE_WORKFLOW_REPLAY_TEMPLATE_SQL = """
      delete from batch.file_template_config where tenant_id=? and template_code=?
      """;
  private static final String CREATE_WORKFLOW_REPLAY_BATCH_TABLE_SQL = """
      create table biz.%s (
        id bigserial primary key,
        tenant_id varchar(64) not null,
        batch_no varchar(128) not null,
        unique (tenant_id, batch_no)
      )
      """;
  private static final String CREATE_WORKFLOW_REPLAY_DETAIL_TABLE_SQL = """
      create table biz.%s (
        id bigserial primary key,
        batch_id bigint not null references biz.%s(id),
        item_code varchar(64) not null,
        amount numeric(18, 2) not null
      )
      """;
  private static final String INSERT_WORKFLOW_REPLAY_BATCH_SQL = """
      insert into biz.%s (tenant_id, batch_no) values (?, ?)
      returning id
      """;
  private static final String INSERT_WORKFLOW_REPLAY_DETAIL_SQL = """
      insert into biz.%s (batch_id, item_code, amount) values (?, ?, ?)
      """;
  private static final String DROP_WORKFLOW_REPLAY_DETAIL_TABLE_SQL = "drop table if exists biz.%s";
  private static final String DROP_WORKFLOW_REPLAY_BATCH_TABLE_SQL = "drop table if exists biz.%s";
  private static final String SELECT_APPROVAL_STATUS_SQL = """
      select approval_status from batch.approval_command where tenant_id=? and approval_no=?
      """;
  private static final String SELECT_COMPENSATION_COMMAND_SQL = """
      select command_no, target_id, command_status, result_summary::text result_summary
      from batch.compensation_command where tenant_id=? and approval_id=?
      """;
  private static final String SELECT_RERUN_STATUS_SQL = """
      select ji.instance_status, ji.job_definition_version, task.error_message
      from batch.job_instance ji
      join batch.workflow_node_run n on n.job_instance_id=ji.id and n.node_type='JOB'
      join batch.job_task task on task.tenant_id=ji.tenant_id and task.job_instance_id=ji.id
      join batch.workflow_run w on w.id=n.workflow_run_id and w.tenant_id=ji.tenant_id
      join batch.trigger_request tr on tr.id=w.trigger_request_id and tr.tenant_id=w.tenant_id
      where ji.tenant_id=? and tr.request_id=?
        and not exists (
            select 1 from batch.workflow_node_run newer
            where newer.workflow_run_id=n.workflow_run_id
              and newer.node_code=n.node_code
              and newer.run_seq > n.run_seq
        )
      """;
  private static final String COUNT_CUSTOMER_ACCOUNT_SQL = """
      select count(*) from biz.customer_account where tenant_id=? and customer_no=?
      """;
  private static final String COUNT_COMPENSATION_COMMAND_SQL = """
      select count(*) from batch.compensation_command where tenant_id=? and approval_id=?
      """;
  private static final String SELECT_RERUN_INSTANCE_SQL = """
      select ji.id, ji.job_definition_version, n.workflow_run_id
      from batch.job_instance ji
      join batch.workflow_node_run n on n.job_instance_id=ji.id and n.node_type='JOB'
      join batch.workflow_run w on w.id=n.workflow_run_id and w.tenant_id=ji.tenant_id
      join batch.trigger_request tr on tr.id=w.trigger_request_id and tr.tenant_id=w.tenant_id
      where ji.tenant_id=? and tr.request_id=?
        and not exists (
            select 1 from batch.workflow_node_run newer
            where newer.workflow_run_id=n.workflow_run_id
              and newer.node_code=n.node_code
              and newer.run_seq > n.run_seq
        )
      """;
  private static final String SELECT_WORKFLOW_RUN_STATUS_SQL = """
      select run_status from batch.workflow_run where tenant_id=? and id=?
      """;
  private static final String SELECT_INSTANCE_SQL = """
      select id, instance_no, instance_status, calendar_code
      from batch.job_instance where tenant_id=? and dedup_key=?
      """;
  private static final String SELECT_INSTANCE_BY_ID_SQL = """
      select id, instance_no, instance_status
      from batch.job_instance where tenant_id=? and id=?
      """;
  private static final String SELECT_REPLAY_SESSION_STATUS_SQL = """
      select session.status from batch.batch_day_replay_session session
      where session.tenant_id=? and session.id=?
      """;
  private static final String SELECT_REPLAY_ENTRY_SQL = """
      select replay_entry.status, replay_entry.rerun_instance_id
      from batch.batch_day_replay_entry replay_entry
      where replay_entry.tenant_id=? and replay_entry.session_id=?
      """;
  private static final String SELECT_APPROVAL_STATE_SQL = """
      select approval_status, requester_id, action_type, target_type, target_id
      from batch.approval_command where tenant_id=? and approval_no=?
      """;
  private static final String SELECT_FAILED_NODE_SQL = """
      select node_run.id, node_run.job_instance_id, node_run.node_status,
        node_run.skip_reason_code, node_run.skip_reason
      from batch.workflow_node_run node_run
      join batch.workflow_run workflow_run
        on workflow_run.id=node_run.workflow_run_id and workflow_run.tenant_id=?
      where node_run.workflow_run_id=? and node_run.node_code='JOB'
      order by node_run.run_seq desc limit 1
      """;
  private static final String SELECT_SKIP_AUDIT_SQL = """
      select job_instance_id, extra_json::text
      from batch.job_execution_log
      where tenant_id=? and log_type='AUDIT' and message='WORKFLOW_NODE_SKIP'
        and detail_ref='workflow_node_run'
        and extra_json->>'workflowRunId'=? and extra_json->>'nodeRunId'=?
      order by id desc limit 1
      """;
  private static final String SELECT_RUN_STATUS_SQL = """
      select run_status from batch.workflow_run where tenant_id=? and id=?
      """;
  private static final String SELECT_NODE_REPLAY_OPERATION_SQL = """
      select id, status, source_workflow_run_id, source_workflow_node_run_id,
        source_job_instance_id, target_request_id, target_workflow_run_id,
        requested_by, approved_by, total_count, succeeded_count, failed_count, in_flight_count
      from batch.replay_operation
      where tenant_id=? and operation_type='WORKFLOW_NODE'
        and source_workflow_run_id=? and source_workflow_node_run_id=?
      """;
  private static final String SELECT_NODE_REPLAY_APPROVAL_PAYLOAD_SQL = """
      select approval_status, requester_id, approver_id,
        payload_json->>'replayOperationId' replay_operation_id,
        payload_json->>'previewToken' preview_token,
        payload_json->>'operationKey' operation_key
      from batch.approval_command where tenant_id=? and approval_no=?
      """;
  private static final String SELECT_REPLAY_OPERATION_ID_BY_KEY_SQL = """
      select id from batch.replay_operation where tenant_id=? and operation_key=?::uuid
      """;
  private static final String SELECT_WORKFLOW_RUN_ID_BY_REQUEST_SQL = """
      select id from batch.workflow_run where tenant_id=? and trigger_request_id=(
        select id from batch.trigger_request where tenant_id=? and request_id=?)
      """;
  private static final String SELECT_NODE_REPLAY_JOB_SQL = """
      select node_run.job_instance_id, node_run.node_status, workflow_run.run_status
      from batch.workflow_node_run node_run
      join batch.workflow_run workflow_run
        on workflow_run.tenant_id=? and workflow_run.id=node_run.workflow_run_id
      where workflow_run.id=? and node_run.node_type='JOB'
      """;
  private static final String SELECT_SOURCE_WORKFLOW_RUN_ID_SQL = """
      select node_run.workflow_run_id
      from batch.workflow_node_run node_run
      join batch.workflow_run workflow_run
        on workflow_run.id=node_run.workflow_run_id and workflow_run.tenant_id=?
      where node_run.job_instance_id=? and node_run.node_type='JOB'
      order by node_run.run_seq desc limit 1
      """;
  private static final String ADVANCE_JOB_DEFINITION_VERSION_SQL = """
      update batch.job_definition
      set version=version+1
      where tenant_id=? and job_code=? and version=1 and enabled=true
      """;
  private static final String SELECT_JOB_DEFINITION_VERSION_SQL = """
      select version from batch.job_definition where tenant_id=? and job_code=?
      """;
  private static final String SELECT_RERUN_SNAPSHOT_JOB_VERSION_SQL = """
      select (execution_snapshot->'jobDefinitions'->'JOB'->>'jobDefinitionVersion')::integer
      from batch.workflow_run where tenant_id=? and id=?
      """;
  private static final String SELECT_NODE_STATUS_SQL = """
      select node_run.node_status from batch.workflow_node_run node_run
      join batch.workflow_run workflow_run
        on workflow_run.id=node_run.workflow_run_id and workflow_run.tenant_id=?
      where node_run.workflow_run_id=? and node_run.node_code=?
      order by node_run.run_seq desc limit 1
      """;
  private static final String COUNT_NODE_RUNS_SQL = """
      select count(*) from batch.workflow_node_run node_run
      join batch.workflow_run workflow_run
        on workflow_run.id=node_run.workflow_run_id and workflow_run.tenant_id=?
      where node_run.workflow_run_id=? and node_run.node_code=?
      """;
  private static final String UPDATE_WORKFLOW_DEFINITION_FOR_DAG_SQL = """
      update batch.workflow_definition
      set single_job_definition_id=null, version=version+1
      where tenant_id=? and id=?
      """;
  private static final String MOVE_END_NODE_FOR_DAG_SQL = """
      update batch.workflow_node set node_order=3
      where tenant_id=? and workflow_definition_id=? and node_code='END'
      """;
  private static final String INSERT_WAIT_NODE_FOR_DAG_SQL = """
      insert into batch.workflow_node
        (tenant_id,workflow_definition_id,node_code,node_name,node_type,node_params,
         node_order,retry_policy,retry_max_count,timeout_seconds,enabled)
      values (?, ?, 'WAIT_RELEASE', 'Controlled release', 'WAIT', ?::jsonb,
              2, 'NONE', 0, 300, true)
      """;
  private static final String DELETE_WORKFLOW_EDGES_FOR_DAG_SQL = """
      delete from batch.workflow_edge where tenant_id=? and workflow_definition_id=?
      """;
  private static final String INSERT_WORKFLOW_EDGES_FOR_DAG_SQL = """
      insert into batch.workflow_edge
        (tenant_id,workflow_definition_id,from_node_code,to_node_code,edge_type,enabled)
      values (?, ?, 'START', 'JOB', 'ALWAYS', true),
             (?, ?, 'START', 'WAIT_RELEASE', 'ALWAYS', true),
             (?, ?, 'JOB', 'END', 'SUCCESS', true),
             (?, ?, 'WAIT_RELEASE', 'END', 'SUCCESS', true)
      """;

  private final JdbcTemplate jdbc;

  private final BatchObjectStore testObjectStore;

  private final E2eOutboxPublishSupport outboxPublishSupport;

  private final WorkflowRunLaunchService launchService;

  private final ResultVersionPromoteService resultVersionPromoteService;

  private final OrchestratorConfigCacheService configCacheService;

  private final BatchDayReplayService batchDayReplayService;

  private final BatchDayReplayDispatcher batchDayReplayDispatcher;

  private final WorkerRegistryServerService workerRegistryService;

  private final DataSource businessDataSource;

  private final DataSource e2eBusinessDataSource;

  private final ObjectMapper objectMapper;

  @LocalServerPort
  private int orchestratorPort;

  @Autowired
  SelfServiceJobCompensationHttpE2eIT(
      JdbcTemplate jdbc,
      BatchObjectStore testObjectStore,
      E2eOutboxPublishSupport outboxPublishSupport,
      WorkflowRunLaunchService launchService,
      ResultVersionPromoteService resultVersionPromoteService,
      OrchestratorConfigCacheService configCacheService,
      BatchDayReplayService batchDayReplayService,
      BatchDayReplayDispatcher batchDayReplayDispatcher,
      WorkerRegistryServerService workerRegistryService,
      @Qualifier("importBusinessDataSource") DataSource businessDataSource,
      @Qualifier("e2eBusinessDataSource") DataSource e2eBusinessDataSource,
      ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.testObjectStore = testObjectStore;
    this.outboxPublishSupport = outboxPublishSupport;
    this.launchService = launchService;
    this.resultVersionPromoteService = resultVersionPromoteService;
    this.configCacheService = configCacheService;
    this.batchDayReplayService = batchDayReplayService;
    this.batchDayReplayDispatcher = batchDayReplayDispatcher;
    this.workerRegistryService = workerRegistryService;
    this.businessDataSource = businessDataSource;
    this.e2eBusinessDataSource = e2eBusinessDataSource;
    this.objectMapper = objectMapper;
  }

  @ParameterizedTest(name = "failed source={0}, replay before compensation={1}")
  @MethodSource("selfServiceCompensationScenarios")
  @DisplayName("TENANT_USER 补偿成功、失败或重放后的 JobInstance 并按来源配置完成新 Workflow")
  void shouldExecuteAuthenticatedSelfServiceCompensation_asWorkflowRerun(
      boolean sourceInitiallyFailed, boolean replayBeforeSelfServiceCompensation) throws Exception {
    executeSelfServiceCompensationScenario(
        sourceInitiallyFailed, replayBeforeSelfServiceCompensation);
  }

  @Test
  @DisplayName("真实失败来源经 BatchDay replay 成功后仍可完成自助补偿")
  void shouldCompensateReplayedFailedSourceThroughAuthenticatedConsole() throws Exception {
    executeSelfServiceCompensationScenario(true, true);
  }

  @ParameterizedTest(name = "workflow node replay approval granted={0}")
  @ValueSource(booleans = {true, false})
  @DisplayName("真实 Console 审批批准或拒绝失败节点重放并正确收敛 Replay Operation")
  void shouldResolveExactFailedWorkflowNodeReplayApprovalThroughAuthenticatedConsole(
      boolean approvalGranted) throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String customerNo = "NODE_REPLAY_" + suffix;
    String requestId = "node-replay-source-" + suffix;
    LocalDate bizDate = LocalDate.of(2099, 1, 15);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, TENANT, "IMPORT", "import", TriggerType.API, bizDate);
    String repairedContent = """
        [{"customerNo":"%s","customerName":"node replay","customerType":"PERSONAL",
          "certificateNo":"ID-%s","mobileNo":"13800000001","email":"node@example.com","status":"ACTIVE"}]
        """.formatted(customerNo, customerNo);
    CompensationSourceInput sourceInput =
        prepareCompensationSourceInput(suffix, customerNo, repairedContent, true);
    LaunchRequest sourceRequest = new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        bizDate,
        TriggerType.API,
        seed.requestId(),
        requestId,
        sourceInput.launchParams());
    launchService.launch(sourceRequest);
    outboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Map<String, Object> sourceInstance =
              jdbc.queryForMap(SELECT_INSTANCE_SQL, TENANT, seed.dedupKey());
          assertThat(sourceInstance).containsEntry("instance_status", "FAILED");
        });
    Map<String, Object> sourceInstance =
        jdbc.queryForMap(SELECT_INSTANCE_SQL, TENANT, seed.dedupKey());
    long sourceJobInstanceId = ((Number) sourceInstance.get("id")).longValue();
    long sourceRunId = jdbc.queryForObject(
        SELECT_SOURCE_WORKFLOW_RUN_ID_SQL, Long.class, TENANT, sourceJobInstanceId);
    Map<String, Object> sourceNode = jdbc.queryForMap(SELECT_FAILED_NODE_SQL, TENANT, sourceRunId);
    long sourceNodeRunId = ((Number) sourceNode.get("id")).longValue();
    assertThat(sourceNode).containsEntry("node_status", "FAILED");
    repairCompensationObject(sourceInput);

    try (ConfigurableApplicationContext securedConsole = startSecuredConsole()) {
      int consolePort =
          securedConsole.getEnvironment().getProperty("local.server.port", Integer.class);
      WebTestClient client = WebTestClient.bindToServer()
          .baseUrl("http://127.0.0.1:" + consolePort)
          .responseTimeout(Duration.ofSeconds(45))
          .build();
      ConsoleJwtService jwtService = securedConsole.getBean(ConsoleJwtService.class);
      Actor tenantUser =
          authenticate(client, jwtService, "node-replay-tenant-user", "ROLE_TENANT_USER");
      Actor requester = authenticate(client, jwtService, "node-replay-requester", "ROLE_ADMIN");
      Actor approver = authenticate(client, jwtService, "node-replay-approver", "ROLE_ADMIN");

      Map<String, Object> previewRequest = new LinkedHashMap<>();
      previewRequest.put("tenantId", TENANT);
      previewRequest.put("workflowRunId", sourceRunId);
      previewRequest.put("workflowNodeRunId", sourceNodeRunId);
      previewRequest.put("reason", "Replay the exact failed JOB node after source repair");
      post(client, tenantUser, "/api/console/jobs/workflow-node/replay-preview", previewRequest)
          .expectStatus()
          .isForbidden();
      WebTestClient.ResponseSpec previewResponse = post(
              client, requester, "/api/console/jobs/workflow-node/replay-preview", previewRequest)
          .expectStatus()
          .isOk();
      JsonNode preview = response(previewResponse).path("data");
      assertThat(preview.path("sourceWorkflowRunId").asLong()).isEqualTo(sourceRunId);
      assertThat(preview.path("sourceWorkflowNodeRunId").asLong()).isEqualTo(sourceNodeRunId);
      assertThat(preview.path("sourceJobInstanceId").asLong()).isEqualTo(sourceJobInstanceId);
      assertThat(preview.path("resultPolicy").asText()).isEqualTo("CREATE_NEW_VERSION");
      assertThat(preview.path("configVersionPolicy").asText()).isEqualTo("USE_ORIGINAL_CONFIG");
      String operationKey = preview.path("operationKey").asText();
      String previewToken = preview.path("previewToken").asText();
      assertThat(operationKey).isNotBlank();
      assertThat(previewToken).isNotBlank();

      Map<String, Object> replayRequest = new LinkedHashMap<>();
      replayRequest.put("tenantId", TENANT);
      replayRequest.put("compensationType", "WORKFLOW_NODE");
      replayRequest.put("targetId", sourceNodeRunId);
      replayRequest.put("jobCode", preview.path("jobCode").asText());
      replayRequest.put("bizDate", preview.path("bizDate").asText());
      replayRequest.put("sourceWorkflowRunId", sourceRunId);
      replayRequest.put("sourceWorkflowNodeRunId", sourceNodeRunId);
      replayRequest.put("reason", previewRequest.get("reason"));
      replayRequest.put("operationKey", operationKey);
      replayRequest.put("previewToken", previewToken);
      replayRequest.put("resultPolicy", preview.path("resultPolicy").asText());
      replayRequest.put(
          "configVersionPolicy", preview.path("configVersionPolicy").asText());
      WebTestClient.ResponseSpec submitResponse = post(
              client, requester, "/api/console/jobs/compensations", replayRequest)
          .expectStatus()
          .isOk();
      String approvalNo = response(submitResponse).path("data").asText();
      assertThat(approvalNo).isNotBlank();
      WebTestClient.ResponseSpec duplicateSubmitResponse = post(
              client, requester, "/api/console/jobs/compensations", replayRequest)
          .expectStatus()
          .isOk();
      String duplicateApprovalNo =
          response(duplicateSubmitResponse).path("data").asText();
      assertThat(duplicateApprovalNo).isEqualTo(approvalNo);

      Map<String, Object> approvalPayload =
          jdbc.queryForMap(SELECT_NODE_REPLAY_APPROVAL_PAYLOAD_SQL, TENANT, approvalNo);
      Long replayOperationIdValue = jdbc.queryForObject(
          SELECT_REPLAY_OPERATION_ID_BY_KEY_SQL, Long.class, TENANT, operationKey);
      assertThat(replayOperationIdValue).isNotNull();
      assertThat(approvalPayload)
          .containsEntry("approval_status", "PENDING")
          .containsEntry("requester_id", "node-replay-requester")
          .containsEntry("replay_operation_id", replayOperationIdValue.toString())
          .containsEntry("preview_token", previewToken)
          .containsEntry("operation_key", operationKey);
      long replayOperationId = Long.parseLong((String) approvalPayload.get("replay_operation_id"));
      Map<String, Object> operationBeforeApproval =
          jdbc.queryForMap(SELECT_NODE_REPLAY_OPERATION_SQL, TENANT, sourceRunId, sourceNodeRunId);
      assertThat(operationBeforeApproval)
          .containsEntry("id", replayOperationId)
          .containsEntry("status", "PENDING_APPROVAL")
          .containsEntry("requested_by", "node-replay-requester")
          .containsEntry("source_job_instance_id", sourceJobInstanceId);

      Map<String, Object> approvalAction = Map.of(
          "tenantId",
          TENANT,
          "operatorId",
          "node-replay-approver",
          "reason",
          approvalGranted ? "Approve exact failed node replay" : "Reject exact failed node replay");
      String approvalActionPath = approvalGranted ? "approve" : "reject";
      String approvalActionUrl = "/api/console/approvals/" + approvalNo + "/" + approvalActionPath;
      post(client, approver, approvalActionUrl, approvalAction).expectStatus().isOk();
      if (!approvalGranted) {
        post(client, approver, approvalActionUrl, approvalAction).expectStatus().isOk();
        Map<String, Object> rejectedOperation = jdbc.queryForMap(
            SELECT_NODE_REPLAY_OPERATION_SQL, TENANT, sourceRunId, sourceNodeRunId);
        assertThat(rejectedOperation)
            .containsEntry("status", "REJECTED")
            .containsEntry("target_request_id", null)
            .containsEntry("total_count", 1)
            .containsEntry("succeeded_count", 0)
            .containsEntry("failed_count", 0)
            .containsEntry("in_flight_count", 0);
        Map<String, Object> rejectedApproval =
            jdbc.queryForMap(SELECT_NODE_REPLAY_APPROVAL_PAYLOAD_SQL, TENANT, approvalNo);
        assertThat(rejectedApproval).containsEntry("approval_status", "REJECTED");
        int compensationCommandCount =
            jdbc.queryForObject(COUNT_COMPENSATION_COMMAND_SQL, Integer.class, TENANT, approvalNo);
        assertThat(compensationCommandCount).isZero();
        return;
      }
      Map<String, Object> operationAfterApproval =
          jdbc.queryForMap(SELECT_NODE_REPLAY_OPERATION_SQL, TENANT, sourceRunId, sourceNodeRunId);
      assertThat(operationAfterApproval)
          .containsEntry("status", "RUNNING")
          .containsEntry("approved_by", "node-replay-approver");
      String replayRequestId = (String) operationAfterApproval.get("target_request_id");
      assertThat(replayRequestId).isNotBlank();

      await()
          .atMost(Duration.ofSeconds(90))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            outboxPublishSupport.publishAllPending(TENANT);
            List<Long> replayWorkflowRunIds = jdbc.queryForList(
                SELECT_WORKFLOW_RUN_ID_BY_REQUEST_SQL, Long.class, TENANT, TENANT, replayRequestId);
            assertThat(replayWorkflowRunIds).hasSize(1);
            Long replayWorkflowRunId = replayWorkflowRunIds.get(0);
            assertThat(replayWorkflowRunId).isNotNull();
            List<Map<String, Object>> replayJobs =
                jdbc.queryForList(SELECT_NODE_REPLAY_JOB_SQL, TENANT, replayWorkflowRunId);
            assertThat(replayJobs).hasSize(1);
            Map<String, Object> replayJob = replayJobs.get(0);
            assertThat(replayJob.get("node_status")).isEqualTo("SUCCESS");
            assertThat(replayJob.get("run_status")).isEqualTo("SUCCESS");
          });
      Map<String, Object> finalOperation =
          jdbc.queryForMap(SELECT_NODE_REPLAY_OPERATION_SQL, TENANT, sourceRunId, sourceNodeRunId);
      assertThat(finalOperation)
          .containsEntry("status", "SUCCEEDED")
          .containsEntry("source_workflow_run_id", sourceRunId)
          .containsEntry("source_workflow_node_run_id", sourceNodeRunId);
      String selected_WORKFLOW_RUN_STATUS_ =
          jdbc.queryForObject(SELECT_WORKFLOW_RUN_STATUS_SQL, String.class, TENANT, sourceRunId);
      assertThat(selected_WORKFLOW_RUN_STATUS_).isEqualTo("FAILED");
      String selected_APPROVAL_STATUS_ =
          jdbc.queryForObject(SELECT_APPROVAL_STATUS_SQL, String.class, TENANT, approvalNo);
      assertThat(selected_APPROVAL_STATUS_).isEqualTo("EXECUTED");
    }
  }

  @Test
  @DisplayName("真实 Console 整 Workflow Replay 固定配置和预览时跨日版本并经独立 EXPORT Worker 完成")
  void shouldReplayWholeWorkflowThroughAuthenticatedConsoleAndIndependentExportWorker()
      throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String batchNo = "WF_REPLAY_" + suffix;
    String templateCode = "WF_REPLAY_TEMPLATE_" + suffix;
    String batchTable = "wf_replay_batch_" + suffix;
    String detailTable = "wf_replay_detail_" + suffix;
    String workerCode = "r25-export-" + suffix;
    String consumerGroupId = "r25-workflow-replay-" + suffix;
    String sourceTraceId = "r25-workflow-replay-source-" + suffix;
    Path workerLog = Files.createTempFile("r25-workflow-replay-export-", ".log");
    Process exportWorker = null;
    try {
      insertWorkflowReplayExportTemplate(templateCode, 1, batchTable, detailTable);
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbc, TENANT, "EXPORT", "export", TriggerType.API, WORKFLOW_REPLAY_BIZ_DATE);
      LocalDate priorBizDate = WORKFLOW_REPLAY_BIZ_DATE.minusDays(1);
      LaunchSeed priorDaySource = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbc, TENANT, "DISPATCH", "dispatch", TriggerType.API, priorBizDate);
      String priorInstanceNo = "wf-replay-prior-" + suffix;
      String priorDedupKey = "wf-replay-prior-dedup-" + suffix;
      Long priorDayJobInstanceId = jdbc.queryForObject(
          INSERT_WORKFLOW_REPLAY_PRIOR_JOB_INSTANCE_SQL,
          Long.class,
          TENANT,
          priorInstanceNo,
          priorBizDate,
          priorDedupKey,
          TENANT,
          priorDaySource.jobCode(),
          priorDaySource.requestId());
      jdbc.update(UPDATE_WORKFLOW_REPLAY_PRIOR_TRIGGER_SQL, TENANT, priorDaySource.requestId());
      String priorBusinessKey = "job:" + priorDaySource.jobCode() + ":" + priorBizDate;
      Long previewResultVersionId = jdbc.queryForObject(
          INSERT_WORKFLOW_REPLAY_PRIOR_RESULT_VERSION_SQL,
          Long.class,
          TENANT,
          priorBusinessKey,
          priorDayJobInstanceId);
      String crossDayDependenciesJson = """
          [{"alias":"prior_day","jobCode":"%s","bizDateOffset":-1,
            "scope":"REQUIRED","consumeVersionStrategy":"EFFECTIVE_ONLY"}]
          """.formatted(priorDaySource.jobCode());
      jdbc.update(
          UPDATE_WORKFLOW_REPLAY_JOB_CROSS_DAY_SQL,
          crossDayDependenciesJson,
          TENANT,
          seed.workflowDefinitionId());
      exportWorker = startExportWorker(workerCode, consumerGroupId, workerLog);
      awaitExportWorkerStarted(exportWorker, workerLog);
      awaitExportWorkerOnline(workerCode);

      Map<String, Object> launchParams = new LinkedHashMap<>();
      launchParams.put("batchNo", batchNo);
      launchParams.put("templateCode", templateCode);
      launchParams.put("bizDate", WORKFLOW_REPLAY_BIZ_DATE.toString());
      launchParams.put("bizType", "WORKFLOW_REPLAY");
      LaunchRequest sourceRequest = new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          WORKFLOW_REPLAY_BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          sourceTraceId,
          launchParams);
      launchService.launch(sourceRequest);
      outboxPublishSupport.publishAllPending(TENANT);

      await()
          .atMost(Duration.ofSeconds(30))
          .pollInterval(Duration.ofSeconds(1))
          .untilAsserted(() -> {
            List<Long> sourceRunIds = jdbc.queryForList(
                SELECT_WORKFLOW_RUN_ID_BY_REQUEST_SQL,
                Long.class,
                TENANT,
                TENANT,
                seed.requestId());
            assertThat(sourceRunIds).hasSize(1);
            Long sourceRunId = sourceRunIds.get(0);
            List<Map<String, Object>> sourceJobs =
                jdbc.queryForList(SELECT_WORKFLOW_REPLAY_JOB_SQL, TENANT, sourceRunId);
            assertThat(sourceJobs).hasSize(1);
          });
      outboxPublishSupport.publishAllPending(TENANT);

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofSeconds(1))
          .untilAsserted(() -> {
            List<Long> sourceRunIds = jdbc.queryForList(
                SELECT_WORKFLOW_RUN_ID_BY_REQUEST_SQL,
                Long.class,
                TENANT,
                TENANT,
                seed.requestId());
            assertThat(sourceRunIds).hasSize(1);
            Long sourceRunId = sourceRunIds.get(0);
            String sourceStatus = jdbc.queryForObject(
                SELECT_WORKFLOW_RUN_STATUS_SQL, String.class, TENANT, sourceRunId);
            assertThat(sourceStatus).isEqualTo("FAILED");
            List<Map<String, Object>> sourceJobs =
                jdbc.queryForList(SELECT_WORKFLOW_REPLAY_JOB_SQL, TENANT, sourceRunId);
            assertThat(sourceJobs).hasSize(1);
            assertThat(sourceJobs.get(0)).containsEntry("instance_status", "FAILED");
          });
      Long sourceWorkflowRunId = jdbc.queryForObject(
          SELECT_WORKFLOW_RUN_ID_BY_REQUEST_SQL, Long.class, TENANT, TENANT, seed.requestId());
      assertThat(sourceWorkflowRunId).isNotNull();

      createWorkflowReplayBusinessRows(batchTable, detailTable, batchNo);
      insertWorkflowReplayExportTemplate(
          templateCode,
          2,
          "wf_replay_missing_batch_" + suffix,
          "wf_replay_missing_detail_" + suffix);
      Integer latestTemplateVersion = jdbc.queryForObject(
          SELECT_LATEST_WORKFLOW_REPLAY_TEMPLATE_VERSION_SQL, Integer.class, TENANT, templateCode);
      assertThat(latestTemplateVersion).isEqualTo(2);

      try (ConfigurableApplicationContext securedConsole = startSecuredConsole()) {
        WebTestClient client = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:"
                + securedConsole.getEnvironment().getProperty("local.server.port", Integer.class))
            .responseTimeout(Duration.ofSeconds(45))
            .build();
        ConsoleJwtService jwtService = securedConsole.getBean(ConsoleJwtService.class);
        Actor requester =
            authenticate(client, jwtService, "workflow-replay-requester", "ROLE_ADMIN");
        Actor approver = authenticate(client, jwtService, "workflow-replay-approver", "ROLE_ADMIN");

        Map<String, Object> previewRequest = new LinkedHashMap<>();
        previewRequest.put("tenantId", TENANT);
        previewRequest.put("workflowRunId", sourceWorkflowRunId);
        previewRequest.put(
            "reason", "Replay the complete failed workflow after repairing export data");
        previewRequest.put("resultPolicy", "CREATE_NEW_VERSION");
        previewRequest.put("configVersionPolicy", "USE_ORIGINAL_CONFIG");
        JsonNode previewEnvelope = response(
            post(client, requester, "/api/console/jobs/workflow-run/replay-preview", previewRequest)
                .expectStatus()
                .isOk());
        JsonNode preview = previewEnvelope.path("data");
        assertThat(preview.path("sourceWorkflowRunId").asLong()).isEqualTo(sourceWorkflowRunId);
        assertThat(preview.path("bizDate").asText()).isEqualTo(WORKFLOW_REPLAY_BIZ_DATE.toString());
        assertThat(preview.path("nodes").size()).isEqualTo(3);
        assertThat(preview.path("edges").size()).isEqualTo(2);
        JsonNode jobPreview = null;
        for (JsonNode nodePreview : preview.path("nodes")) {
          if ("JOB".equals(nodePreview.path("nodeCode").asText())) {
            jobPreview = nodePreview;
          }
        }
        assertThat(jobPreview).isNotNull();
        assertThat(jobPreview.path("pluginId").asText()).isEqualTo("jdbc_mapped_export");
        assertThat(jobPreview.path("blockers").size())
            .as(
                "replay blockers=%s, capability=%s",
                jobPreview.path("blockers"), jobPreview.path("capability"))
            .isZero();

        String operationKey = preview.path("operationKey").asText();
        String previewToken = preview.path("previewToken").asText();
        assertThat(operationKey).isNotBlank();
        assertThat(previewToken).isNotBlank();
        Map<String, Object> submission = new LinkedHashMap<>(previewRequest);
        submission.put("operationKey", operationKey);
        submission.put("previewToken", previewToken);
        JsonNode submissionEnvelope = response(post(
                client,
                requester,
                "/api/console/jobs/workflow-run/replay-operations",
                submission,
                operationKey)
            .expectStatus()
            .isOk());
        JsonNode submissionResult = submissionEnvelope.path("data");
        String approvalNo = submissionResult.path("approvalNo").asText();
        assertThat(submissionResult.path("replayOperationId").asLong()).isPositive();
        assertThat(approvalNo).isNotBlank();

        Map<String, Object> operationBeforeApproval =
            jdbc.queryForMap(SELECT_WORKFLOW_REPLAY_OPERATION_SQL, TENANT, operationKey);
        assertThat(operationBeforeApproval)
            .containsEntry("status", "PENDING_APPROVAL")
            .containsEntry("source_workflow_run_id", sourceWorkflowRunId)
            .containsEntry("total_count", 1)
            .containsEntry("in_flight_count", 1);
        assertThat(operationBeforeApproval.get("from_date")).isNull();
        assertThat(operationBeforeApproval.get("through_date")).isNull();
        Long newerResultVersionId = jdbc.queryForObject(
            INSERT_WORKFLOW_REPLAY_NEWER_RESULT_VERSION_SQL,
            Long.class,
            TENANT,
            priorBusinessKey,
            priorDayJobInstanceId);
        resultVersionPromoteService.promote(TENANT, newerResultVersionId);
        assertThat(newerResultVersionId).isNotEqualTo(previewResultVersionId);
        String previewResultVersionStatus = jdbc.queryForObject(
            SELECT_WORKFLOW_REPLAY_RESULT_VERSION_STATUS_SQL,
            String.class,
            TENANT,
            previewResultVersionId);
        assertThat(previewResultVersionStatus).isEqualTo("SUPERSEDED");
        Map<String, Object> approvalAction = Map.of(
            "tenantId", TENANT,
            "operatorId", "workflow-replay-approver",
            "reason", "Approve complete workflow replay");
        post(client, approver, "/api/console/approvals/" + approvalNo + "/approve", approvalAction)
            .expectStatus()
            .isOk();

        Map<String, Object> operationAfterApproval =
            jdbc.queryForMap(SELECT_WORKFLOW_REPLAY_OPERATION_SQL, TENANT, operationKey);
        assertThat(operationAfterApproval.get("status")).isEqualTo("RUNNING");
        String replayRequestId = (String) operationAfterApproval.get("target_request_id");
        assertThat(replayRequestId).isNotBlank();

        outboxPublishSupport.publishAllPending(TENANT);
        await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> {
              List<Long> replayRunIds = jdbc.queryForList(
                  SELECT_WORKFLOW_RUN_ID_BY_REQUEST_SQL,
                  Long.class,
                  TENANT,
                  TENANT,
                  replayRequestId);
              assertThat(replayRunIds).hasSize(1);
              List<Map<String, Object>> replayJobs =
                  jdbc.queryForList(SELECT_WORKFLOW_REPLAY_JOB_SQL, TENANT, replayRunIds.get(0));
              assertThat(replayJobs).hasSize(1);
            });
        outboxPublishSupport.publishAllPending(TENANT);

        await()
            .atMost(Duration.ofSeconds(180))
            .pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> {
              List<Long> replayRunIds = jdbc.queryForList(
                  SELECT_WORKFLOW_RUN_ID_BY_REQUEST_SQL,
                  Long.class,
                  TENANT,
                  TENANT,
                  replayRequestId);
              assertThat(replayRunIds).hasSize(1);
              Long replayRunId = replayRunIds.get(0);
              List<Map<String, Object>> replayJobs =
                  jdbc.queryForList(SELECT_WORKFLOW_REPLAY_JOB_SQL, TENANT, replayRunId);
              assertThat(replayJobs).hasSize(1);
              assertThat(replayJobs.get(0))
                  .containsEntry("node_status", "SUCCESS")
                  .containsEntry("instance_status", "SUCCESS")
                  .containsEntry("run_status", "SUCCESS");
              Map<String, Object> completedOperation =
                  jdbc.queryForMap(SELECT_WORKFLOW_REPLAY_OPERATION_SQL, TENANT, operationKey);
              assertThat(completedOperation)
                  .containsEntry("status", "SUCCEEDED")
                  .containsEntry("succeeded_count", 1)
                  .containsEntry("failed_count", 0)
                  .containsEntry("in_flight_count", 0);
            });

        List<Long> replayRunIds = jdbc.queryForList(
            SELECT_WORKFLOW_RUN_ID_BY_REQUEST_SQL, Long.class, TENANT, TENANT, replayRequestId);
        Long replayRunId = replayRunIds.get(0);
        Map<String, Object> replayJob =
            jdbc.queryForMap(SELECT_WORKFLOW_REPLAY_JOB_SQL, TENANT, replayRunId);
        Long replayJobInstanceId = ((Number) replayJob.get("job_instance_id")).longValue();
        String replayCrossDayVersionId = jdbc.queryForObject(
            SELECT_WORKFLOW_REPLAY_CROSS_DAY_VERSION_ID_SQL,
            String.class,
            TENANT,
            replayJobInstanceId);
        assertThat(replayCrossDayVersionId).isEqualTo(previewResultVersionId.toString());
        String replayCrossDaySourceBatch = jdbc.queryForObject(
            SELECT_WORKFLOW_REPLAY_CROSS_DAY_SOURCE_BATCH_SQL,
            String.class,
            TENANT,
            replayJobInstanceId);
        assertThat(replayCrossDaySourceBatch).isEqualTo("replay-prior-day-v1");
        List<Map<String, Object>> exportFiles = jdbc.queryForList(
            SELECT_WORKFLOW_REPLAY_EXPORT_FILE_SQL, TENANT, batchNo, templateCode);
        assertThat(exportFiles).hasSize(1);
        Map<String, Object> exportFile = exportFiles.get(0);
        assertThat(exportFile.get("file_status")).isEqualTo("GENERATED");
        assertThat(((Number) exportFile.get("file_size_bytes")).longValue()).isPositive();
        assertThat(exportFile.get("checksum")).isNotNull();
        assertThat(exportFile.get("template_code")).isEqualTo(templateCode);

        List<Map<String, Object>> resultVersions = jdbc.queryForList(
            SELECT_WORKFLOW_REPLAY_RESULT_VERSION_SQL, TENANT, replayJobInstanceId);
        assertThat(resultVersions).hasSize(1);
        assertThat(resultVersions.get(0))
            .containsEntry("status", "EFFECTIVE")
            .containsEntry("job_instance_id", replayJobInstanceId)
            .containsEntry("payload_storage", "INLINE_JSON");
        String selected_WORKFLOW_RUN_STATUS_ = jdbc.queryForObject(
            SELECT_WORKFLOW_RUN_STATUS_SQL, String.class, TENANT, sourceWorkflowRunId);
        assertThat(selected_WORKFLOW_RUN_STATUS_).isEqualTo("FAILED");
        String selected_APPROVAL_STATUS_ =
            jdbc.queryForObject(SELECT_APPROVAL_STATUS_SQL, String.class, TENANT, approvalNo);
        assertThat(selected_APPROVAL_STATUS_).isEqualTo("EXECUTED");

        WorkerHeartbeatDto unknownCapabilityRegistration = WorkerHeartbeatDto.builder()
            .tenantId(TENANT)
            .workerCode(workerCode)
            .workerGroup("EXPORT")
            .replayCapabilities(List.of(
                ReplayCapabilityDeclaration.unknown(WorkerPluginIds.EXPORT_DATA_JDBC_MAPPED)))
            .build();
        var reRegisteredWorker = workerRegistryService.register(unknownCapabilityRegistration);
        assertThat(reRegisteredWorker.workerCode()).isEqualTo(workerCode);

        Map<String, Object> blockedPreviewRequest = new LinkedHashMap<>(previewRequest);
        blockedPreviewRequest.put(
            "reason", "Verify UNKNOWN Worker capability blocks whole Workflow replay");
        JsonNode blockedPreviewEnvelope = response(post(
                client,
                requester,
                "/api/console/jobs/workflow-run/replay-preview",
                blockedPreviewRequest)
            .expectStatus()
            .isOk());
        JsonNode blockedPreview = blockedPreviewEnvelope.path("data");
        JsonNode blockedJobPreview = null;
        for (JsonNode nodePreview : blockedPreview.path("nodes")) {
          if ("JOB".equals(nodePreview.path("nodeCode").asText())) {
            blockedJobPreview = nodePreview;
          }
        }
        assertThat(blockedJobPreview).isNotNull();
        String blockedPluginId = blockedJobPreview.path("pluginId").asText();
        assertThat(blockedPluginId).isEqualTo(WorkerPluginIds.EXPORT_DATA_JDBC_MAPPED);
        JsonNode blockedCapability = blockedJobPreview.path("capability");
        String blockedCapabilityStatus = blockedCapability.path("status").asText();
        assertThat(blockedCapabilityStatus).isEqualTo("REPLAY_SAFETY_UNPROVEN");
        assertThat(blockedJobPreview.path("blockers").toString())
            .contains("REPLAY_SAFETY_UNPROVEN");

        String blockedOperationKey = blockedPreview.path("operationKey").asText();
        String blockedPreviewToken = blockedPreview.path("previewToken").asText();
        Map<String, Object> blockedSubmission = new LinkedHashMap<>(blockedPreviewRequest);
        blockedSubmission.put("operationKey", blockedOperationKey);
        blockedSubmission.put("previewToken", blockedPreviewToken);
        post(
                client,
                requester,
                "/api/console/jobs/workflow-run/replay-operations",
                blockedSubmission,
                blockedOperationKey)
            .expectStatus()
            .isEqualTo(409);
        Integer blockedOperationCount = jdbc.queryForObject(
            COUNT_WORKFLOW_REPLAY_OPERATION_BY_KEY_SQL, Integer.class, TENANT, blockedOperationKey);
        assertThat(blockedOperationCount).isZero();
        Integer unconsumedPreviewTokenCount = jdbc.queryForObject(
            COUNT_UNCONSUMED_WORKFLOW_REPLAY_PREVIEW_TOKEN_BY_KEY_SQL,
            Integer.class,
            TENANT,
            blockedOperationKey);
        assertThat(unconsumedPreviewTokenCount).isEqualTo(1);
      }
    } finally {
      stopWorkerProcess(exportWorker);
      jdbc.update(MARK_EXPORT_WORKER_OFFLINE_SQL, TENANT, workerCode);
      dropWorkflowReplayBusinessTables(batchTable, detailTable);
      jdbc.update(DELETE_WORKFLOW_REPLAY_TEMPLATE_SQL, TENANT, templateCode);
      Files.deleteIfExists(workerLog);
    }
  }

  @Test
  @DisplayName("整图重放独立执行同一 Job 的失败与成功节点,不合并节点身份和导出产物")
  void shouldReplayBothOccurrencesOfSameJob_whenSourceWorkflowHasMixedOutcomes() throws Exception {
    final String expandWorkflowSql = """
        update batch.workflow_definition set single_job_definition_id=null where tenant_id=? and id=?
        """;
    final String insertRepeatedJobNodeSql = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type,
            related_job_code, worker_group, node_order, retry_policy, retry_max_count,
            timeout_seconds, node_params, enabled
        ) select tenant_id, workflow_definition_id, 'JOB_SUCCESS', '已成功的重复 Job 节点',
                 node_type, related_job_code, worker_group, 2, retry_policy, retry_max_count,
                 timeout_seconds, jsonb_build_object('batchNo', ?), enabled
          from batch.workflow_node
          where tenant_id=? and workflow_definition_id=? and node_code='JOB'
        """;
    final String setFailedNodeBatchSql = """
        update batch.workflow_node set node_params=jsonb_build_object('batchNo', ?)
        where tenant_id=? and workflow_definition_id=? and node_code='JOB'
        """;
    final String updateFailureJoinSql = """
        update batch.workflow_edge set edge_type='ALWAYS'
        where tenant_id=? and workflow_definition_id=? and from_node_code='JOB' and to_node_code='END'
        """;
    final String insertRepeatedJobEdgesSql = """
        insert into batch.workflow_edge (
            tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
        ) values (?, ?, 'START', 'JOB_SUCCESS', 'ALWAYS', true),
                 (?, ?, 'JOB_SUCCESS', 'END', 'ALWAYS', true)
        """;
    final String selectNodeArtifactsSql = """
        select node.node_code, node.id as node_run_id, job.id as job_instance_id, job.job_code,
               node.node_status, job.instance_status, job.biz_date,
               file.id as file_id, file.source_ref, file.storage_bucket, file.storage_path
        from batch.workflow_run run
        join batch.workflow_node_run node on node.workflow_run_id=run.id and node.node_type='JOB'
        join batch.job_instance job on job.tenant_id=run.tenant_id and job.id=node.job_instance_id
        left join batch.pipeline_instance pipeline
          on pipeline.tenant_id=job.tenant_id and pipeline.related_job_instance_id=job.id
        left join batch.file_record file on file.tenant_id=pipeline.tenant_id and file.id=pipeline.file_id
        where run.tenant_id=? and run.id=? order by node.node_code
        """;
    final String selectOwnedObjectsSql = """
        select storage_bucket, storage_path from batch.file_record
        where tenant_id=? and source_ref in (?, ?) and metadata_json->>'templateCode'=?
        """;
    final String selectReplaySourceRunSql = """
        select execution_snapshot->>'sourceWorkflowRunId' from batch.workflow_run
        where tenant_id=? and id=?
        """;
    final String updateSuccessfulSourceDataSql = """
        update biz.%s set item_code=?, amount=? where item_code='R25-ITEM'
        """;
    String suffix = UUID.randomUUID().toString().replace("-", "");
    LocalDate bizDate = WORKFLOW_REPLAY_BIZ_DATE.plusDays(2);
    String failedBatchNo = "WF_TWO_FAILED_" + suffix;
    String successfulBatchNo = "WF_TWO_SUCCESS_" + suffix;
    String templateCode = "WF_TWO_TEMPLATE_" + suffix;
    String batchTable = "wf_two_batch_" + suffix;
    String detailTable = "wf_two_detail_" + suffix;
    String workerCode = "r25-two-export-" + suffix;
    String consumerGroupId = "r25-two-replay-" + suffix;
    Path workerLog = Files.createTempFile("r25-two-job-replay-", ".log");
    Process exportWorker = null;
    try {
      insertWorkflowReplayExportTemplate(templateCode, 1, batchTable, detailTable);
      createWorkflowReplayBusinessRows(batchTable, detailTable, successfulBatchNo);
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbc, TENANT, "EXPORT", "export", TriggerType.API, bizDate);
      jdbc.update(expandWorkflowSql, TENANT, seed.workflowDefinitionId());
      jdbc.update(setFailedNodeBatchSql, failedBatchNo, TENANT, seed.workflowDefinitionId());
      int insertedNode = jdbc.update(
          insertRepeatedJobNodeSql, successfulBatchNo, TENANT, seed.workflowDefinitionId());
      assertThat(insertedNode).isEqualTo(1);
      jdbc.update(updateFailureJoinSql, TENANT, seed.workflowDefinitionId());
      jdbc.update(
          insertRepeatedJobEdgesSql,
          TENANT,
          seed.workflowDefinitionId(),
          TENANT,
          seed.workflowDefinitionId());
      exportWorker = startExportWorker(workerCode, consumerGroupId, workerLog);
      awaitExportWorkerStarted(exportWorker, workerLog);
      awaitExportWorkerOnline(workerCode);
      Map<String, Object> sourceParams = Map.of(
          "templateCode",
          templateCode,
          "bizDate",
          bizDate.toString(),
          "bizType",
          "WORKFLOW_REPLAY");
      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          bizDate,
          TriggerType.API,
          seed.requestId(),
          "r25-two-source-" + suffix,
          sourceParams));
      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofSeconds(1))
          .untilAsserted(() -> {
            outboxPublishSupport.publishAllPending(TENANT);
            Long sourceRunId = jdbc.queryForObject(
                SELECT_WORKFLOW_RUN_ID_BY_REQUEST_SQL,
                Long.class,
                TENANT,
                TENANT,
                seed.requestId());
            String sourceStatus = jdbc.queryForObject(
                SELECT_WORKFLOW_RUN_STATUS_SQL, String.class, TENANT, sourceRunId);
            assertThat(sourceStatus)
                .as("source Worker log: %s", workerLog)
                .isIn("FAILED", "SUCCESS");
          });
      Long sourceRunId = jdbc.queryForObject(
          SELECT_WORKFLOW_RUN_ID_BY_REQUEST_SQL, Long.class, TENANT, TENANT, seed.requestId());
      List<Map<String, Object>> sourceNodes =
          jdbc.queryForList(selectNodeArtifactsSql, TENANT, sourceRunId);
      assertThat(sourceNodes).hasSize(2);
      assertThat(sourceNodes)
          .extracting(node -> node.get("instance_status"))
          .containsExactly("FAILED", "SUCCESS");
      assertThat(sourceNodes).extracting(node -> node.get("job_code")).containsOnly(seed.jobCode());
      Map<String, Object> successfulSourceNode = sourceNodes.get(1);
      assertThat(successfulSourceNode.get("file_id")).isNotNull();
      String originalBucket = (String) successfulSourceNode.get("storage_bucket");
      String originalKey = (String) successfulSourceNode.get("storage_path");
      byte[] originalSuccessfulArtifact = readObject(originalBucket, originalKey);
      JdbcTemplate businessJdbc = new JdbcTemplate(e2eBusinessDataSource);
      String repairSuccessfulSourceSql = updateSuccessfulSourceDataSql.formatted(detailTable);
      int updatedSuccessfulSource = businessJdbc.update(
          repairSuccessfulSourceSql, "R25-ITEM-REPLAY", new BigDecimal("33.75"));
      assertThat(updatedSuccessfulSource).isEqualTo(1);
      String insertBatchSql = INSERT_WORKFLOW_REPLAY_BATCH_SQL.formatted(batchTable);
      Long repairedBatchId =
          businessJdbc.queryForObject(insertBatchSql, Long.class, TENANT, failedBatchNo);
      String insertDetailSql = INSERT_WORKFLOW_REPLAY_DETAIL_SQL.formatted(detailTable);
      businessJdbc.update(
          insertDetailSql, repairedBatchId, "R25-RECOVERED", new BigDecimal("22.50"));

      try (ConfigurableApplicationContext securedConsole = startSecuredConsole()) {
        Integer consolePort =
            securedConsole.getEnvironment().getProperty("local.server.port", Integer.class);
        WebTestClient client = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:" + consolePort)
            .responseTimeout(Duration.ofSeconds(45))
            .build();
        ConsoleJwtService jwtService = securedConsole.getBean(ConsoleJwtService.class);
        Actor requester =
            authenticate(client, jwtService, "two-job-requester-" + suffix, "ROLE_ADMIN");
        Actor approver =
            authenticate(client, jwtService, "two-job-approver-" + suffix, "ROLE_ADMIN");
        Map<String, Object> previewRequest = new LinkedHashMap<>();
        previewRequest.put("tenantId", TENANT);
        previewRequest.put("workflowRunId", sourceRunId);
        previewRequest.put(
            "reason", "Replay both nodes including the successful occurrence of the same Job");
        previewRequest.put("resultPolicy", "CREATE_NEW_VERSION");
        previewRequest.put("configVersionPolicy", "USE_ORIGINAL_CONFIG");
        JsonNode previewEnvelope = response(
            post(client, requester, "/api/console/jobs/workflow-run/replay-preview", previewRequest)
                .expectStatus()
                .isOk());
        JsonNode preview = previewEnvelope.path("data");
        assertThat(preview.path("nodes")).hasSize(4);
        int jobPreviewCount = 0;
        for (JsonNode node : preview.path("nodes")) {
          if ("JOB".equals(node.path("nodeType").asText())) {
            jobPreviewCount++;
            assertThat(node.path("jobCode").asText()).isEqualTo(seed.jobCode());
            assertThat(node.path("blockers")).as("node=%s", node).isEmpty();
          }
        }
        assertThat(jobPreviewCount).isEqualTo(2);
        String operationKey = preview.path("operationKey").asText();
        Map<String, Object> submission = new LinkedHashMap<>(previewRequest);
        submission.put("operationKey", operationKey);
        submission.put("previewToken", preview.path("previewToken").asText());
        JsonNode submissionEnvelope = response(post(
                client,
                requester,
                "/api/console/jobs/workflow-run/replay-operations",
                submission,
                operationKey)
            .expectStatus()
            .isOk());
        String approvalNo = submissionEnvelope.path("data").path("approvalNo").asText();
        assertThat(approvalNo).isNotBlank();
        post(
                client,
                approver,
                "/api/console/approvals/" + approvalNo + "/approve",
                Map.of("tenantId", TENANT, "reason", "Approve complete graph replay"))
            .expectStatus()
            .isOk();
        Map<String, Object> operation =
            jdbc.queryForMap(SELECT_WORKFLOW_REPLAY_OPERATION_SQL, TENANT, operationKey);
        String replayRequestId = (String) operation.get("target_request_id");
        assertThat(replayRequestId).isNotBlank();
        await()
            .atMost(Duration.ofSeconds(180))
            .pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> {
              outboxPublishSupport.publishAllPending(TENANT);
              List<Long> replayRunIds = jdbc.queryForList(
                  SELECT_WORKFLOW_RUN_ID_BY_REQUEST_SQL,
                  Long.class,
                  TENANT,
                  TENANT,
                  replayRequestId);
              assertThat(replayRunIds).hasSize(1);
              Long replayRunId = replayRunIds.getFirst();
              String replayStatus = jdbc.queryForObject(
                  SELECT_WORKFLOW_RUN_STATUS_SQL, String.class, TENANT, replayRunId);
              assertThat(replayStatus)
                  .as("replay Worker log: %s", workerLog)
                  .isIn("SUCCESS", "FAILED");
            });
        Long replayRunId = jdbc.queryForObject(
            SELECT_WORKFLOW_RUN_ID_BY_REQUEST_SQL, Long.class, TENANT, TENANT, replayRequestId);
        List<Map<String, Object>> replayNodes =
            jdbc.queryForList(selectNodeArtifactsSql, TENANT, replayRunId);
        String replayStatus =
            jdbc.queryForObject(SELECT_WORKFLOW_RUN_STATUS_SQL, String.class, TENANT, replayRunId);
        assertThat(replayStatus).isEqualTo("SUCCESS");
        assertThat(replayNodes).hasSize(2);
        assertThat(replayNodes).allSatisfy(node -> {
          assertThat(node.get("instance_status")).isEqualTo("SUCCESS");
          assertThat(node.get("file_id")).isNotNull();
        });
        assertThat(replayNodes)
            .extracting(node -> node.get("job_instance_id"))
            .doesNotHaveDuplicates();
        assertThat(replayNodes).extracting(node -> node.get("file_id")).doesNotHaveDuplicates();
        assertThat(replayNodes)
            .extracting(node -> node.get("source_ref"))
            .containsExactly(failedBatchNo, successfulBatchNo);
        for (int index = 0; index < replayNodes.size(); index++) {
          Map<String, Object> sourceNode = sourceNodes.get(index);
          Map<String, Object> replayNode = replayNodes.get(index);
          assertThat(replayNode.get("node_code")).isEqualTo(sourceNode.get("node_code"));
          assertThat(replayNode.get("job_instance_id"))
              .isNotEqualTo(sourceNode.get("job_instance_id"));
          assertThat(replayNode.get("biz_date").toString()).isEqualTo(bizDate.toString());
          String bucket = (String) replayNode.get("storage_bucket");
          String key = (String) replayNode.get("storage_path");
          byte[] exportedBytes = readObject(bucket, key);
          String exportedContent = new String(exportedBytes, StandardCharsets.UTF_8);
          assertThat(exportedContent).contains(index == 0 ? "R25-RECOVERED" : "R25-ITEM-REPLAY");
        }
        List<Map<String, Object>> sourceAfterReplay =
            jdbc.queryForList(selectNodeArtifactsSql, TENANT, sourceRunId);
        assertThat(sourceAfterReplay).isEqualTo(sourceNodes);
        byte[] sourceArtifactAfterReplay = readObject(originalBucket, originalKey);
        assertThat(sourceArtifactAfterReplay).isEqualTo(originalSuccessfulArtifact);
        assertThat(replayNodes.get(1).get("file_id"))
            .isNotEqualTo(successfulSourceNode.get("file_id"));
        Map<String, Object> completed =
            jdbc.queryForMap(SELECT_WORKFLOW_REPLAY_OPERATION_SQL, TENANT, operationKey);
        assertThat(completed)
            .containsEntry("status", "SUCCEEDED")
            .containsEntry("target_workflow_run_id", replayRunId)
            .containsEntry("source_workflow_run_id", sourceRunId);
        String frozenSourceRunId =
            jdbc.queryForObject(selectReplaySourceRunSql, String.class, TENANT, replayRunId);
        assertThat(frozenSourceRunId).isEqualTo(sourceRunId.toString());
      }
    } finally {
      stopWorkerProcess(exportWorker);
      jdbc.update(MARK_EXPORT_WORKER_OFFLINE_SQL, TENANT, workerCode);
      List<Map<String, Object>> ownedObjects = jdbc.queryForList(
          selectOwnedObjectsSql, TENANT, failedBatchNo, successfulBatchNo, templateCode);
      for (Map<String, Object> object : ownedObjects) {
        String bucket = (String) object.get("storage_bucket");
        String key = (String) object.get("storage_path");
        testObjectStore.delete(bucket, key);
        boolean objectRemains = testObjectStore.exists(bucket, key);
        assertThat(objectRemains).isFalse();
      }
      dropWorkflowReplayBusinessTables(batchTable, detailTable);
      jdbc.update(DELETE_WORKFLOW_REPLAY_TEMPLATE_SQL, TENANT, templateCode);
    }
  }

  @Test
  @DisplayName("真实 Console 日期区间 Replay 经独立 EXPORT Worker 完成两个失败业务日")
  void shouldReplayFailedDateRangeThroughAuthenticatedConsoleAndIndependentExportWorker()
      throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    LocalDate firstBizDate = LocalDate.of(2099, 2, 2);
    LocalDate secondBizDate = firstBizDate.plusDays(1);
    LocalDate thirdBizDate = secondBizDate.plusDays(1);
    String firstBatchNo = "WF_RANGE_A_" + suffix;
    String secondBatchNo = "WF_RANGE_B_" + suffix;
    String thirdBatchNo = "WF_RANGE_C_" + suffix;
    String templateCode = "WF_RANGE_TEMPLATE_" + suffix;
    String batchTable = "wf_range_batch_" + suffix;
    String detailTable = "wf_range_detail_" + suffix;
    String workerCode = "r25-range-export-" + suffix;
    String consumerGroupId = "r25-range-replay-" + suffix;
    Path workerLog = Files.createTempFile("r25-range-replay-export-", ".log");
    Process exportWorker = null;
    try {
      insertWorkflowReplayExportTemplate(templateCode, 1, batchTable, detailTable);
      LaunchSeed firstSource = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbc, TENANT, "EXPORT", "export", TriggerType.API, firstBizDate);
      LaunchSeed secondSource = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbc, TENANT, "EXPORT", "export", TriggerType.API, secondBizDate);
      LaunchSeed thirdSource = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbc, TENANT, "EXPORT", "export", TriggerType.API, thirdBizDate);

      exportWorker = startExportWorker(workerCode, consumerGroupId, workerLog);
      awaitExportWorkerStarted(exportWorker, workerLog);
      awaitExportWorkerOnline(workerCode);
      launchWorkflowReplayExportSource(firstSource, firstBizDate, firstBatchNo, templateCode);
      awaitFailedWorkflowExportSource(firstSource.requestId());
      launchWorkflowReplayExportSource(secondSource, secondBizDate, secondBatchNo, templateCode);
      awaitFailedWorkflowExportSource(secondSource.requestId());
      launchWorkflowReplayExportSource(thirdSource, thirdBizDate, thirdBatchNo, templateCode);
      awaitFailedWorkflowExportSource(thirdSource.requestId());

      createWorkflowReplayBusinessRows(batchTable, detailTable, firstBatchNo);
      JdbcTemplate businessJdbc = new JdbcTemplate(e2eBusinessDataSource);
      String insertBatchSql = INSERT_WORKFLOW_REPLAY_BATCH_SQL.formatted(batchTable);
      Long secondBatchId =
          businessJdbc.queryForObject(insertBatchSql, Long.class, TENANT, secondBatchNo);
      assertThat(secondBatchId).isNotNull();
      String insertDetailSql = INSERT_WORKFLOW_REPLAY_DETAIL_SQL.formatted(detailTable);
      int insertedDetails = businessJdbc.update(
          insertDetailSql, secondBatchId, "R25-RANGE-ITEM-B", new BigDecimal("18.25"));
      assertThat(insertedDetails).isEqualTo(1);
      Long thirdBatchId =
          businessJdbc.queryForObject(insertBatchSql, Long.class, TENANT, thirdBatchNo);
      assertThat(thirdBatchId).isNotNull();
      int insertedThirdDetails = businessJdbc.update(
          insertDetailSql, thirdBatchId, "R25-RANGE-ITEM-C", new BigDecimal("27.50"));
      assertThat(insertedThirdDetails).isEqualTo(1);

      try (ConfigurableApplicationContext securedConsole = startSecuredConsole()) {
        WebTestClient client = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:"
                + securedConsole.getEnvironment().getProperty("local.server.port", Integer.class))
            .responseTimeout(Duration.ofSeconds(45))
            .build();
        ConsoleJwtService jwtService = securedConsole.getBean(ConsoleJwtService.class);
        Actor requester =
            authenticate(client, jwtService, "range-replay-requester-" + suffix, "ROLE_ADMIN");
        String approverName = "range-replay-approver-" + suffix;
        Actor approver = authenticate(client, jwtService, approverName, "ROLE_ADMIN");

        Map<String, Object> rangeRequest = new LinkedHashMap<>();
        rangeRequest.put("tenantId", TENANT);
        rangeRequest.put("calendarCode", "e2e_calendar");
        rangeRequest.put("fromDate", firstBizDate.toString());
        rangeRequest.put("throughDate", thirdBizDate.toString());
        rangeRequest.put("scope", "SUBSET_JOB_CODES");
        rangeRequest.put(
            "jobCodes",
            List.of(firstSource.jobCode(), secondSource.jobCode(), thirdSource.jobCode()));
        rangeRequest.put("resultPolicy", "CREATE_NEW_VERSION");
        rangeRequest.put("configVersionPolicy", "USE_ORIGINAL_CONFIG");
        rangeRequest.put("reason", "Replay two failed business dates after export data repair");
        rangeRequest.put("requestedBy", "range-replay-requester-" + suffix);
        rangeRequest.put("autoApprove", false);
        rangeRequest.put("executionMode", "REPLAY");
        rangeRequest.put("candidateSource", "EXISTING_INSTANCES");

        JsonNode previewEnvelope = response(post(
                client,
                requester,
                "/api/console/ops/batch-day-replay/operations/date-range/preview",
                rangeRequest)
            .expectStatus()
            .isOk());
        JsonNode preview = previewEnvelope.path("data");
        assertThat(preview.path("totalCandidateCount").asInt()).isEqualTo(3);
        assertThat(preview.path("days")).hasSize(3);
        assertThat(preview.path("days").get(0).path("bizDate").asText())
            .isEqualTo(firstBizDate.toString());
        assertThat(preview.path("days").get(0).path("candidateCount").asInt()).isEqualTo(1);
        assertThat(preview.path("days").get(1).path("bizDate").asText())
            .isEqualTo(secondBizDate.toString());
        assertThat(preview.path("days").get(1).path("candidateCount").asInt()).isEqualTo(1);
        assertThat(preview.path("days").get(2).path("bizDate").asText())
            .isEqualTo(thirdBizDate.toString());
        assertThat(preview.path("days").get(2).path("candidateCount").asInt()).isEqualTo(1);

        String operationKey = preview.path("operationKey").asText();
        String previewToken = preview.path("previewToken").asText();
        assertThat(operationKey).isNotBlank();
        assertThat(previewToken).isNotBlank();
        Map<String, Object> submission = new LinkedHashMap<>(rangeRequest);
        submission.put("operationKey", operationKey);
        submission.put("previewToken", previewToken);
        response(post(
                client,
                requester,
                "/api/console/ops/batch-day-replay/operations/date-range",
                submission,
                operationKey)
            .expectStatus()
            .isOk());

        Map<String, Object> operationBeforeApproval =
            jdbc.queryForMap(SELECT_DATE_RANGE_REPLAY_OPERATION_SQL, TENANT, operationKey);
        assertThat(operationBeforeApproval)
            .containsEntry("status", "PENDING_APPROVAL")
            .containsEntry("total_count", 3)
            .containsEntry("in_flight_count", 3);
        assertThat(operationBeforeApproval.get("from_date").toString())
            .isEqualTo(firstBizDate.toString());
        assertThat(operationBeforeApproval.get("through_date").toString())
            .isEqualTo(thirdBizDate.toString());
        Long operationId = ((Number) operationBeforeApproval.get("id")).longValue();
        List<Map<String, Object>> childSessions =
            jdbc.queryForList(SELECT_DATE_RANGE_REPLAY_SESSIONS_SQL, TENANT, operationId);
        assertThat(childSessions).hasSize(3);
        assertThat(childSessions)
            .extracting(session -> session.get("status"))
            .containsExactly("PENDING_APPROVAL", "PENDING_APPROVAL", "PENDING_APPROVAL");

        client
            .post()
            .uri(uriBuilder -> uriBuilder
                .path("/api/console/ops/batch-day-replay/operations/{operationId}/approve")
                .queryParam("tenantId", TENANT)
                .queryParam("approver", approverName)
                .build(operationId))
            .cookie("batch_console_token", approver.jwt())
            .cookie("XSRF-TOKEN", approver.csrf())
            .header("X-XSRF-TOKEN", approver.csrf())
            .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
            .header(CommonConstants.DEFAULT_OPERATOR_ID_HEADER, "spoofed-operator")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "range-approve-" + operationKey)
            .exchange()
            .expectStatus()
            .isOk();

        Map<String, Object> operationAfterApproval =
            jdbc.queryForMap(SELECT_DATE_RANGE_REPLAY_OPERATION_SQL, TENANT, operationKey);
        assertThat(operationAfterApproval)
            .containsEntry("status", "RUNNING")
            .containsEntry("approved_by", approverName);

        try {
          int disabledFirstWorkflow = jdbc.update(
              UPDATE_DATE_RANGE_REPLAY_WORKFLOW_ENABLED_SQL,
              false,
              TENANT,
              firstSource.workflowDefinitionId());
          int disabledSecondWorkflow = jdbc.update(
              UPDATE_DATE_RANGE_REPLAY_WORKFLOW_ENABLED_SQL,
              false,
              TENANT,
              secondSource.workflowDefinitionId());
          assertThat(disabledFirstWorkflow).isEqualTo(1);
          assertThat(disabledSecondWorkflow).isEqualTo(1);

          batchDayReplayDispatcher.scheduledDispatch();
          outboxPublishSupport.publishAllPending(TENANT);
          await()
              .atMost(Duration.ofSeconds(240))
              .pollInterval(Duration.ofSeconds(1))
              .untilAsserted(() -> {
                batchDayReplayDispatcher.scheduledDispatch();
                outboxPublishSupport.publishAllPending(TENANT);
                Map<String, Object> failedOperation =
                    jdbc.queryForMap(SELECT_DATE_RANGE_REPLAY_OPERATION_SQL, TENANT, operationKey);
                assertThat(failedOperation)
                    .containsEntry("status", "PARTIAL_FAILED")
                    .containsEntry("total_count", 3)
                    .containsEntry("succeeded_count", 1)
                    .containsEntry("failed_count", 2)
                    .containsEntry("in_flight_count", 0);
                List<Map<String, Object>> failedEntries = jdbc.queryForList(
                    SELECT_DATE_RANGE_REPLAY_ENTRIES_FOR_RETRY_SQL, TENANT, operationId);
                assertThat(failedEntries).hasSize(3);
                assertThat(failedEntries)
                    .extracting(entry -> entry.get("session_status"))
                    .containsExactly("PARTIAL_FAILED", "PARTIAL_FAILED", "SUCCEEDED");
                assertThat(failedEntries)
                    .extracting(entry -> entry.get("entry_status"))
                    .containsExactly("FAILED", "FAILED", "SUCCEEDED");
                List<Map<String, Object>> unboundFailedEntries = jdbc.queryForList(
                    SELECT_DATE_RANGE_REPLAY_FAILED_ENTRIES_SQL, TENANT, operationId);
                assertThat(unboundFailedEntries).hasSize(2);
                assertThat(unboundFailedEntries)
                    .allSatisfy(
                        entry -> assertThat(entry.get("rerun_instance_id")).isNull());
                assertThat(failedEntries.get(2).get("rerun_instance_id")).isNotNull();
              });
        } finally {
          int enabledFirstWorkflow = jdbc.update(
              UPDATE_DATE_RANGE_REPLAY_WORKFLOW_ENABLED_SQL,
              true,
              TENANT,
              firstSource.workflowDefinitionId());
          int enabledSecondWorkflow = jdbc.update(
              UPDATE_DATE_RANGE_REPLAY_WORKFLOW_ENABLED_SQL,
              true,
              TENANT,
              secondSource.workflowDefinitionId());
          assertThat(enabledFirstWorkflow).isEqualTo(1);
          assertThat(enabledSecondWorkflow).isEqualTo(1);
        }

        List<Map<String, Object>> failedEntries =
            jdbc.queryForList(SELECT_DATE_RANGE_REPLAY_FAILED_ENTRIES_SQL, TENANT, operationId);
        assertThat(failedEntries).hasSize(2);
        Map<String, Object> firstFailedEntry = failedEntries.get(0);
        Map<String, Object> secondFailedEntry = failedEntries.get(1);
        Number firstSessionIdValue = (Number) firstFailedEntry.get("session_id");
        Number firstEntryIdValue = (Number) firstFailedEntry.get("entry_id");
        Number secondSessionIdValue = (Number) secondFailedEntry.get("session_id");
        Number secondEntryIdValue = (Number) secondFailedEntry.get("entry_id");
        long firstSessionId = firstSessionIdValue.longValue();
        long firstEntryId = firstEntryIdValue.longValue();
        long secondSessionId = secondSessionIdValue.longValue();
        long secondEntryId = secondEntryIdValue.longValue();
        CountDownLatch retriesReady = new CountDownLatch(2);
        CountDownLatch beginRetries = new CountDownLatch(1);
        ExecutorService retryExecutor = Executors.newFixedThreadPool(2);
        try {
          Future<JsonNode> firstRetry = retryExecutor.submit(() -> retryDateRangeReplayEntry(
              client,
              requester,
              firstSessionId,
              firstEntryId,
              "range-retry-" + operationKey + "-first",
              "Retry first failed date-range entry",
              retriesReady,
              beginRetries));
          Future<JsonNode> secondRetry = retryExecutor.submit(() -> retryDateRangeReplayEntry(
              client,
              requester,
              secondSessionId,
              secondEntryId,
              "range-retry-" + operationKey + "-second",
              "Retry second failed date-range entry",
              retriesReady,
              beginRetries));
          boolean bothRetriesReady = retriesReady.await(10, TimeUnit.SECONDS);
          assertThat(bothRetriesReady).isTrue();
          beginRetries.countDown();
          JsonNode firstRetryResponse = firstRetry.get(60, TimeUnit.SECONDS);
          JsonNode secondRetryResponse = secondRetry.get(60, TimeUnit.SECONDS);
          JsonNode firstRetryData = firstRetryResponse.path("data");
          JsonNode secondRetryData = secondRetryResponse.path("data");
          String firstRetryStatus = firstRetryData.path("status").asText();
          String secondRetryStatus = secondRetryData.path("status").asText();
          assertThat(firstRetryStatus).isEqualTo("RUNNING");
          assertThat(secondRetryStatus).isEqualTo("RUNNING");
        } finally {
          beginRetries.countDown();
          retryExecutor.shutdownNow();
        }

        Map<String, Object> operationAfterConcurrentRetry =
            jdbc.queryForMap(SELECT_DATE_RANGE_REPLAY_OPERATION_SQL, TENANT, operationKey);
        assertThat(operationAfterConcurrentRetry)
            .containsEntry("status", "RUNNING")
            .containsEntry("total_count", 3)
            .containsEntry("succeeded_count", 1)
            .containsEntry("failed_count", 0)
            .containsEntry("in_flight_count", 2);
        List<Map<String, Object>> entriesAfterRetry =
            jdbc.queryForList(SELECT_DATE_RANGE_REPLAY_ENTRIES_FOR_RETRY_SQL, TENANT, operationId);
        assertThat(entriesAfterRetry)
            .extracting(entry -> entry.get("entry_status"))
            .containsExactly("PENDING", "PENDING", "SUCCEEDED");
        List<Map<String, Object>> unboundEntriesAfterRetry =
            jdbc.queryForList(SELECT_DATE_RANGE_REPLAY_FAILED_ENTRIES_SQL, TENANT, operationId);
        assertThat(unboundEntriesAfterRetry).isEmpty();
        assertThat(entriesAfterRetry.subList(0, 2))
            .allSatisfy(entry -> assertThat(entry.get("rerun_instance_id")).isNull());
        assertThat(entriesAfterRetry.get(2).get("rerun_instance_id")).isNotNull();

        batchDayReplayDispatcher.scheduledDispatch();
        outboxPublishSupport.publishAllPending(TENANT);

        await()
            .atMost(Duration.ofSeconds(240))
            .pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> {
              outboxPublishSupport.publishAllPending(TENANT);
              Map<String, Object> completedOperation =
                  jdbc.queryForMap(SELECT_DATE_RANGE_REPLAY_OPERATION_SQL, TENANT, operationKey);
              assertThat(completedOperation)
                  .containsEntry("status", "SUCCEEDED")
                  .containsEntry("total_count", 3)
                  .containsEntry("succeeded_count", 3)
                  .containsEntry("failed_count", 0)
                  .containsEntry("in_flight_count", 0);
            });

        List<Map<String, Object>> completedSessions =
            jdbc.queryForList(SELECT_DATE_RANGE_REPLAY_SESSIONS_SQL, TENANT, operationId);
        assertThat(completedSessions)
            .extracting(session -> session.get("status"))
            .containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED");
        assertThat(completedSessions)
            .extracting(session -> session.get("biz_date").toString())
            .containsExactly(
                firstBizDate.toString(), secondBizDate.toString(), thirdBizDate.toString());

        List<Map<String, Object>> replayEntries =
            jdbc.queryForList(SELECT_DATE_RANGE_REPLAY_ENTRIES_SQL, TENANT, operationId);
        assertThat(replayEntries).hasSize(3);
        assertThat(replayEntries)
            .extracting(entry -> entry.get("entry_status"))
            .containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED");
        assertThat(replayEntries)
            .extracting(entry -> entry.get("source_status"))
            .containsExactly("FAILED", "FAILED", "FAILED");
        assertThat(replayEntries)
            .extracting(entry -> entry.get("rerun_status"))
            .containsExactly("SUCCESS", "SUCCESS", "SUCCESS");
        assertThat(replayEntries)
            .extracting(entry -> entry.get("rerun_workflow_status"))
            .containsExactly("SUCCESS", "SUCCESS", "SUCCESS");
        assertThat(replayEntries)
            .extracting(entry -> entry.get("rerun_biz_date").toString())
            .containsExactly(
                firstBizDate.toString(), secondBizDate.toString(), thirdBizDate.toString());
        assertThat(replayEntries)
            .extracting(entry -> entry.get("source_instance_id"))
            .doesNotContainNull();
        assertThat(replayEntries)
            .extracting(entry -> entry.get("rerun_instance_id"))
            .doesNotContainNull();

        List<Map<String, Object>> firstExportFiles = jdbc.queryForList(
            SELECT_WORKFLOW_REPLAY_EXPORT_FILE_SQL, TENANT, firstBatchNo, templateCode);
        List<Map<String, Object>> secondExportFiles = jdbc.queryForList(
            SELECT_WORKFLOW_REPLAY_EXPORT_FILE_SQL, TENANT, secondBatchNo, templateCode);
        List<Map<String, Object>> thirdExportFiles = jdbc.queryForList(
            SELECT_WORKFLOW_REPLAY_EXPORT_FILE_SQL, TENANT, thirdBatchNo, templateCode);
        assertThat(firstExportFiles).hasSize(1);
        assertThat(firstExportFiles.get(0).get("file_status")).isEqualTo("GENERATED");
        assertThat(((Number) firstExportFiles.get(0).get("file_size_bytes")).longValue())
            .isPositive();
        assertThat(firstExportFiles.get(0).get("checksum")).isNotNull();
        assertThat(secondExportFiles).hasSize(1);
        assertThat(secondExportFiles.get(0).get("file_status")).isEqualTo("GENERATED");
        assertThat(((Number) secondExportFiles.get(0).get("file_size_bytes")).longValue())
            .isPositive();
        assertThat(secondExportFiles.get(0).get("checksum")).isNotNull();
        assertThat(thirdExportFiles).hasSize(1);
        assertThat(thirdExportFiles.get(0).get("file_status")).isEqualTo("GENERATED");
        assertThat(((Number) thirdExportFiles.get(0).get("file_size_bytes")).longValue())
            .isPositive();
        assertThat(thirdExportFiles.get(0).get("checksum")).isNotNull();
      }
    } finally {
      stopWorkerProcess(exportWorker);
      jdbc.update(MARK_EXPORT_WORKER_OFFLINE_SQL, TENANT, workerCode);
      dropWorkflowReplayBusinessTables(batchTable, detailTable);
      jdbc.update(DELETE_WORKFLOW_REPLAY_TEMPLATE_SQL, TENANT, templateCode);
      Files.deleteIfExists(workerLog);
    }
  }

  private void launchWorkflowReplayExportSource(
      LaunchSeed seed, LocalDate bizDate, String batchNo, String templateCode) {
    Map<String, Object> launchParams = new LinkedHashMap<>();
    launchParams.put("batchNo", batchNo);
    launchParams.put("templateCode", templateCode);
    launchParams.put("bizDate", bizDate.toString());
    launchParams.put("bizType", "WORKFLOW_REPLAY");
    LaunchRequest request = new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        bizDate,
        TriggerType.API,
        seed.requestId(),
        "r25-range-source-" + seed.requestId(),
        launchParams);
    launchService.launch(request);
    outboxPublishSupport.publishAllPending(TENANT);
  }

  private void awaitFailedWorkflowExportSource(String requestId) {
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> {
          List<Long> sourceRunIds = jdbc.queryForList(
              SELECT_WORKFLOW_RUN_ID_BY_REQUEST_SQL, Long.class, TENANT, TENANT, requestId);
          assertThat(sourceRunIds).hasSize(1);
          Long sourceRunId = sourceRunIds.get(0);
          String selected_WORKFLOW_RUN_STATUS_ = jdbc.queryForObject(
              SELECT_WORKFLOW_RUN_STATUS_SQL, String.class, TENANT, sourceRunId);
          assertThat(selected_WORKFLOW_RUN_STATUS_).isEqualTo("FAILED");
          List<Map<String, Object>> sourceJobs =
              jdbc.queryForList(SELECT_WORKFLOW_REPLAY_JOB_SQL, TENANT, sourceRunId);
          assertThat(sourceJobs).hasSize(1);
          assertThat(sourceJobs.get(0))
              .containsEntry("instance_status", "FAILED")
              .containsEntry("run_status", "FAILED");
        });
  }

  private JsonNode retryDateRangeReplayEntry(
      WebTestClient client,
      Actor actor,
      long sessionId,
      long entryId,
      String idempotencyKey,
      String reason,
      CountDownLatch retriesReady,
      CountDownLatch beginRetries)
      throws Exception {
    retriesReady.countDown();
    boolean retriesReleased = beginRetries.await(10, TimeUnit.SECONDS);
    if (!retriesReleased) {
      throw new IllegalStateException("Concurrent date-range replay retries were not released");
    }

    WebTestClient.ResponseSpec responseSpec = client
        .post()
        .uri(uriBuilder -> uriBuilder
            .path(
                "/api/console/ops/batch-day-replay/sessions/{sessionId}/entries/{entryId}/retry-submission")
            .queryParam("tenantId", TENANT)
            .queryParam("reason", reason)
            .build(sessionId, entryId))
        .cookie("batch_console_token", actor.jwt())
        .cookie("XSRF-TOKEN", actor.csrf())
        .header("X-XSRF-TOKEN", actor.csrf())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .header(CommonConstants.DEFAULT_OPERATOR_ID_HEADER, "spoofed-operator")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .exchange()
        .expectStatus()
        .isOk();
    JsonNode responseBody = response(responseSpec);
    return responseBody;
  }

  private void executeSelfServiceCompensationScenario(
      boolean sourceInitiallyFailed, boolean replayBeforeSelfServiceCompensation) throws Exception {
    LocalDate scenarioBizDate = WORKFLOW_REPLAY_BIZ_DATE.plusWeeks(1);
    if (sourceInitiallyFailed) {
      scenarioBizDate = WORKFLOW_REPLAY_BIZ_DATE.plusWeeks(2);
    }
    if (replayBeforeSelfServiceCompensation) {
      scenarioBizDate = WORKFLOW_REPLAY_BIZ_DATE.plusWeeks(3);
    }
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String customerNo = "SELF_SERVICE_" + suffix;
    String requestId = "self-service-source-" + suffix;
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, TENANT, "IMPORT", "import", TriggerType.API, scenarioBizDate);
    String customerName = "self service";
    String customerType = "PERSONAL";
    String certificateNo = "ID-" + customerNo;
    String mobileNo = "13800000001";
    String email = "self-service@example.com";
    String status = "ACTIVE";
    String repairedImportContentTemplate = """
        [{"customerNo":"%s","customerName":"%s","customerType":"%s",
          "certificateNo":"%s","mobileNo":"%s","email":"%s","status":"%s"}]
        """;
    String repairedImportContent = repairedImportContentTemplate.formatted(
        customerNo, customerName, customerType, certificateNo, mobileNo, email, status);
    CompensationSourceInput sourceInput = prepareCompensationSourceInput(
        suffix, customerNo, repairedImportContent, sourceInitiallyFailed);
    LaunchRequest sourceLaunchRequest = new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        scenarioBizDate,
        TriggerType.API,
        seed.requestId(),
        requestId,
        sourceInput.launchParams());
    launchService.launch(sourceLaunchRequest);
    outboxPublishSupport.publishAllPending(TENANT);

    String expectedSourceStatus = sourceInitiallyFailed ? "FAILED" : "SUCCESS";
    ThrowingRunnable sourceInstanceCompleted = () -> {
      Map<String, Object> currentSourceInstance =
          jdbc.queryForMap(SELECT_INSTANCE_SQL, TENANT, seed.dedupKey());
      assertThat(currentSourceInstance).containsEntry("instance_status", expectedSourceStatus);
    };
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(sourceInstanceCompleted);
    Map<String, Object> sourceInstance =
        jdbc.queryForMap(SELECT_INSTANCE_SQL, TENANT, seed.dedupKey());
    long sourceInstanceId = ((Number) sourceInstance.get("id")).longValue();
    Long sourceWorkflowRunId = jdbc.queryForObject(
        SELECT_SOURCE_WORKFLOW_RUN_ID_SQL, Long.class, TENANT, sourceInstanceId);
    Map<String, Object> sourceJobNode =
        jdbc.queryForMap(SELECT_FAILED_NODE_SQL, TENANT, sourceWorkflowRunId);
    String sourceWorkflowStatus = jdbc.queryForObject(
        SELECT_WORKFLOW_RUN_STATUS_SQL, String.class, TENANT, sourceWorkflowRunId);
    assertThat(sourceJobNode).containsEntry("node_status", expectedSourceStatus);
    assertThat(sourceWorkflowStatus).isEqualTo(expectedSourceStatus);

    Map<String, Object> compensationSourceInstance = sourceInstance;
    long compensationSourceWorkflowRunId = sourceWorkflowRunId;
    Long replaySessionId = null;
    if (replayBeforeSelfServiceCompensation) {
      repairCompensationObject(sourceInput);
      replaySessionId = replayFailedSource(seed, sourceInstance, scenarioBizDate);
      Map<String, Object> replayEntry =
          jdbc.queryForMap(SELECT_REPLAY_ENTRY_SQL, TENANT, replaySessionId);
      assertThat(replayEntry).containsEntry("status", "SUCCEEDED");
      Number replayedInstanceIdValue = (Number) replayEntry.get("rerun_instance_id");
      long replayedInstanceId = replayedInstanceIdValue.longValue();
      compensationSourceInstance =
          jdbc.queryForMap(SELECT_INSTANCE_BY_ID_SQL, TENANT, replayedInstanceId);
      assertThat(compensationSourceInstance).containsEntry("instance_status", "SUCCESS");
      Long replayedWorkflowRunId = jdbc.queryForObject(
          SELECT_SOURCE_WORKFLOW_RUN_ID_SQL, Long.class, TENANT, replayedInstanceId);
      compensationSourceWorkflowRunId = replayedWorkflowRunId;
    }
    String compensationSourceInstanceNo = (String) compensationSourceInstance.get("instance_no");
    long compensationSourceInstanceId = ((Number) compensationSourceInstance.get("id")).longValue();

    ConfigurableApplicationContext startedConsole = startSecuredConsole();
    try (ConfigurableApplicationContext securedConsole = startedConsole) {
      BatchSecurityProperties securedConsoleSecurity =
          securedConsole.getBean(BatchSecurityProperties.class);
      assertThat(securedConsoleSecurity.isBypassMode()).isFalse();
      int consolePort =
          securedConsole.getEnvironment().getProperty("local.server.port", Integer.class);
      WebTestClient client = WebTestClient.bindToServer()
          .baseUrl("http://127.0.0.1:" + consolePort)
          .responseTimeout(Duration.ofSeconds(45))
          .build();
      ConsoleJwtService jwtService = securedConsole.getBean(ConsoleJwtService.class);
      Actor requester =
          authenticate(client, jwtService, "self-service-requester", "ROLE_TENANT_USER");
      Actor approver = authenticate(client, jwtService, "self-service-approver", "ROLE_ADMIN");
      Actor auditor = authenticate(client, jwtService, "self-service-auditor", "ROLE_AUDITOR");

      String requestPath = "/api/console/self-service/jobs/compensation-request";
      Map<String, Object> request = Map.of(
          "tenantId",
          TENANT,
          "jobCode",
          seed.jobCode(),
          "bizDate",
          scenarioBizDate.toString(),
          "compensationType",
          "JOB",
          "targetInstanceNo",
          compensationSourceInstanceNo,
          "reason",
          "self-service compensation after source " + expectedSourceStatus.toLowerCase());
      WebTestClient.ResponseSpec createRequestResponse =
          post(client, requester, requestPath, request).expectStatus().isOk();
      JsonNode created = response(createRequestResponse);
      String approvalNo = created.path("data").asText();
      assertThat(approvalNo).isNotBlank();

      Map<String, Object> pending = jdbc.queryForMap(SELECT_APPROVAL_STATE_SQL, TENANT, approvalNo);
      assertThat(pending)
          .containsEntry("approval_status", "PENDING")
          .containsEntry("requester_id", "self-service-requester")
          .containsEntry("action_type", "COMPENSATION")
          .containsEntry("target_type", "JOB_INSTANCE")
          .containsEntry("target_id", String.valueOf(compensationSourceInstanceId));

      String approvePath = "/api/console/approvals/" + approvalNo + "/approve";
      Map<String, Object> approvalAction = Map.of(
          "tenantId", TENANT,
          "operatorId", "self-service-approver",
          "reason", "independent approval for exact source instance");
      post(client, auditor, approvePath, approvalAction).expectStatus().isForbidden();
      post(client, requester, approvePath, approvalAction).expectStatus().isForbidden();
      String statusAfterDeniedApproval =
          jdbc.queryForObject(SELECT_APPROVAL_STATUS_SQL, String.class, TENANT, approvalNo);
      assertThat(statusAfterDeniedApproval).isEqualTo("PENDING");

      WebTestClient.ResponseSpec approvalResponse =
          post(client, approver, approvePath, approvalAction).expectStatus().isOk();
      JsonNode approved = response(approvalResponse);
      assertThat(approved.path("data").asText()).isNotBlank();
      Map<String, Object> command =
          jdbc.queryForMap(SELECT_COMPENSATION_COMMAND_SQL, TENANT, approvalNo);
      assertThat(command)
          .containsEntry("command_status", "SUCCESS")
          .containsEntry("target_id", compensationSourceInstance.get("id"));
      JsonNode commandResult = objectMapper.readTree((String) command.get("result_summary"));
      assertThat(commandResult.path("sourceInstanceNo").asText())
          .isEqualTo(compensationSourceInstanceNo);
      String rerunRequestId = commandResult.path("launchRequestId").asText();
      assertThat(rerunRequestId)
          .as("compensation result summary %s", commandResult)
          .isNotBlank();
      String statusAfterApproval =
          jdbc.queryForObject(SELECT_APPROVAL_STATUS_SQL, String.class, TENANT, approvalNo);
      assertThat(statusAfterApproval).isEqualTo("EXECUTED");

      int advancedDefinitionCount =
          jdbc.update(ADVANCE_JOB_DEFINITION_VERSION_SQL, TENANT, seed.jobCode());
      assertThat(advancedDefinitionCount).isEqualTo(1);
      Integer latestJobDefinitionVersion = jdbc.queryForObject(
          SELECT_JOB_DEFINITION_VERSION_SQL, Integer.class, TENANT, seed.jobCode());
      assertThat(latestJobDefinitionVersion).isEqualTo(2);
      configCacheService.evictJobDefinition(TENANT, seed.jobCode());
      if (sourceInitiallyFailed && !replayBeforeSelfServiceCompensation) {
        repairCompensationObject(sourceInput);
      }

      ThrowingRunnable rerunCompleted = () -> {
        outboxPublishSupport.publishAllPending(TENANT);
        List<Map<String, Object>> currentRerunInstances =
            jdbc.queryForList(SELECT_RERUN_STATUS_SQL, TENANT, rerunRequestId);
        assertThat(currentRerunInstances).hasSize(1);
        String currentRerunStatus =
            (String) currentRerunInstances.getFirst().get("instance_status");
        String currentRerunErrorMessage =
            (String) currentRerunInstances.getFirst().get("error_message");
        assertThat(currentRerunStatus)
            .as("rerun instance=%s, task error=%s", currentRerunInstances, currentRerunErrorMessage)
            .isEqualTo("SUCCESS");
        Integer currentRerunDefinitionVersion =
            (Integer) currentRerunInstances.getFirst().get("job_definition_version");
        assertThat(currentRerunDefinitionVersion).isEqualTo(1);
      };
      await()
          .atMost(Duration.ofSeconds(90))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(rerunCompleted);
      Map<String, Object> rerunInstance =
          jdbc.queryForMap(SELECT_RERUN_INSTANCE_SQL, TENANT, rerunRequestId);
      long rerunInstanceId = ((Number) rerunInstance.get("id")).longValue();
      assertThat(rerunInstanceId).isNotEqualTo(compensationSourceInstanceId);
      long rerunWorkflowRunId = ((Number) rerunInstance.get("workflow_run_id")).longValue();
      Integer frozenJobDefinitionVersion = jdbc.queryForObject(
          SELECT_RERUN_SNAPSHOT_JOB_VERSION_SQL, Integer.class, TENANT, rerunWorkflowRunId);
      assertThat(frozenJobDefinitionVersion).isEqualTo(1);
      String rerunWorkflowStatus = jdbc.queryForObject(
          SELECT_WORKFLOW_RUN_STATUS_SQL, String.class, TENANT, rerunWorkflowRunId);
      assertThat(rerunWorkflowStatus).isEqualTo("SUCCESS");
      String sourceWorkflowStatusAfterRerun = jdbc.queryForObject(
          SELECT_WORKFLOW_RUN_STATUS_SQL, String.class, TENANT, compensationSourceWorkflowRunId);
      String expectedCompensationSourceRunStatus =
          replayBeforeSelfServiceCompensation ? "SUCCESS" : expectedSourceStatus;
      assertThat(sourceWorkflowStatusAfterRerun).isEqualTo(expectedCompensationSourceRunStatus);
      String sourceNodeStatusAfterRerun = jdbc.queryForObject(
          SELECT_NODE_STATUS_SQL, String.class, TENANT, compensationSourceWorkflowRunId, "JOB");
      assertThat(sourceNodeStatusAfterRerun).isEqualTo(expectedCompensationSourceRunStatus);
      String originalSourceWorkflowStatusAfterRerun = jdbc.queryForObject(
          SELECT_WORKFLOW_RUN_STATUS_SQL, String.class, TENANT, sourceWorkflowRunId);
      assertThat(originalSourceWorkflowStatusAfterRerun).isEqualTo(expectedSourceStatus);
      if (replaySessionId != null) {
        String replaySessionStatusAfterCompensation = jdbc.queryForObject(
            SELECT_REPLAY_SESSION_STATUS_SQL, String.class, TENANT, replaySessionId);
        assertThat(replaySessionStatusAfterCompensation).isEqualTo("SUCCEEDED");
      }
      JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
      Integer customerRows = businessJdbc.queryForObject(
          COUNT_CUSTOMER_ACCOUNT_SQL, Integer.class, TENANT, customerNo);
      assertThat(customerRows).isEqualTo(1);
      Integer compensationCommandCount =
          jdbc.queryForObject(COUNT_COMPENSATION_COMMAND_SQL, Integer.class, TENANT, approvalNo);
      assertThat(compensationCommandCount).isEqualTo(1);
    }
  }

  private static Stream<Arguments> selfServiceCompensationScenarios() {
    return Stream.of(Arguments.of(false, false), Arguments.of(true, false));
  }

  private Long replayFailedSource(
      LaunchSeed seed, Map<String, Object> failedSourceInstance, LocalDate scenarioBizDate) {
    String calendarCode = (String) failedSourceInstance.get("calendar_code");
    String replayTraceId = "self-service-replay-" + UUID.randomUUID();
    BatchDayReplaySubmitCommand previewCommand =
        replayCommand(seed.jobCode(), calendarCode, scenarioBizDate, replayTraceId, null);
    var preview = batchDayReplayService.preview(previewCommand);
    long failedSourceInstanceId = ((Number) failedSourceInstance.get("id")).longValue();
    assertThat(preview.entries())
        .singleElement()
        .satisfies(entry -> assertThat(entry.sourceInstanceId()).isEqualTo(failedSourceInstanceId));

    BatchDayReplaySubmitCommand submitCommand = replayCommand(
        seed.jobCode(), calendarCode, scenarioBizDate, replayTraceId, preview.previewToken());
    var session = batchDayReplayService.submit(submitCommand);
    batchDayReplayDispatcher.scheduledDispatch();
    ThrowingRunnable replayCompleted = () -> {
      outboxPublishSupport.publishAllPending(TENANT);
      String currentSessionStatus =
          jdbc.queryForObject(SELECT_REPLAY_SESSION_STATUS_SQL, String.class, TENANT, session.id());
      assertThat(currentSessionStatus).isEqualTo("SUCCEEDED");
    };
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(replayCompleted);
    return session.id();
  }

  private static BatchDayReplaySubmitCommand replayCommand(
      String jobCode,
      String calendarCode,
      LocalDate scenarioBizDate,
      String traceId,
      String previewToken) {
    return BatchDayReplaySubmitCommand.builder()
        .tenantId(TENANT)
        .calendarCode(calendarCode)
        .bizDate(scenarioBizDate)
        .scope("SUBSET_JOB_CODES")
        .jobCodes(List.of(jobCode))
        .resultPolicy("CREATE_NEW_VERSION")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .reason("replay failed source before self-service compensation")
        .requestedBy("self-service-replay-e2e")
        .autoApprove(true)
        .traceId(traceId)
        .executionMode("REPLAY")
        .candidateSource("EXISTING_INSTANCES")
        .previewToken(previewToken)
        .build();
  }

  private void repairCompensationObject(CompensationSourceInput sourceInput) {
    byte[] repairedFileContent = sourceInput.repairedFileContent();
    String storageBucket = sourceInput.storageBucket();
    String storagePath = sourceInput.storagePath();
    putObject(storageBucket, storagePath, repairedFileContent);
  }

  @Test
  @DisplayName("真实多角色 Console 只允许管理员跳过失败 JOB 并持久化准确目标、操作者和理由")
  void shouldSkipFailedJobNodeThroughAuthenticatedConsoleAndPersistAudit() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, TENANT, "IMPORT", "import", TriggerType.API, BIZ_DATE);
    String requestId = "manual-skip-" + suffix;
    Map<String, Object> failedImportParams = Map.of(
        "fileFormatType", "JSON",
        "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
        "bizType", "CUSTOMER",
        "content", "this is not valid JSON");
    LaunchRequest launchRequest = new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        BIZ_DATE,
        TriggerType.API,
        seed.requestId(),
        requestId,
        failedImportParams);
    LaunchResponse launchResponse = launchService.launch(launchRequest);
    String workflowRunReference = launchResponse.instanceNo();
    long workflowRunId = Long.parseLong(workflowRunReference);
    outboxPublishSupport.publishAllPending(TENANT);

    ThrowingRunnable failedJobNode = () -> {
      Map<String, Object> currentNode =
          jdbc.queryForMap(SELECT_FAILED_NODE_SQL, TENANT, workflowRunId);
      assertThat(currentNode).containsEntry("node_status", "FAILED");
    };
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(failedJobNode);

    ConfigurableApplicationContext startedConsole = startSecuredConsole();
    try (ConfigurableApplicationContext securedConsole = startedConsole) {
      int consolePort =
          securedConsole.getEnvironment().getProperty("local.server.port", Integer.class);
      String consoleBaseUrl = "http://127.0.0.1:" + consolePort;
      WebTestClient client = WebTestClient.bindToServer()
          .baseUrl(consoleBaseUrl)
          .responseTimeout(Duration.ofSeconds(45))
          .build();
      ConsoleJwtService jwtService = securedConsole.getBean(ConsoleJwtService.class);
      Actor administrator = authenticate(client, jwtService, "manual-skip-admin", "ROLE_ADMIN");
      Actor auditor = authenticate(client, jwtService, "manual-skip-auditor", "ROLE_AUDITOR");
      String reason = "skip failed import after operator verified upstream data";
      UriComponentsBuilder skipNodeUriBuilder = UriComponentsBuilder.fromUriString(consoleBaseUrl);
      skipNodeUriBuilder
          .path("/api/console/workflow-runs/{id}/skip-node")
          .queryParam("tenantId", TENANT)
          .queryParam("nodeCode", "JOB")
          .queryParam("reason", reason);
      URI skipNodeUri =
          skipNodeUriBuilder.buildAndExpand(workflowRunId).encode().toUri();

      WebTestClient.ResponseSpec deniedResponse = postSkipNode(client, auditor, skipNodeUri);
      deniedResponse.expectStatus().isForbidden();
      Map<String, Object> nodeAfterDeniedRequest =
          jdbc.queryForMap(SELECT_FAILED_NODE_SQL, TENANT, workflowRunId);
      assertThat(nodeAfterDeniedRequest).containsEntry("node_status", "FAILED");

      WebTestClient.ResponseSpec skipResponse = postSkipNode(client, administrator, skipNodeUri);
      skipResponse.expectStatus().isOk();
      JsonNode skippedNodeResponse = response(skipResponse);
      JsonNode skippedNodeData = skippedNodeResponse.path("data");
      long responseWorkflowRunId = skippedNodeData.path("id").asLong();
      String responseNodeCode = skippedNodeData.path("nodeCode").asText();
      String responseNodeStatus = skippedNodeData.path("nodeStatus").asText();
      assertThat(responseWorkflowRunId).isEqualTo(workflowRunId);
      assertThat(responseNodeCode).isEqualTo("JOB");
      assertThat(responseNodeStatus).isEqualTo("SKIPPED");

      Map<String, Object> skippedNode =
          jdbc.queryForMap(SELECT_FAILED_NODE_SQL, TENANT, workflowRunId);
      assertThat(skippedNode)
          .containsEntry("node_status", "SKIPPED")
          .containsEntry("skip_reason_code", "OPERATOR_SKIPPED")
          .containsEntry("skip_reason", reason);
      long nodeRunId = ((Number) skippedNode.get("id")).longValue();
      long jobInstanceId = ((Number) skippedNode.get("job_instance_id")).longValue();
      Map<String, Object> skipAuditRow = jdbc.queryForMap(
          SELECT_SKIP_AUDIT_SQL, TENANT, String.valueOf(workflowRunId), String.valueOf(nodeRunId));
      String auditJson = (String) skipAuditRow.get("extra_json");
      long auditedJobInstanceId = ((Number) skipAuditRow.get("job_instance_id")).longValue();
      JsonNode audit = objectMapper.readTree(auditJson);
      long auditedWorkflowRunId = audit.path("workflowRunId").asLong();
      long auditedNodeRunId = audit.path("nodeRunId").asLong();
      String auditedOperatorId = audit.path("operatorId").asText();
      String auditedOperatorType = audit.path("operatorType").asText();
      String auditedReason = audit.path("reason").asText();
      assertThat(auditedWorkflowRunId).isEqualTo(workflowRunId);
      assertThat(auditedNodeRunId).isEqualTo(nodeRunId);
      assertThat(auditedOperatorId).isEqualTo("manual-skip-admin");
      assertThat(auditedOperatorType).isEqualTo("REQUEST");
      assertThat(auditedReason).isEqualTo(reason);

      // 真实 Job 失败已提交 WorkflowRun 的终态与事件。
      // 跳过节点只记录操作者处置，不重写终态历史。
      String workflowStatusAfterNodeSkip =
          jdbc.queryForObject(SELECT_RUN_STATUS_SQL, String.class, TENANT, workflowRunId);
      assertThat(workflowStatusAfterNodeSkip).isEqualTo("FAILED");
      assertThat(auditedJobInstanceId).isEqualTo(jobInstanceId);

      UriComponentsBuilder operationAuditUriBuilder = UriComponentsBuilder.fromUriString(
              consoleBaseUrl)
          .path("/api/console/queries/operation-audits");
      operationAuditUriBuilder
          .queryParam("tenantId", TENANT)
          .queryParam("aggregateType", "workflow_run")
          .queryParam("aggregateId", workflowRunId)
          .queryParam("action", "workflowRun.skipNode")
          .queryParam("pageNo", 1)
          .queryParam("pageSize", 10);
      String operationAuditUri = operationAuditUriBuilder.build().encode().toUriString();
      WebTestClient.ResponseSpec operationAuditResponse = client
          .get()
          .uri(operationAuditUri)
          .cookie("batch_console_token", administrator.jwt())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .exchange();
      operationAuditResponse.expectStatus().isOk();
      JsonNode operationAuditPage = response(operationAuditResponse);
      JsonNode operationAuditData = operationAuditPage.path("data");
      JsonNode operationAuditItems = operationAuditData.path("items");
      assertThat(operationAuditItems.isArray()).isTrue();
      assertThat(operationAuditItems.size()).isEqualTo(1);
      JsonNode operationAudit = operationAuditItems.get(0);
      String operationAuditOperator = operationAudit.path("operatorId").asText();
      String operationAuditResult = operationAudit.path("result").asText();
      String operationAuditAggregateId = operationAudit.path("aggregateId").asText();
      String operationAuditParams = operationAudit.path("params").asText();
      assertThat(operationAuditOperator).isEqualTo("manual-skip-admin");
      assertThat(operationAuditResult).isEqualTo("SUCCESS");
      assertThat(operationAuditAggregateId).isEqualTo(String.valueOf(workflowRunId));
      assertThat(operationAuditParams).contains(reason, "JOB");

      UriComponentsBuilder jobExecutionLogUriBuilder = UriComponentsBuilder.fromUriString(
              consoleBaseUrl)
          .path("/api/console/queries/job-execution-logs");
      jobExecutionLogUriBuilder
          .queryParam("tenantId", TENANT)
          .queryParam("jobInstanceId", jobInstanceId)
          .queryParam("logType", "AUDIT")
          .queryParam("keyword", "WORKFLOW_NODE_SKIP")
          .queryParam("pageNo", 1)
          .queryParam("pageSize", 10);
      String jobExecutionLogUri = jobExecutionLogUriBuilder.build().encode().toUriString();
      WebTestClient.ResponseSpec jobExecutionLogResponse = client
          .get()
          .uri(jobExecutionLogUri)
          .cookie("batch_console_token", administrator.jwt())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .exchange();
      jobExecutionLogResponse.expectStatus().isOk();
      JsonNode jobExecutionLogPage = response(jobExecutionLogResponse);
      JsonNode jobExecutionLogData = jobExecutionLogPage.path("data");
      JsonNode jobExecutionLogItems = jobExecutionLogData.path("items");
      assertThat(jobExecutionLogItems.isArray()).isTrue();
      assertThat(jobExecutionLogItems.size()).isEqualTo(1);
      JsonNode jobExecutionLog = jobExecutionLogItems.get(0);
      long queriedJobInstanceId = jobExecutionLog.path("jobInstanceId").asLong();
      String queriedLogMessage = jobExecutionLog.path("message").asText();
      String queriedDetailReference = jobExecutionLog.path("detailRef").asText();
      String queriedAuditJson = jobExecutionLog.path("extraJson").asText();
      assertThat(queriedJobInstanceId).isEqualTo(jobInstanceId);
      assertThat(queriedLogMessage).isEqualTo("WORKFLOW_NODE_SKIP");
      assertThat(queriedDetailReference).isEqualTo("workflow_node_run");
      JsonNode queriedAudit = objectMapper.readTree(queriedAuditJson);
      long queriedWorkflowRunId = queriedAudit.path("workflowRunId").asLong();
      long queriedNodeRunId = queriedAudit.path("nodeRunId").asLong();
      String queriedOperator = queriedAudit.path("operatorId").asText();
      String queriedReason = queriedAudit.path("reason").asText();
      assertThat(queriedWorkflowRunId).isEqualTo(workflowRunId);
      assertThat(queriedNodeRunId).isEqualTo(nodeRunId);
      assertThat(queriedOperator).isEqualTo("manual-skip-admin");
      assertThat(queriedReason).isEqualTo(reason);
    }
  }

  @Test
  @DisplayName("认证 Console 跳过失败 DAG 节点后等待 Join 分支并按 DAG 规则完成")
  void shouldAdvanceDagJoinAfterAuthenticatedSkipOfFailedNode() throws Exception {
    String previousAllowPrivate = System.getProperty("batch.security.ssrf-guard.allow-private");
    System.setProperty("batch.security.ssrf-guard.allow-private", "true");
    InetSocketAddress sensorAddress = new InetSocketAddress("127.0.0.1", 0);
    HttpServer sensorEndpoint = HttpServer.create(sensorAddress, 0);
    AtomicBoolean sensorReady = new AtomicBoolean(false);
    sensorEndpoint.createContext("/sensor", exchange -> {
      String sensorStatus = sensorReady.get() ? "READY" : "WAIT";
      byte[] responseBody =
          ("{\"status\":\"" + sensorStatus + "\"}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, responseBody.length);
      exchange.getResponseBody().write(responseBody);
      exchange.close();
    });
    sensorEndpoint.start();
    try {
      UUID generatedSuffix = UUID.randomUUID();
      String suffixWithSeparators = generatedSuffix.toString();
      String suffix = suffixWithSeparators.replace("-", "");
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbc, TENANT, "IMPORT", "import", TriggerType.API, BIZ_DATE);
      int sensorPort = sensorEndpoint.getAddress().getPort();
      String sensorUrl = "http://127.0.0.1:" + sensorPort + "/sensor";
      Map<String, Object> sensorSpecification =
          Map.of("url", sensorUrl, "matchExpr", "$.status==READY");
      Map<String, Object> waitNodeParams = Map.of(
          "sensor_type",
          "HTTP_POLL",
          "sensor_spec",
          sensorSpecification,
          "timeout_seconds",
          300,
          "poll_interval_seconds",
          1,
          "on_timeout",
          "FAIL");
      String serializedWaitNodeParams = objectMapper.writeValueAsString(waitNodeParams);
      configureManualSkipWaitDag(seed, serializedWaitNodeParams);
      long workflowDefinitionId = seed.workflowDefinitionId();

      Map<String, Object> failedImportParams = Map.of(
          "fileFormatType", "JSON",
          "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
          "bizType", "CUSTOMER",
          "content", "this is not valid JSON");
      String requestId = "manual-skip-dag-" + suffix;
      LaunchRequest launchRequest = new LaunchRequest(
          TENANT,
          workflowDefinitionId,
          BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          requestId,
          failedImportParams);
      LaunchResponse launchResponse = launchService.launch(launchRequest);
      long workflowRunId = Long.parseLong(launchResponse.instanceNo());
      outboxPublishSupport.publishAllPending(TENANT);

      ThrowingRunnable jobFailedAndWaitPending = () -> {
        String jobStatus =
            jdbc.queryForObject(SELECT_NODE_STATUS_SQL, String.class, TENANT, workflowRunId, "JOB");
        String waitStatus = jdbc.queryForObject(
            SELECT_NODE_STATUS_SQL, String.class, TENANT, workflowRunId, "WAIT_RELEASE");
        String workflowStatus =
            jdbc.queryForObject(SELECT_RUN_STATUS_SQL, String.class, TENANT, workflowRunId);
        assertThat(jobStatus).isEqualTo("FAILED");
        assertThat(waitStatus).isEqualTo("RUNNING");
        assertThat(workflowStatus).isEqualTo("RUNNING");
      };
      await()
          .atMost(Duration.ofSeconds(90))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(jobFailedAndWaitPending);

      ConfigurableApplicationContext startedConsole = startSecuredConsole();
      try (ConfigurableApplicationContext securedConsole = startedConsole) {
        Environment consoleEnvironment = securedConsole.getEnvironment();
        Integer configuredConsolePort =
            consoleEnvironment.getProperty("local.server.port", Integer.class);
        int consolePort = configuredConsolePort;
        String consoleBaseUrl = "http://127.0.0.1:" + consolePort;
        WebTestClient client = WebTestClient.bindToServer()
            .baseUrl(consoleBaseUrl)
            .responseTimeout(Duration.ofSeconds(45))
            .build();
        ConsoleJwtService jwtService = securedConsole.getBean(ConsoleJwtService.class);
        Actor administrator =
            authenticate(client, jwtService, "manual-skip-dag-admin", "ROLE_ADMIN");
        Actor auditor = authenticate(client, jwtService, "manual-skip-dag-auditor", "ROLE_AUDITOR");
        URI failedJobSkipUri = buildSkipNodeUri(consoleBaseUrl, workflowRunId, "JOB");
        URI runningWaitSkipUri = buildSkipNodeUri(consoleBaseUrl, workflowRunId, "WAIT_RELEASE");

        WebTestClient.ResponseSpec deniedSkipResponse =
            postSkipNode(client, auditor, failedJobSkipUri);
        deniedSkipResponse.expectStatus().isForbidden();
        WebTestClient.ResponseSpec runningWaitSkipResponse =
            postSkipNode(client, administrator, runningWaitSkipUri);
        runningWaitSkipResponse.expectStatus().isEqualTo(409);
        String waitStatusAfterRejectedSkip = jdbc.queryForObject(
            SELECT_NODE_STATUS_SQL, String.class, TENANT, workflowRunId, "WAIT_RELEASE");
        assertThat(waitStatusAfterRejectedSkip).isEqualTo("RUNNING");

        WebTestClient.ResponseSpec skipResponse =
            postSkipNode(client, administrator, failedJobSkipUri);
        skipResponse.expectStatus().isOk();
        JsonNode skipResponseBody = response(skipResponse);
        JsonNode skippedNodeData = skipResponseBody.path("data");
        String skippedNodeStatus = skippedNodeData.path("nodeStatus").asText();
        assertThat(skippedNodeStatus).isEqualTo("SKIPPED");
        String workflowStatusAfterSkip =
            jdbc.queryForObject(SELECT_RUN_STATUS_SQL, String.class, TENANT, workflowRunId);
        String waitStatusAfterSkip = jdbc.queryForObject(
            SELECT_NODE_STATUS_SQL, String.class, TENANT, workflowRunId, "WAIT_RELEASE");
        Integer endNodeCountAfterSkip =
            jdbc.queryForObject(COUNT_NODE_RUNS_SQL, Integer.class, TENANT, workflowRunId, "END");
        assertThat(workflowStatusAfterSkip).isEqualTo("RUNNING");
        assertThat(waitStatusAfterSkip).isEqualTo("RUNNING");
        assertThat(endNodeCountAfterSkip).isZero();

        sensorReady.set(true);
        ThrowingRunnable workflowJoinCompleted = () -> {
          String completedWaitStatus = jdbc.queryForObject(
              SELECT_NODE_STATUS_SQL, String.class, TENANT, workflowRunId, "WAIT_RELEASE");
          String completedWorkflowStatus =
              jdbc.queryForObject(SELECT_RUN_STATUS_SQL, String.class, TENANT, workflowRunId);
          assertThat(completedWaitStatus).isEqualTo("SUCCESS");
          assertThat(completedWorkflowStatus).isEqualTo("SUCCESS");
        };
        await()
            .atMost(Duration.ofSeconds(90))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(workflowJoinCompleted);
      }
    } finally {
      sensorEndpoint.stop(0);
      if (previousAllowPrivate == null) {
        System.clearProperty("batch.security.ssrf-guard.allow-private");
      } else {
        System.setProperty("batch.security.ssrf-guard.allow-private", previousAllowPrivate);
      }
    }
  }

  private CompensationSourceInput prepareCompensationSourceInput(
      String suffix,
      String customerNo,
      String repairedImportContent,
      boolean sourceInitiallyFailed) {
    if (!sourceInitiallyFailed) {
      Map<String, Object> inlineLaunchParams = Map.of(
          "fileFormatType", "JSON",
          "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
          "bizType", "CUSTOMER",
          "content", repairedImportContent);
      return new CompensationSourceInput(inlineLaunchParams, null, null, null);
    }

    String storageBucket = s3Bucket();
    String storagePath = "self-service-compensation/" + TENANT + "/" + suffix + ".json";
    byte[] initialInvalidContent = "this is not valid JSON".getBytes(StandardCharsets.UTF_8);
    byte[] repairedFileContent = repairedImportContent.getBytes(StandardCharsets.UTF_8);
    ensureBucket(storageBucket);
    putObject(storageBucket, storagePath, initialInvalidContent);

    String fileName = customerNo + ".json";
    String templateCode = "IMP-CUSTOMER-JSON-ARRAY";
    Map<String, Object> objectStoreLaunchParams = Map.of(
        "fileName", fileName,
        "originalFileName", fileName,
        "fileFormatType", "JSON",
        "charset", "UTF-8",
        "bizType", "CUSTOMER",
        "storageType", "S3",
        "storageBucket", storageBucket,
        "storagePath", storagePath,
        "templateCode", templateCode);
    return new CompensationSourceInput(
        objectStoreLaunchParams, storageBucket, storagePath, repairedFileContent);
  }

  private record CompensationSourceInput(
      Map<String, Object> launchParams,
      String storageBucket,
      String storagePath,
      byte[] repairedFileContent) {}

  private void configureManualSkipWaitDag(LaunchSeed seed, String serializedWaitNodeParams) {
    long workflowDefinitionId = seed.workflowDefinitionId();
    jdbc.update(UPDATE_WORKFLOW_DEFINITION_FOR_DAG_SQL, TENANT, workflowDefinitionId);
    jdbc.update(MOVE_END_NODE_FOR_DAG_SQL, TENANT, workflowDefinitionId);
    jdbc.update(
        INSERT_WAIT_NODE_FOR_DAG_SQL, TENANT, workflowDefinitionId, serializedWaitNodeParams);
    jdbc.update(DELETE_WORKFLOW_EDGES_FOR_DAG_SQL, TENANT, workflowDefinitionId);
    jdbc.update(
        INSERT_WORKFLOW_EDGES_FOR_DAG_SQL,
        TENANT,
        workflowDefinitionId,
        TENANT,
        workflowDefinitionId,
        TENANT,
        workflowDefinitionId,
        TENANT,
        workflowDefinitionId);
  }

  private URI buildSkipNodeUri(String consoleBaseUrl, long workflowRunId, String nodeCode) {
    UriComponentsBuilder skipNodeUriBuilder = UriComponentsBuilder.fromUriString(consoleBaseUrl);
    skipNodeUriBuilder
        .path("/api/console/workflow-runs/{id}/skip-node")
        .queryParam("tenantId", TENANT)
        .queryParam("nodeCode", nodeCode)
        .queryParam("reason", "skip failed DAG node after operator review");
    UriComponents expandedSkipNodeUri = skipNodeUriBuilder.buildAndExpand(workflowRunId);
    UriComponents encodedSkipNodeUri = expandedSkipNodeUri.encode();
    return encodedSkipNodeUri.toUri();
  }

  private ConfigurableApplicationContext startSecuredConsole() {
    Map<String, Object> consoleProperties = new HashMap<>();
    BiConsumer<String, Supplier<?>> propertyRegistrar = (name, supplier) -> {
      Object propertyValue = supplier.get();
      consoleProperties.put(name, propertyValue);
    };
    AbstractIntegrationTest.registerDynamicProperties(propertyRegistrar::accept, false);
    consoleProperties.put("server.port", 0);
    consoleProperties.put("spring.main.web-application-type", "servlet");
    consoleProperties.put("spring.flyway.enabled", false);
    consoleProperties.put(
        "spring.autoconfigure.exclude",
        String.join(
            ",",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
            "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration"));
    consoleProperties.put("batch.startup-self-check.enabled", false);
    consoleProperties.put("batch.console.security.single-session-enabled", false);
    consoleProperties.put("batch.console.security.default-tenant-id", TENANT);
    consoleProperties.put("batch.console.security.allowed-tenants", TENANT);
    consoleProperties.put("batch.security.internal-secret", INTERNAL_SECRET);
    String orchestratorBaseUrl = "http://127.0.0.1:" + orchestratorPort;
    consoleProperties.put("batch.console.orchestrator.base-url", orchestratorBaseUrl);
    consoleProperties.put("batch.console.trigger.base-url", orchestratorBaseUrl);
    String[] consoleArguments = new String[consoleProperties.size()];
    int argumentIndex = 0;
    for (Map.Entry<String, Object> property : consoleProperties.entrySet()) {
      String consoleArgument = "--" + property.getKey() + "=" + property.getValue();
      consoleArguments[argumentIndex] = consoleArgument;
      argumentIndex++;
    }

    SpringApplicationBuilder consoleApplication =
        new SpringApplicationBuilder(BatchConsoleApiApplication.class);
    consoleApplication.profiles("test", "e2e");
    return consoleApplication.run(consoleArguments);
  }

  private WebTestClient.ResponseSpec postSkipNode(
      WebTestClient client, Actor actor, URI skipNodeUri) {
    WebTestClient.RequestHeadersSpec<?> skipRequest = client
        .post()
        .uri(skipNodeUri)
        .cookie("batch_console_token", actor.jwt())
        .cookie("XSRF-TOKEN", actor.csrf())
        .header("X-XSRF-TOKEN", actor.csrf())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .header(CommonConstants.DEFAULT_OPERATOR_ID_HEADER, "spoofed-operator")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "manual-skip-" + UUID.randomUUID());
    return skipRequest.exchange();
  }

  private Actor authenticate(
      WebTestClient client, ConsoleJwtService jwtService, String username, String role) {
    ConsoleAuthTokenResponse issuedToken = jwtService.issueToken(username, TENANT, Set.of(role));
    String jwt = issuedToken.accessToken();
    WebTestClient.ResponseSpec authCheckResponse = client
        .get()
        .uri("/api/console/auth/check")
        .cookie("batch_console_token", jwt)
        .exchange()
        .expectStatus()
        .isNoContent();
    var authCheckResult = authCheckResponse.returnResult(Void.class);
    var responseCookies = authCheckResult.getResponseCookies();
    var cookie = responseCookies.getFirst("XSRF-TOKEN");
    assertThat(cookie).isNotNull();
    return new Actor(jwt, cookie.getValue());
  }

  private WebTestClient.ResponseSpec post(
      WebTestClient client, Actor actor, String path, Map<String, Object> body) {
    String idempotencyKey = "self-service-" + UUID.randomUUID();
    return post(client, actor, path, body, idempotencyKey);
  }

  private WebTestClient.ResponseSpec post(
      WebTestClient client,
      Actor actor,
      String path,
      Map<String, Object> body,
      String idempotencyKey) {
    return client
        .post()
        .uri(path)
        .cookie("batch_console_token", actor.jwt())
        .cookie("XSRF-TOKEN", actor.csrf())
        .header("X-XSRF-TOKEN", actor.csrf())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .header(CommonConstants.DEFAULT_OPERATOR_ID_HEADER, "spoofed-operator")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  private void insertWorkflowReplayExportTemplate(
      String templateCode, int version, String batchTable, String detailTable) throws Exception {
    Map<String, Object> fieldMappings = Map.of(
        "name", "itemCode",
        "sourceColumn", "item_code",
        "type", "STRING",
        "header", "itemCode");
    Map<String, Object> amountMapping = Map.of(
        "name", "amount",
        "sourceColumn", "amount",
        "type", "DECIMAL",
        "header", "amount");
    Map<String, Object> mappedExportSpec = Map.of(
        "schema", "biz",
        "batchTable", batchTable,
        "batchTenantColumn", "tenant_id",
        "batchNoColumn", "batch_no",
        "batchSelectColumns", List.of("id", "batch_no"),
        "detailTable", detailTable,
        "detailFkColumn", "batch_id",
        "detailOrderByColumn", "id",
        "detailSelectColumns", List.of("id", "item_code", "amount"));
    Map<String, Object> queryParamSchema = Map.of("jdbcMappedExport", mappedExportSpec);
    String namingRule = "workflow_replay_{bizDate}_" + version + "_{seq}.csv";
    String fieldMappingsJson =
        objectMapper.writeValueAsString(List.of(fieldMappings, amountMapping));
    String queryParamSchemaJson = objectMapper.writeValueAsString(queryParamSchema);
    int insertedRows = jdbc.update(
        INSERT_WORKFLOW_REPLAY_EXPORT_TEMPLATE_SQL,
        TENANT,
        templateCode,
        "Workflow Replay Export " + version,
        namingRule,
        fieldMappingsJson,
        queryParamSchemaJson,
        version);
    assertThat(insertedRows).isEqualTo(1);
  }

  private void createWorkflowReplayBusinessRows(
      String batchTable, String detailTable, String batchNo) {
    JdbcTemplate businessJdbc = new JdbcTemplate(e2eBusinessDataSource);
    String createBatchTableSql = CREATE_WORKFLOW_REPLAY_BATCH_TABLE_SQL.formatted(batchTable);
    String createDetailTableSql =
        CREATE_WORKFLOW_REPLAY_DETAIL_TABLE_SQL.formatted(detailTable, batchTable);
    businessJdbc.execute(createBatchTableSql);
    businessJdbc.execute(createDetailTableSql);

    String insertBatchSql = INSERT_WORKFLOW_REPLAY_BATCH_SQL.formatted(batchTable);
    Long batchId = businessJdbc.queryForObject(insertBatchSql, Long.class, TENANT, batchNo);
    assertThat(batchId).isNotNull();

    String insertDetailSql = INSERT_WORKFLOW_REPLAY_DETAIL_SQL.formatted(detailTable);
    int insertedRows =
        businessJdbc.update(insertDetailSql, batchId, "R25-ITEM", new BigDecimal("12.50"));
    assertThat(insertedRows).isEqualTo(1);
  }

  private void dropWorkflowReplayBusinessTables(String batchTable, String detailTable) {
    JdbcTemplate businessJdbc = new JdbcTemplate(e2eBusinessDataSource);
    String dropDetailTableSql = DROP_WORKFLOW_REPLAY_DETAIL_TABLE_SQL.formatted(detailTable);
    String dropBatchTableSql = DROP_WORKFLOW_REPLAY_BATCH_TABLE_SQL.formatted(batchTable);
    businessJdbc.execute(dropDetailTableSql);
    businessJdbc.execute(dropBatchTableSql);
  }

  private Process startExportWorker(String workerCode, String consumerGroupId, Path logFile)
      throws IOException, InterruptedException {
    Path workerExecJar = locateExportExecJar();
    List<String> command = new ArrayList<>(List.of(
        JAVA_BIN,
        "-jar",
        workerExecJar.toString(),
        "--spring.profiles.active=test,e2e",
        "--server.port=0",
        "--spring.datasource.url=" + platformJdbcUrl(),
        "--spring.datasource.username=" + platformJdbcUsername(),
        "--spring.datasource.password=" + platformJdbcPassword(),
        "--spring.datasource.driver-class-name=org.postgresql.Driver",
        "--batch.datasource.business.url=" + businessJdbcUrl(),
        "--batch.datasource.business.username=" + businessJdbcUsername(),
        "--batch.datasource.business.password=" + businessJdbcPassword(),
        "--spring.kafka.bootstrap-servers=" + kafkaBootstrapServers(),
        "--batch.rls.startup-fail-fast=false",
        "--batch.orchestrator.base-url=http://127.0.0.1:" + orchestratorPort,
        "--batch.worker.task-client.base-url=http://127.0.0.1:" + orchestratorPort,
        "--batch.worker.registry.fail-fast-on-startup=false",
        "--batch.security.bypass-mode=true",
        "--batch.security.internal-secret=" + INTERNAL_SECRET,
        "--batch.security.kms.default-key-ref=DEFAULT_TEST",
        "--batch.security.kms.keys.DEFAULT_TEST=AAAAAAAAAAAAAAAAAAAAAA==",
        "--batch.storage.s3.endpoint=" + s3Endpoint(),
        "--batch.storage.s3.region=us-east-1",
        "--batch.storage.s3.access-key=minioadmin",
        "--batch.storage.s3.secret-key=minioadmin123",
        "--batch.storage.s3.bucket=" + s3Bucket(),
        "--batch.storage.s3.path-style-enabled=true",
        "--batch.worker.type=EXPORT",
        "--batch.worker.export.worker-code=" + workerCode,
        "--batch.worker.export.worker-type=EXPORT",
        "--batch.worker.export.tenant-id=" + TENANT,
        "--batch.worker.export.heartbeat-interval-millis=1000",
        "--batch.worker.export.topic=batch.task.dispatch.export",
        "--batch.worker.export.consumer-group-id=" + consumerGroupId,
        "--batch.worker.report-outbox.enabled=true",
        "--batch.worker.report-outbox.storage=PLATFORM_PG",
        "--batch.worker.report-outbox.backend-guard.cutover-id=r15-export-recovery-e2e-v1",
        "--batch.worker.report-outbox.poll-interval-millis=600000",
        "--batch.worker.task-client.report-max-attempts=1",
        "--batch.worker.identity.instance-id=" + workerCode));
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    return processBuilder.start();
  }

  private void awaitExportWorkerStarted(Process worker, Path logFile) {
    await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          assertThat(worker.isAlive())
              .withFailMessage("export worker exited before startup; log: %s", logFile)
              .isTrue();
          String workerLogText = Files.readString(logFile, StandardCharsets.UTF_8);
          assertThat(workerLogText).contains("Started BatchWorkerExportApplication");
        });
  }

  private void awaitExportWorkerOnline(String workerCode) {
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Integer onlineWorkerCount = jdbc.queryForObject(
              SELECT_EXPORT_WORKER_ONLINE_COUNT_SQL, Integer.class, TENANT, workerCode);
          assertThat(onlineWorkerCount).isEqualTo(1);
        });
  }

  private Path locateExportExecJar() throws IOException, InterruptedException {
    Path targetDirectory = projectRoot().resolve("batch-worker/export/target");
    Path workerExecJar = findLatestExecJar(targetDirectory);
    Path compiledClassesDirectory = targetDirectory.resolve("classes");
    Path buildStamp = targetDirectory.resolve(".workflow-replay-exec-build-stamp");
    boolean compiledClassesAreNewer = workerExecJar != null
        && (!Files.isRegularFile(buildStamp)
            || hasCompiledClassesNewerThanStamp(compiledClassesDirectory, buildStamp));
    if (workerExecJar != null && !compiledClassesAreNewer) {
      return workerExecJar;
    }

    Path buildLog = Files.createTempFile("workflow-replay-export-build-", ".log");
    List<String> buildCommand = List.of(
        projectRoot().resolve("mvnw").toString(),
        "-o",
        "-ntp",
        "-q",
        "-pl",
        "batch-worker/export",
        "-am",
        "-DskipTests",
        "package");
    ProcessBuilder buildProcessBuilder = new ProcessBuilder(buildCommand);
    buildProcessBuilder.directory(projectRoot().toFile());
    buildProcessBuilder.redirectErrorStream(true);
    buildProcessBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(buildLog.toFile()));
    Process buildProcess = buildProcessBuilder.start();
    try {
      boolean buildFinished = buildProcess.waitFor(WORKER_BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
      if (!buildFinished) {
        buildProcess.destroyForcibly();
        throw new IllegalStateException("timed out building batch-worker-export; see " + buildLog);
      }
      if (buildProcess.exitValue() != 0) {
        throw new IllegalStateException("failed to build batch-worker-export; see " + buildLog);
      }
    } finally {
      if (buildProcess.isAlive()) {
        buildProcess.destroyForcibly();
      }
    }

    workerExecJar = findLatestExecJar(targetDirectory);
    if (workerExecJar == null) {
      throw new IllegalStateException(
          "batch-worker-export exec jar not found under " + targetDirectory);
    }
    Files.writeString(buildStamp, "export worker exec jar packaged for workflow replay E2E");
    Files.deleteIfExists(buildLog);
    return workerExecJar;
  }

  private boolean hasCompiledClassesNewerThanStamp(Path classesDirectory, Path buildStamp)
      throws IOException {
    if (!Files.isDirectory(classesDirectory)) {
      return false;
    }
    long buildStampLastModified = buildStamp.toFile().lastModified();
    try (Stream<Path> classFiles = Files.walk(classesDirectory)) {
      return classFiles
          .filter(Files::isRegularFile)
          .anyMatch(classFile -> classFile.toFile().lastModified() > buildStampLastModified);
    }
  }

  private Path findLatestExecJar(Path targetDirectory) throws IOException {
    if (!Files.isDirectory(targetDirectory)) {
      return null;
    }
    try (Stream<Path> paths = Files.list(targetDirectory)) {
      return paths
          .filter(path -> path.getFileName().toString().endsWith("-exec.jar"))
          .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
          .orElse(null);
    }
  }

  private Path projectRoot() {
    Path currentDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (currentDirectory != null) {
      boolean hasRootPom = Files.isRegularFile(currentDirectory.resolve("pom.xml"));
      boolean hasWorkerModule = Files.isDirectory(currentDirectory.resolve("batch-worker/export"));
      if (hasRootPom && hasWorkerModule) {
        return currentDirectory;
      }
      currentDirectory = currentDirectory.getParent();
    }
    throw new IllegalStateException(
        "unable to locate project root from " + System.getProperty("user.dir"));
  }

  private void stopWorkerProcess(Process worker) {
    if (worker == null || !worker.isAlive()) {
      return;
    }
    ProcessHandle workerHandle = worker.toHandle();
    workerHandle.descendants().forEach(ProcessHandle::destroy);
    workerHandle.destroy();
    try {
      boolean stopped = worker.waitFor(10, TimeUnit.SECONDS);
      if (!stopped) {
        workerHandle.descendants().forEach(ProcessHandle::destroyForcibly);
        workerHandle.destroyForcibly();
        worker.waitFor(10, TimeUnit.SECONDS);
      }
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
      workerHandle.descendants().forEach(ProcessHandle::destroyForcibly);
      workerHandle.destroyForcibly();
    }
  }

  private JsonNode response(WebTestClient.ResponseSpec response) throws Exception {
    WebTestClient.BodyContentSpec responseBody = response.expectBody();
    byte[] body = responseBody.returnResult().getResponseBody();
    assertThat(body).isNotNull();
    return objectMapper.readTree(body);
  }

  private record Actor(String jwt, String csrf) {}
}

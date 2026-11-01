package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.i18n.BizMessageResolver;
import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eImportApplication;
import io.github.pinpols.batch.e2e.apps.E2eTriggerApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService;
import io.github.pinpols.batch.orchestrator.config.OrchestratorKafkaConsumerConfiguration;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.infrastructure.trigger.TriggerLaunchConsumer;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.infrastructure.TriggerSchedulerFacade;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.web.TriggerCommandInputMapper;
import io.github.pinpols.batch.trigger.web.request.TriggerLaunchRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * Trigger → Outbox → Kafka → Orchestrator → WorkflowRun → JobInstance 全链路 E2E。
 *
 * <p>真起 PG + Kafka(Testcontainers via {@link AbstractIntegrationTest})，并行启动 trigger 与 orchestrator 两个独立
 * Spring context。用例从 Trigger HTTP 或 {@link TriggerService#launch} 进入，断言:
 *
 * <ol>
 *   <li>orchestrator 端 {@code TriggerLaunchConsumer} 真消费消息(@KafkaListener)
 *   <li>反序列化 LaunchEnvelope 成功
 *   <li>调用 {@code LaunchApplicationService.launchFromTrustedQueue(launchRequest)}
 *   <li>创建 WorkflowRun，并由根 Workflow 派发 JOB 节点和 JobInstance
 *   <li>请求、WorkflowRun 与子 JobInstance 的业务日保持一致
 * </ol>
 *
 * <p>并发请求完成后重复投递原 Kafka envelope，验证消费位点推进后仍只有一条成功执行链和实际业务写入。
 */
@E2eSpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.kafka.namespace=workflow-e2e-new",
      "batch.outbox.poll-interval-millis=500",
      "batch.outbox.min-poll-interval-millis=200",
      "batch.startup-self-check.enabled=false"
    })
@E2eBusinessSchema
@Sql(scripts = E2eTestSql.IMPORT_TEMPLATE_SEED)
@Tag("critical")
// E2eImportApplication ComponentScan 不覆盖 io.github.pinpols.batch.common.i18n 包,
// 显式 @Import BizMessageResolver 让 KafkaOutboxPublisher 等 i18n 依赖可以解出来
@Import(BizMessageResolver.class)
@DisplayName("触发到 Workflow 执行的异步全链路端到端:触发经中继投递消息并派发 Job 节点,重复投递仍保持幂等")
class TriggerAsyncLaunchFullChainE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static ConfigurableApplicationContext triggerContext;

  private static TriggerService triggerService() {
    if (triggerContext == null) {
      triggerContext = new SpringApplicationBuilder(E2eTriggerApplication.class)
          .profiles("test", "e2e")
          .run(
              "--spring.main.web-application-type=servlet",
              "--server.address=127.0.0.1",
              "--server.port=0",
              "--spring.datasource.url=" + platformJdbcUrl(),
              "--spring.datasource.username=" + platformJdbcUsername(),
              "--spring.datasource.password=" + platformJdbcPassword(),
              "--spring.kafka.bootstrap-servers=" + kafkaBootstrapServers(),
              "--batch.kafka.namespace=workflow-e2e-new",
              "--spring.flyway.enabled=false",
              "--spring.quartz.auto-startup=false",
              "--batch.startup-self-check.enabled=false",
              "--batch.security.bypass-mode=true",
              "--batch.orchestrator.base-url=http://127.0.0.1:1",
              "--batch.trigger.outbox.poll-interval-millis=200",
              "--batch.trigger.kafka.send-timeout-seconds=5");
    }
    return triggerContext.getBean(TriggerService.class);
  }

  private static TriggerSchedulerFacade triggerSchedulerFacade() {
    triggerService();
    return triggerContext.getBean(TriggerSchedulerFacade.class);
  }

  private static Scheduler quartzScheduler() {
    triggerService();
    return triggerContext.getBean(Scheduler.class);
  }

  @AfterAll
  static void stopTriggerContext() {
    if (triggerContext != null) {
      triggerContext.close();
    }
  }

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private KafkaTemplate<String, String> kafkaTemplate;

  private final MeterRegistry meterRegistry;

  private final BatchKafkaTopicNamespace topicNamespace;

  private final KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

  private final TaskOutcomeService taskOutcomeService;

  private final TriggerLaunchConsumer triggerLaunchConsumer;

  private final DataSource importBusinessDataSource;

  @Autowired
  TriggerAsyncLaunchFullChainE2eIT(
      MeterRegistry meterRegistry,
      BatchKafkaTopicNamespace topicNamespace,
      KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry,
      TaskOutcomeService taskOutcomeService,
      TriggerLaunchConsumer triggerLaunchConsumer,
      @Qualifier("importBusinessDataSource") DataSource importBusinessDataSource) {
    this.meterRegistry = meterRegistry;
    this.topicNamespace = topicNamespace;
    this.kafkaListenerEndpointRegistry = kafkaListenerEndpointRegistry;
    this.taskOutcomeService = taskOutcomeService;
    this.triggerLaunchConsumer = triggerLaunchConsumer;
    this.importBusinessDataSource = importBusinessDataSource;
  }

  @Test
  @DisplayName("触发发起后事件真正发布并被消费:作业实例有且仅有一条,发送记录状态推进为已发布")
  void triggerLaunch_publishesAndCreatesJobInstance() {
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?\
        """;
    final String selectPublishStatusSql = """
        select publish_status from batch.trigger_outbox_event where tenant_id = ? and request_id = ?\
        """;
    final String selectCountSql2 = """
        select count(*) as linked_job_count,
               count(*) filter (
                 where tr.biz_date is distinct from wr.biz_date
                    or ji.biz_date is distinct from tr.biz_date
               ) as mismatch_count
          from batch.trigger_request tr
          join batch.workflow_run wr
            on wr.tenant_id = tr.tenant_id and wr.trigger_request_id = tr.id
          join batch.workflow_node_run wnr
            on wnr.workflow_run_id = wr.id and wnr.job_instance_id is not null
          join batch.job_instance ji
            on ji.tenant_id = tr.tenant_id and ji.id = wnr.job_instance_id
         where tr.tenant_id = ? and tr.request_id = ?
        """;

    // 1) 准备 job_definition；fixture 预置的 trigger_request 先删掉，让真实 trigger service 创建。
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    jdbcTemplate.update(deleteTriggerRequestSql, TENANT, seed.requestId());

    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId(TENANT);
    request.setWorkflowDefinitionId(seed.workflowDefinitionId());
    request.setBizDate(LocalDate.of(2026, 4, 30));
    request.setTriggerType(TriggerType.API);
    request.setParams(Map.of("fileFormatType", "JSON"));
    triggerService()
        .launch(
            new TriggerLaunchCommand(
                TriggerCommandInputMapper.toInput(request),
                seed.dedupKey(), seed.requestId(), "tr-fullchain"));

    // 2) trigger outbox relay 真发 Kafka，orchestrator consumer 真消费并创建实例。
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Integer count =
              jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, seed.dedupKey());
          assertThat(count).isEqualTo(1);
          String publishStatus = jdbcTemplate.queryForObject(
              selectPublishStatusSql, String.class, TENANT, seed.requestId());
          assertThat(publishStatus).isEqualTo("PUBLISHED");
        });

    Map.Entry<Integer, Integer> datePropagationCounts = jdbcTemplate.queryForObject(
        selectCountSql2,
        (rs, rowNum) -> Map.entry(rs.getInt("linked_job_count"), rs.getInt("mismatch_count")),
        TENANT,
        seed.requestId());
    assertThat(datePropagationCounts.getKey())
        .as("the API-triggered Workflow has one linked Job node")
        .isEqualTo(1);
    assertThat(datePropagationCounts.getValue())
        .as("API request, root WorkflowRun and child JobInstance keep the requested business date")
        .isZero();
  }

  @Test
  @DisplayName("新部署的 Trigger Outbox 启动消息不会投递给未命名空间隔离的旧 consumer")
  void namespacedTriggerLaunch_isNotDeliveredToLegacyConsumer() throws Exception {
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    final String selectJobInstanceCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?
        """;
    final String selectPublishStatusSql = """
        select publish_status from batch.trigger_outbox_event where tenant_id = ? and request_id = ?
        """;
    final String legacyTopic = BatchTopics.TRIGGER_LAUNCH_V1;
    final String consumerGroup = "legacy-trigger-probe-" + UUID.randomUUID();
    final Properties legacyConsumerProperties = new Properties();
    legacyConsumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
    legacyConsumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
    legacyConsumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    legacyConsumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    legacyConsumerProperties.put(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    legacyConsumerProperties.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    jdbcTemplate.update(deleteTriggerRequestSql, TENANT, seed.requestId());

    try (KafkaConsumer<String, String> legacyConsumer =
        new KafkaConsumer<>(legacyConsumerProperties)) {
      legacyConsumer.subscribe(List.of(legacyTopic));
      await().atMost(Duration.ofSeconds(15)).until(() -> {
        legacyConsumer.poll(Duration.ofMillis(100));
        return !legacyConsumer.assignment().isEmpty();
      });

      TriggerLaunchRequest request = new TriggerLaunchRequest();
      request.setTenantId(TENANT);
      request.setWorkflowDefinitionId(seed.workflowDefinitionId());
      request.setBizDate(LocalDate.of(2026, 10, 22));
      request.setTriggerType(TriggerType.API);
      request.setParams(Map.of("fileFormatType", "JSON"));
      triggerService()
          .launch(new TriggerLaunchCommand(
              TriggerCommandInputMapper.toInput(request),
              seed.dedupKey(), seed.requestId(), "r24-namespace-isolation"));

      await()
          .atMost(Duration.ofSeconds(60))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            Integer jobInstanceCount = jdbcTemplate.queryForObject(
                selectJobInstanceCountSql, Integer.class, TENANT, seed.dedupKey());
            assertThat(jobInstanceCount).isEqualTo(1);
            String publishStatus = jdbcTemplate.queryForObject(
                selectPublishStatusSql, String.class, TENANT, seed.requestId());
            assertThat(publishStatus).isEqualTo("PUBLISHED");
          });

      boolean legacyConsumerReceivedRequest = false;
      final long observationDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (System.nanoTime() < observationDeadline) {
        ConsumerRecords<String, String> records = legacyConsumer.poll(Duration.ofMillis(100));
        for (ConsumerRecord<String, String> record : records) {
          if (record.value().contains(seed.requestId())) {
            legacyConsumerReceivedRequest = true;
          }
        }
      }

      assertThat(legacyConsumerReceivedRequest).isFalse();
      assertThat(topicNamespace.topic(legacyTopic)).isEqualTo("workflow-e2e-new." + legacyTopic);
    }
  }

  @Test
  @DisplayName("Quartz 定时根触发经 Kafka 启动复合 Workflow 的两个 Job 节点并在两者成功后汇合")
  void quartzScheduledRoot_launchesCompositeWorkflowThroughKafkaAndCompletes() throws Exception {
    final String selectIdSql = """
              select wr.id
                from batch.workflow_run wr
                join batch.trigger_request tr
                  on tr.tenant_id = wr.tenant_id and tr.id = wr.trigger_request_id
               where wr.tenant_id = ? and wr.workflow_definition_id = ?
                 and tr.trigger_type = 'SCHEDULED'
               order by wr.id desc
               limit 1
              """;
    final String selectNodeCodeSql = """
              select node_code, job_instance_id
                from batch.workflow_node_run
               where workflow_run_id = ?
                 and node_code in ('JOB_A', 'JOB_B') and job_instance_id is not null
              """;
    final String selectCountSql = """
              select count(*)
                from batch.workflow_run wr
                join batch.trigger_request tr
                  on tr.tenant_id = wr.tenant_id and tr.id = wr.trigger_request_id
                join batch.workflow_node_run wnr
                  on wnr.workflow_run_id = wr.id
                join batch.job_instance ji
                  on ji.tenant_id = wr.tenant_id and ji.id = wnr.job_instance_id
               where wr.tenant_id = ? and wr.id = ?
                 and (tr.biz_date is distinct from wr.biz_date
                   or ji.biz_date is distinct from tr.biz_date)
              """;
    final String selectCountSql2 = """
              select count(*)
                from batch.trigger_outbox_event toe
                join batch.trigger_request tr
                  on tr.tenant_id = toe.tenant_id and tr.request_id = toe.request_id
                join batch.workflow_run wr
                  on wr.tenant_id = tr.tenant_id and wr.trigger_request_id = tr.id
               where wr.tenant_id = ? and wr.id = ? and toe.publish_status = 'PUBLISHED'
              """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;

    CompositeSeed seed = prepareCompositeScheduledWorkflow();
    TriggerSchedulerFacade facade = triggerSchedulerFacade();
    Scheduler scheduler = quartzScheduler();
    JobKey jobKey =
        JobKey.jobKey(TENANT + ":WF:" + seed.workflowCode(), TriggerSchedulerFacade.JOB_GROUP);

    try {
      facade.registerByWorkflowCode(TENANT, seed.workflowCode());
      scheduler.start();
      scheduler.triggerJob(jobKey);

      List<Long> workflowRuns = await()
          .atMost(Duration.ofSeconds(60))
          .pollInterval(Duration.ofMillis(200))
          .until(
              () -> jdbcTemplate.query(
                  selectIdSql, (rs, rowNum) -> rs.getLong(1), TENANT, seed.workflowDefinitionId()),
              runs -> !runs.isEmpty());
      Long workflowRunId = workflowRuns.get(0);

      List<Map.Entry<String, Long>> nodeInstances = await()
          .atMost(Duration.ofSeconds(60))
          .pollInterval(Duration.ofMillis(200))
          .until(
              () -> jdbcTemplate.query(
                  selectNodeCodeSql,
                  (rs, rowNum) -> Map.entry(rs.getString(1), rs.getLong(2)),
                  workflowRunId),
              rows -> rows.size() == 2);
      assertThat(nodeInstances)
          .extracting(Map.Entry::getKey)
          .containsExactlyInAnyOrder("JOB_A", "JOB_B");
      Long jobAInstanceId = nodeInstances.stream()
          .filter(entry -> entry.getKey().equals("JOB_A"))
          .findFirst()
          .orElseThrow()
          .getValue();
      Long jobBInstanceId = nodeInstances.stream()
          .filter(entry -> entry.getKey().equals("JOB_B"))
          .findFirst()
          .orElseThrow()
          .getValue();
      assertThat(jobAInstanceId).isNotEqualTo(jobBInstanceId);
      Integer selectedWorkflowRunCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, workflowRunId);
      assertThat(selectedWorkflowRunCount)
          .as(
              "scheduled request, root WorkflowRun and every Job node keep one frozen business date")
          .isZero();
      Integer selectedTriggerOutboxEventCount =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, workflowRunId);
      assertThat(selectedTriggerOutboxEventCount).isEqualTo(1);

      reportSuccessfulTask(jobAInstanceId);
      String selectedRunStatus =
          jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
      assertThat(selectedRunStatus).isEqualTo("RUNNING");
      reportSuccessfulTask(jobBInstanceId);
      String selectedRunStatusRead2 =
          jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
      assertThat(selectedRunStatusRead2).isEqualTo("SUCCESS");
    } finally {
      if (!scheduler.isShutdown() && scheduler.checkExists(jobKey)) {
        scheduler.deleteJob(jobKey);
      }
    }
  }

  private CompositeSeed prepareCompositeScheduledWorkflow() {
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = ? and request_id in (?, ?)
    """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
            schedule_type, schedule_expr, timezone, trigger_mode, schedule_enabled, calendar_code
        ) values (?, ?, ?, 'DAG', 1, true, 'CRON', '0 0 1 * * ?', 'UTC', 'SCHEDULED', true,
                  'e2e_calendar')
        returning id
        """;
    final String insertWorkflowNodeSql = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type,
            related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
        ) values
            (?, ?, 'START', 'Start', 'START', null, 0, 'NONE', 0, 0, true),
            (?, ?, 'JOB_A', 'Job A', 'JOB', ?, 1, 'NONE', 0, 0, true),
            (?, ?, 'JOB_B', 'Job B', 'JOB', ?, 2, 'NONE', 0, 0, true),
            (?, ?, 'END', 'End', 'END', null, 3, 'NONE', 0, 0, true)
        """;
    final String insertWorkflowEdgeSql = """
        insert into batch.workflow_edge (
            tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
        ) values
            (?, ?, 'START', 'JOB_A', 'ALWAYS', true),
            (?, ?, 'START', 'JOB_B', 'ALWAYS', true),
            (?, ?, 'JOB_A', 'END', 'SUCCESS', true),
            (?, ?, 'JOB_B', 'END', 'SUCCESS', true)
        """;

    String suffix = UUID.randomUUID().toString().replace("-", "");
    LaunchSeed first = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "GENERAL", "E2E_WG_A_" + suffix, TriggerType.API);
    LaunchSeed second = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "GENERAL", "E2E_WG_B_" + suffix, TriggerType.API);
    jdbcTemplate.update(deleteTriggerRequestSql, TENANT, first.requestId(), second.requestId());
    insertWorker("E2E_WORKER_A_" + suffix, "E2E_WG_A_" + suffix);
    insertWorker("E2E_WORKER_B_" + suffix, "E2E_WG_B_" + suffix);

    String workflowCode = "E2E_COMPOSITE_" + suffix;
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, TENANT, workflowCode, workflowCode);
    jdbcTemplate.update(
        insertWorkflowNodeSql,
        TENANT,
        workflowDefinitionId,
        TENANT,
        workflowDefinitionId,
        first.jobCode(),
        TENANT,
        workflowDefinitionId,
        second.jobCode(),
        TENANT,
        workflowDefinitionId);
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        TENANT,
        workflowDefinitionId,
        TENANT,
        workflowDefinitionId,
        TENANT,
        workflowDefinitionId,
        TENANT,
        workflowDefinitionId);
    return new CompositeSeed(workflowCode, workflowDefinitionId);
  }

  private void insertWorker(String workerCode, String workerGroup) {
    final String insertWorkerRegistrySql = """
        insert into batch.worker_registry (
            tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
        ) values (?, ?, ?, '[]'::jsonb, 'ONLINE', now(), 0)
        """;

    jdbcTemplate.update(insertWorkerRegistrySql, TENANT, workerCode, workerGroup);
  }

  private void reportSuccessfulTask(Long jobInstanceId) {
    final String selectIdSql = """
        select id from batch.job_task where tenant_id = ? and job_instance_id = ? order by id limit 1
    """;
    final String updateJobTaskSql = """
        update batch.job_task set task_status = 'RUNNING', started_at = ? where id = ?
    """;

    Long taskId = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, jobInstanceId);
    jdbcTemplate.update(updateJobTaskSql, Timestamp.from(BatchDateTimeSupport.utcNow()), taskId);
    taskOutcomeService.applyTaskOutcome(TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(taskId)
        .success(true)
        .resultSummary("{}")
        .build());
  }

  private record CompositeSeed(String workflowCode, Long workflowDefinitionId) {}

  @ParameterizedTest
  @EnumSource(
      value = TriggerType.class,
      names = {"API", "MANUAL"})
  @DisplayName("API/人工并发重投同一 HTTP 请求及 Kafka 消息仅生成一条成功运行和业务写入")
  void shouldCompleteOneWorkflowAndBusinessEffect_whenSameLaunchIsSubmittedConcurrently(
      TriggerType triggerType) throws Exception {
    String deleteSeedRequestSql = """
        delete from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    String selectLaunchPayloadSql = """
        select payload::text from batch.trigger_outbox_event
         where tenant_id = ? and request_id = ? and publish_status = 'PUBLISHED'
        """;
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", triggerType);
    String requestId = seed.requestId();
    String dedupKey = seed.dedupKey();
    jdbcTemplate.update(deleteSeedRequestSql, TENANT, requestId);
    long uniqueSuffix = System.nanoTime();
    String customerNo = "R31" + Long.toUnsignedString(uniqueSuffix);
    LocalDate businessDate = LocalDate.of(2026, 4, 30);
    Map<String, Object> customer = Map.of(
        "customerNo",
        customerNo,
        "customerName",
        "R31 concurrent launch",
        "customerType",
        "PERSONAL",
        "certificateNo",
        customerNo,
        "mobileNo",
        "13800000001",
        "email",
        "r31@example.com",
        "status",
        "ACTIVE");
    List<Map<String, Object>> customers = List.of(customer);
    String content = JsonUtils.toJson(customers);
    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId(TENANT);
    request.setWorkflowDefinitionId(seed.workflowDefinitionId());
    request.setBizDate(businessDate);
    request.setTriggerType(triggerType);
    request.setParams(Map.of(
        "fileFormatType",
        "JSON",
        "templateCode",
        "IMP-CUSTOMER-JSON-ARRAY",
        "bizType",
        "CUSTOMER",
        "content",
        content));
    triggerService();
    Integer triggerPort =
        triggerContext.getEnvironment().getProperty("local.server.port", Integer.class);
    URI launchUri = URI.create("http://127.0.0.1:" + triggerPort + "/api/triggers/launch");
    String requestBody = JsonUtils.toJson(request);
    HttpRequest.BodyPublisher requestPublisher = HttpRequest.BodyPublishers.ofString(requestBody);
    Duration requestTimeout = Duration.ofSeconds(30);
    HttpRequest httpRequest = HttpRequest.newBuilder(launchUri)
        .timeout(requestTimeout)
        .header("Content-Type", "application/json")
        .header("Idempotency-Key", dedupKey)
        .header("X-Request-Id", requestId)
        .POST(requestPublisher)
        .build();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2);
        HttpClient client = HttpClient.newHttpClient()) {
      var submit = (Callable<HttpResponse<String>>) () -> {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("concurrent launch barrier was not released");
        }
        HttpResponse.BodyHandler<String> responseHandler = HttpResponse.BodyHandlers.ofString();
        return client.send(httpRequest, responseHandler);
      };
      var first = executor.submit(submit);
      var second = executor.submit(submit);
      boolean bothReady = ready.await(10, TimeUnit.SECONDS);
      assertThat(bothReady).isTrue();
      start.countDown();
      HttpResponse<String> firstResponse = first.get(40, TimeUnit.SECONDS);
      HttpResponse<String> secondResponse = second.get(40, TimeUnit.SECONDS);
      assertThat(firstResponse.statusCode()).as(firstResponse.body()).isEqualTo(200);
      assertThat(secondResponse.statusCode()).as(secondResponse.body()).isEqualTo(200);
    } finally {
      start.countDown();
    }
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertSuccessfulLaunchIdentity(seed, businessDate, customerNo));

    String persistedEnvelope =
        jdbcTemplate.queryForObject(selectLaunchPayloadSql, String.class, TENANT, requestId);
    String topic = topicNamespace.topic(BatchTopics.TRIGGER_LAUNCH_V1);
    String messageKey = TENANT + ":" + seed.requestId();
    RecordMetadata finalMetadata = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      var sendResult =
          kafkaTemplate.send(topic, messageKey, persistedEnvelope).get(15, TimeUnit.SECONDS);
      finalMetadata = sendResult.getRecordMetadata();
    }
    TopicPartition partition = new TopicPartition(topic, finalMetadata.partition());
    long expectedOffset = finalMetadata.offset() + 1;
    String consumerGroup = triggerLaunchConsumer.consumerGroupId();
    String bootstrapServers = kafkaBootstrapServers();
    Map<String, Object> adminProperties = Map.of("bootstrap.servers", bootstrapServers);
    try (Admin admin = Admin.create(adminProperties)) {
      await()
          .atMost(Duration.ofSeconds(30))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(() -> {
            var offsets = admin
                .listConsumerGroupOffsets(consumerGroup)
                .partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS);
            var committed = offsets.get(partition);
            assertThat(committed).isNotNull();
            assertThat(committed.offset()).isGreaterThanOrEqualTo(expectedOffset);
          });
    }
    assertSuccessfulLaunchIdentity(seed, businessDate, customerNo);
  }

  private void assertSuccessfulLaunchIdentity(
      LaunchSeed seed, LocalDate businessDate, String customerNo) {
    String selectIdentitySql = """
        select count(distinct tr.id) as request_count,
               count(distinct wr.id) as root_count,
               count(distinct nr.id) as job_node_count,
               count(distinct ji.id) as job_count,
               count(*) filter (where wr.run_status = 'SUCCESS' and nr.node_status = 'SUCCESS'
                                  and ji.instance_status = 'SUCCESS') as success_count,
               count(*) filter (where tr.biz_date = ? and wr.biz_date = ? and ji.biz_date = ?
                                  and child.tenant_id = tr.tenant_id and ji.tenant_id = tr.tenant_id
                                  and ji.job_code = ?
                                  and ji.trigger_request_id = child.id
                                  and child.parent_workflow_run_id = wr.id
                                  and child.parent_node_code = nr.node_code) as exact_identity_count
          from batch.trigger_request tr
          join batch.workflow_run wr on wr.tenant_id = tr.tenant_id and wr.trigger_request_id = tr.id
          join batch.workflow_node_run nr on nr.workflow_run_id = wr.id and nr.node_type = 'JOB'
          join batch.job_instance ji on ji.id = nr.job_instance_id
          join batch.trigger_request child on child.id = ji.trigger_request_id
         where tr.tenant_id = ? and tr.request_id = ?
        """;
    Map<String, Object> identity = jdbcTemplate.queryForMap(
        selectIdentitySql,
        businessDate,
        businessDate,
        businessDate,
        seed.jobCode(),
        TENANT,
        seed.requestId());
    assertThat(identity).allSatisfy((key, value) -> {
      long identityCount = ((Number) value).longValue();
      assertThat(identityCount).as(key).isEqualTo(1);
    });
    String selectTriggerOutboxCountSql = """
        select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id = ?
        """;
    Long outboxCount = jdbcTemplate.queryForObject(
        selectTriggerOutboxCountSql, Long.class, TENANT, seed.requestId());
    assertThat(outboxCount).isEqualTo(1);
    String selectBusinessEffectSql = """
        select count(*) from biz.customer_account where tenant_id = ? and customer_no = ?
        """;
    JdbcTemplate businessJdbc = new JdbcTemplate(importBusinessDataSource);
    Long businessRows =
        businessJdbc.queryForObject(selectBusinessEffectSql, Long.class, TENANT, customerNo);
    assertThat(businessRows).isEqualTo(1);
  }

  @Test
  @DisplayName("旧 Trigger envelope 在 consumer 停止期间进入 Kafka，重启后被拒绝且不写业务数据")
  void staleTriggerEnvelopeVersion_doesNotCreateRequestOrWorkflowRun() throws Exception {
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectCountSql2 = """
            select count(*)
              from batch.workflow_run wr
              join batch.trigger_request tr
                on tr.tenant_id = wr.tenant_id and tr.id = wr.trigger_request_id
             where tr.tenant_id = ? and tr.request_id = ?
            """;
    final String selectCountSql3 = """
        select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?
    """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "GENERAL", "E2E_STALE_CALLBACK", TriggerType.API);
    jdbcTemplate.update(deleteTriggerRequestSql, TENANT, seed.requestId());

    LaunchRequest currentWorkflowRequest = new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 10, 21),
        TriggerType.API,
        seed.requestId(),
        "stale-trigger-callback",
        Map.of());
    LaunchEnvelope oldEnvelope = new LaunchEnvelope(
        currentWorkflowRequest,
        seed.dedupKey(),
        BatchDateTimeSupport.utcNow(),
        LaunchEnvelope.CURRENT_VERSION - 1,
        null);
    Counter unsupportedVersion =
        meterRegistry.counter("batch.trigger.launch.failed.total", "reason", "unsupported_version");
    double failuresBefore = unsupportedVersion.count();
    var listener = kafkaListenerEndpointRegistry.getListenerContainer(
        OrchestratorKafkaConsumerConfiguration.TRIGGER_LAUNCH_LISTENER_ID);
    assertThat(listener).isNotNull();
    listener.stop();
    await().atMost(Duration.ofSeconds(15)).until(() -> !listener.isRunning());

    try {
      kafkaTemplate
          .send(
              topicNamespace.topic(BatchTopics.TRIGGER_LAUNCH_V1),
              TENANT + ":" + seed.requestId(),
              JsonUtils.toJson(oldEnvelope))
          .get();

      assertThat(unsupportedVersion.count()).isEqualTo(failuresBefore);
      Integer selectedTriggerRequestCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, seed.requestId());
      assertThat(selectedTriggerRequestCount).isZero();

      listener.start();
      await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(
              () -> assertThat(unsupportedVersion.count()).isEqualTo(failuresBefore + 1));
    } finally {
      if (!listener.isRunning()) {
        listener.start();
      }
    }

    Integer selectedTriggerRequestCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, seed.requestId());
    assertThat(selectedTriggerRequestCountRead2).isZero();
    Integer selectedWorkflowRunCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, seed.requestId());
    assertThat(selectedWorkflowRunCount).isZero();
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, seed.dedupKey());
    assertThat(selectedJobInstanceCount).isZero();
  }

  @Test
  @DisplayName("当前版本的旧 Job 根请求在 consumer 重启后被拒绝且不写业务数据")
  void currentEnvelopeWithLegacyJobRoot_doesNotCreateRequestOrWorkflowRun() throws Exception {
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectCountSql2 = """
            select count(*)
              from batch.workflow_run wr
              join batch.trigger_request tr
                on tr.tenant_id = wr.tenant_id and tr.id = wr.trigger_request_id
             where tr.tenant_id = ? and tr.request_id = ?
            """;
    final String selectCountSql3 = """
        select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?
    """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "GENERAL", "E2E_LEGACY_JOB_ROOT", TriggerType.API);
    jdbcTemplate.update(deleteTriggerRequestSql, TENANT, seed.requestId());

    LaunchRequest legacyJobRoot = new LaunchRequest(
        TENANT,
        seed.jobCode(),
        LocalDate.of(2026, 10, 21),
        TriggerType.API,
        seed.requestId(),
        "legacy-job-root-after-restart",
        Map.of());
    LaunchEnvelope envelope =
        LaunchEnvelope.of(legacyJobRoot, seed.dedupKey(), BatchDateTimeSupport.utcNow());
    Counter rejectedBusinessMessage = meterRegistry.counter(
        "batch.trigger.launch.failed.total", "tenant", TENANT, "reason", "business");
    double failuresBefore = rejectedBusinessMessage.count();
    var listener = kafkaListenerEndpointRegistry.getListenerContainer(
        OrchestratorKafkaConsumerConfiguration.TRIGGER_LAUNCH_LISTENER_ID);
    assertThat(listener).isNotNull();
    listener.stop();
    await().atMost(Duration.ofSeconds(15)).until(() -> !listener.isRunning());

    try {
      kafkaTemplate
          .send(
              topicNamespace.topic(BatchTopics.TRIGGER_LAUNCH_V1),
              TENANT + ":" + seed.requestId(),
              JsonUtils.toJson(envelope))
          .get();

      assertThat(rejectedBusinessMessage.count()).isEqualTo(failuresBefore);
      Integer selectedTriggerRequestCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, seed.requestId());
      assertThat(selectedTriggerRequestCount).isZero();

      listener.start();
      await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(
              () -> assertThat(rejectedBusinessMessage.count()).isEqualTo(failuresBefore + 1));
    } finally {
      if (!listener.isRunning()) {
        listener.start();
      }
    }

    Integer selectedTriggerRequestCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, seed.requestId());
    assertThat(selectedTriggerRequestCountRead2).isZero();
    Integer selectedWorkflowRunCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, seed.requestId());
    assertThat(selectedWorkflowRunCount).isZero();
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, seed.dedupKey());
    assertThat(selectedJobInstanceCount).isZero();
  }
}

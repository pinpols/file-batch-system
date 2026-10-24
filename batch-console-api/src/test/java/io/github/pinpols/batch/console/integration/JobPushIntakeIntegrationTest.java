package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.*;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.service.JobPushIntakeService;
import io.github.pinpols.batch.console.domain.notification.service.WorkflowTerminalNotificationService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;

@SpringBootTest(classes=BatchConsoleApiApplication.class,webEnvironment=SpringBootTest.WebEnvironment.MOCK)
class JobPushIntakeIntegrationTest extends AbstractIntegrationTest {
  @Autowired private WorkflowTerminalNotificationService terminal;
  @Autowired private JobPushIntakeService push;
  @Autowired private ConsolePushSubscriptionMapper subscriptions;
  @Autowired private ConsolePushProperties properties;
  @Autowired private JdbcTemplate jdbc;
  @Autowired @Qualifier("transactionManager") private PlatformTransactionManager transactions;
  private final List<String> tenants=new ArrayList<>();

  @BeforeEach void enableIntake() {
    properties.setEnabled(true); properties.getJobNotify().setEnabled(true);
  }

  @AfterEach void cleanup() {
    properties.setEnabled(false); properties.getJobNotify().setEnabled(false);
    for(String tenant:tenants) {
      jdbc.update("delete from batch.console_push_job_delivery where tenant_id=?",tenant);
      jdbc.update("delete from batch.workflow_terminal_notification_receipt where tenant_id=?",tenant);
      for(var device:subscriptions.findByTenant(tenant))
        subscriptions.deleteByEndpoint(tenant,device.getUsername(),device.getEndpoint());
    }
  }

  @Test void firstReceiptFreezesTwoDevicesAndReplayDoesNotExpandNewDevices() {
    String tenant=tenant();subscribe(tenant,"alice","one");subscribe(tenant,"alice","two");
    accept(tenant,job(tenant,"SUCCESS",false,"alice"));
    assertThat(count("console_push_job_delivery",tenant)).isEqualTo(3);
    assertThat(count("workflow_terminal_notification_receipt",tenant)).isEqualTo(1);
    subscribe(tenant,"alice","later");
    accept(tenant,job(tenant,"SUCCESS",false,"alice"));
    assertThat(count("console_push_job_delivery",tenant)).isEqualTo(3);
    assertThat(jdbc.queryForList("select distinct delivery_status from batch.console_push_job_delivery where tenant_id=? and record_type='DEVICE'",String.class,tenant))
        .containsExactly("PENDING");
    assertThat(jdbc.queryForObject("select count(distinct payload_json) from batch.console_push_job_delivery where tenant_id=?",Integer.class,tenant)).isEqualTo(1);
  }

  @Test void noOperatorIsRecordedWithoutFindingAnArbitraryRecipient() {
    String tenant=tenant();subscribe(tenant,"alice","one");
    accept(tenant,job(tenant,"SUCCESS",false,null));
    assertThat(decision(tenant)).isEqualTo("NO_OPERATOR");
    assertThat(count("console_push_job_delivery",tenant)).isEqualTo(1);
  }

  @Test void disabledDecisionRemainsFrozenWhenPushIsLaterEnabled() {
    String tenant=tenant();subscribe(tenant,"alice","one");properties.getJobNotify().setEnabled(false);
    var job=job(tenant,"SUCCESS",false,"alice");accept(tenant,job);
    properties.getJobNotify().setEnabled(true);accept(tenant,job);
    assertThat(decision(tenant)).isEqualTo("DISABLED");
    assertThat(count("console_push_job_delivery",tenant)).isEqualTo(1);
  }

  @ParameterizedTest @CsvSource({"SUCCESS_DRY_RUN,true","FAILED_DRY_RUN,true","SUCCESS_DRY_RUN,false","FAILED_DRY_RUN,false"})
  void dryRunCreatesOnlyAnExplicitDecision(String status,boolean dry) {
    String tenant=tenant();subscribe(tenant,"alice","one");
    accept(tenant,job(tenant,status,dry,"alice"));
    assertThat(decision(tenant)).isEqualTo("DRY_RUN");
    assertThat(count("console_push_job_delivery",tenant)).isEqualTo(1);
  }

  @ParameterizedTest @ValueSource(strings={"CANCELLED","TERMINATED"})
  void cancellationAndTerminationKeepTheirOwnPushFacts(String status) {
    String tenant=tenant();subscribe(tenant,"alice","one");
    accept(tenant,job(tenant,status,false,"alice"));
    assertThat(decision(tenant)).isEqualTo("QUEUED");
    assertThat(count("console_push_job_delivery",tenant)).isEqualTo(2);
    assertThat(count("notification_delivery_log",tenant)).isZero();
    assertThat(count("webhook_delivery_log",tenant)).isZero();
  }

  @Test void failureWhileWritingDeviceRollsBackReceiptAndDecision() {
    String tenant=tenant();subscribe(tenant,"alice","one");
    jdbc.execute("""
        create function batch.reject_test_push_device() returns trigger language plpgsql as $$
        begin if NEW.record_type='DEVICE' and NEW.tenant_id='%s' then
          raise exception 'injected device write failure'; end if; return NEW; end $$
        """.formatted(tenant));
    jdbc.execute("create trigger reject_test_push_device before insert on batch.console_push_job_delivery for each row execute function batch.reject_test_push_device()");
    try {
      assertThatThrownBy(()->accept(tenant,job(tenant,"SUCCESS",false,"alice"))).isInstanceOf(RuntimeException.class);
      assertThat(count("workflow_terminal_notification_receipt",tenant)).isZero();
      assertThat(count("console_push_job_delivery",tenant)).isZero();
    } finally {
      jdbc.execute("drop trigger reject_test_push_device on batch.console_push_job_delivery");
      jdbc.execute("drop function batch.reject_test_push_device()");
    }
    accept(tenant,job(tenant,"SUCCESS",false,"alice"));
    assertThat(count("console_push_job_delivery",tenant)).isEqualTo(2);
  }

  @Test void conflictingOperatorCannotRedirectExistingDeviceIntent() {
    String tenant=tenant();subscribe(tenant,"alice","one");subscribe(tenant,"bob","other");
    accept(tenant,job(tenant,"SUCCESS",false,"alice"));
    assertThatThrownBy(()->accept(tenant,job(tenant,"SUCCESS",false,"bob"))).isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbc.queryForList("select distinct operator_id from batch.console_push_job_delivery where tenant_id=?",String.class,tenant)).containsExactly("alice");
    assertThat(count("console_push_job_delivery",tenant)).isEqualTo(2);
  }

  @Test void enqueueRequiresTheReceiptTransaction() {
    String tenant=tenant();
    assertThatThrownBy(()->push.accept(tenant,key(tenant,"SUCCESS"),job(tenant,"SUCCESS",false,"alice")))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(count("console_push_job_delivery",tenant)).isZero();
  }

  @Test void forcedRlsSeparatesTenantReadsAndRejectsCrossTenantInsert() throws Exception {
    String own=tenant();String other=tenant();subscribe(own,"alice","one");
    accept(own,job(own,"SUCCESS",false,"alice"));accept(other,job(other,"SUCCESS",false,null));
    String role="push_intake_reader_"+Long.toUnsignedString(System.nanoTime());
    jdbc.execute("create role "+role+" nosuperuser nobypassrls");
    jdbc.execute("grant usage on schema batch to "+role);
    jdbc.execute("grant select,insert on batch.console_push_job_delivery to "+role);
    jdbc.execute("grant select,insert on batch.workflow_terminal_notification_receipt to "+role);
    jdbc.execute("grant select on batch.console_push_subscription to "+role);
    jdbc.execute("grant usage on sequence batch.console_push_job_delivery_id_seq to "+role);
    try(var connection=DriverManager.getConnection(platformJdbcUrl(),platformJdbcUsername(),platformJdbcPassword());
        var sql=connection.createStatement()) {
      sql.execute("set role "+role);
      try(var set=connection.prepareStatement("select set_config('app.tenant_id',?,false)")) {
        set.setString(1,own);set.execute();
      }
      try(var rows=sql.executeQuery("select count(*) from batch.console_push_job_delivery")) {
        assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(2);
      }
      assertThatThrownBy(()-> {
        try(var insert=connection.prepareStatement("insert into batch.console_push_job_delivery (tenant_id,terminal_event_key,job_instance_id,record_type,decision,event_type,payload_json) values (?,?,10,'DECISION','DISABLED','JOB_SUCCESS','{}'::jsonb)")) {
          insert.setString(1,other);insert.setString(2,key(other,"SUCCESS"));insert.executeUpdate();
        }
      }).isInstanceOfSatisfying(java.sql.SQLException.class,e->{
        assertThat(e.getSQLState()).isEqualTo("42501");
        assertThat(e.getMessage()).contains("row-level security");
      });
      String applicationTenant=tenant();
      subscribe(applicationTenant,"alice","ordinary-role-device");
      RlsTenantContextHolder.runWithTenant(applicationTenant,()->new TransactionTemplate(transactions)
          .executeWithoutResult(transaction->{
            jdbc.execute("set local role "+role);
            terminal.acceptJob(key(applicationTenant,"CANCELLED"),
                JsonUtils.toJson(job(applicationTenant,"CANCELLED",false,"alice")));
          }));
      assertThat(decision(applicationTenant)).isEqualTo("QUEUED");
      assertThat(count("console_push_job_delivery",applicationTenant)).isEqualTo(2);
      assertThat(count("workflow_terminal_notification_receipt",applicationTenant)).isEqualTo(1);
    } finally {jdbc.execute("drop owned by "+role);jdbc.execute("drop role "+role);}
  }

  private String tenant() {String tenant="push-intake-"+UUID.randomUUID();tenants.add(tenant);return tenant;}
  private static String key(String tenant,String status) {return tenant+":job:10:terminal:"+status;}
  private void accept(String tenant,Map<String,Object> job) {
    RlsTenantContextHolder.runWithTenant(tenant,()->terminal.acceptJob(key(tenant,(String)job.get("instanceStatus")),JsonUtils.toJson(job)));
  }
  private String decision(String tenant) {return jdbc.queryForObject("select decision from batch.console_push_job_delivery where tenant_id=? and record_type='DECISION'",String.class,tenant);}
  private int count(String table,String tenant) {return jdbc.queryForObject("select count(*) from batch."+table+" where tenant_id=?",Integer.class,tenant);}
  private void subscribe(String tenant,String operator,String device) {
    var sub=new ConsolePushSubscriptionEntity();sub.setTenantId(tenant);sub.setUsername(operator);
    sub.setEndpoint("https://push.example.test/"+tenant+"/"+device);sub.setP256dhKey("fixture-key");sub.setAuthSecret("fixture-auth");subscriptions.upsert(sub);
  }
  private static Map<String,Object> job(String tenant,String status,boolean dry,String operator) {
    Map<String,Object> job=new LinkedHashMap<>(Map.ofEntries(Map.entry("schemaVersion","v1"),Map.entry("entityType","JOB_INSTANCE"),
        Map.entry("tenantId",tenant),Map.entry("jobInstanceId",10L),Map.entry("terminalVersion",1L),Map.entry("jobCode","IMPORT"),
        Map.entry("instanceStatus",status),Map.entry("dryRun",dry),Map.entry("bizDate","2026-10-01"),Map.entry("finishedAt","2026-10-01T00:00:00Z")));
    job.put("operatorId",operator);return job;
  }
}

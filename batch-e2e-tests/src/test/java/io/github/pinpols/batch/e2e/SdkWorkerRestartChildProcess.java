package io.github.pinpols.batch.e2e;

import io.github.pinpols.batch.sdk.client.BatchPlatformClient;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 不执行状态 SQL、不手动发布，也不注入 Kafka 重投；由生产轮询器执行恢复。 */
public final class SdkWorkerRestartChildProcess {

  private SdkWorkerRestartChildProcess() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("expected worker properties path");
    }
    Properties properties = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    String mode = properties.getProperty("mode");
    Path ready = Path.of(properties.getProperty("ready"));
    Path entered = Path.of(properties.getProperty("entered"));
    Path completed = Path.of(properties.getProperty("completed"));
    Path stop = Path.of(properties.getProperty("stop"));

    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "PROCESS";
      }

      @Override
      public boolean isSafeToReplay(SdkTaskContext context) {
        // 仅此测试处理器：稳定业务键下事务性插入/核对，重复执行不覆盖已提交的不同版本。
        return true;
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext context) {
        try {
          Files.writeString(entered, "task=" + context.taskId());
          if ("block".equals(mode)) {
            while (true) {
              Thread.sleep(1000L);
            }
          }
          if (!properties
              .getProperty("root-marker")
              .equals(context.parameters().get("rootMarker"))) {
            throw new IllegalStateException("root launch parameters were not preserved");
          }
          var selected = context.crossDayResults("prior_day").getFirst();
          String selectedName = String.valueOf(selected.output().get("sourceBatch"));
          try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                  properties.getProperty("business.jdbc.url"),
                  properties.getProperty("business.jdbc.username"),
                  properties.getProperty("business.jdbc.password"),
                  true)) {
            String tenantId = properties.getProperty("tenant");
            String customerNo = properties.getProperty("customer-no");
            if (!Objects.equals(tenantId, context.tenantId())) {
              throw new IllegalStateException("SDK child business tenant differs from claimed tenant");
            }
            persistCustomerEffect(
                dataSource, tenantId, customerNo, selectedName, selected.resultVersionId());
          }
          Files.writeString(completed, selected.resultVersionId() + ":" + selectedName);
          return SdkTaskResult.ok(
              "consumed pinned cross-day result " + selected.resultVersionId(),
              Map.of("resultVersionId", selected.resultVersionId(), "sourceBatch", selectedName));
        } catch (Exception failure) {
          throw new IllegalStateException("SDK child handler failed", failure);
        }
      }
    };

    BatchPlatformClient client = BatchPlatformClient.builder(BatchPlatformClientConfig.builder()
            .baseUrl(properties.getProperty("base-url"))
            .tenantId(properties.getProperty("tenant"))
            .workerCode(properties.getProperty("worker-code"))
            .kafkaBootstrap(properties.getProperty("kafka-bootstrap"))
            .kafkaTopicPattern("batch\\.task\\.dispatch\\.process\\..*")
            .kafkaGroupId(properties.getProperty("worker-code"))
            .build())
        .register(handler)
        .build();
    client.start();
    Files.writeString(ready, "started");
    try {
      while (!Files.exists(stop)) {
        Thread.sleep(100L);
      }
    } finally {
      client.stop();
    }
  }
  private static void persistCustomerEffect(
      SingleConnectionDataSource dataSource,
      String tenantId,
      String customerNo,
      String selectedName,
      Long resultVersionId) {
    String insertCustomerAccountSql = """
        insert into biz.customer_account (
          tenant_id, customer_no, customer_name, customer_type, status,
          source_batch_no, created_by, updated_by)
        values (?, ?, ?, 'PERSONAL', 'ACTIVE', ?, 'cross-day-sdk-e2e', 'cross-day-sdk-e2e')
        on conflict (tenant_id, customer_no) do nothing
        """;
    String selectCommittedCustomerSql = """
        select customer_name, customer_type, status, source_batch_no
          from biz.customer_account
         where tenant_id = ? and customer_no = ?
         for update
        """;
    JdbcTemplate businessJdbc = new JdbcTemplate(dataSource);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    String frozenResultVersion = String.valueOf(resultVersionId);
    transaction.executeWithoutResult(status -> {
      businessJdbc.update(
          insertCustomerAccountSql, tenantId, customerNo, selectedName, frozenResultVersion);
      Map<String, Object> committed =
          businessJdbc.queryForMap(selectCommittedCustomerSql, tenantId, customerNo);
      boolean sameFrozenEffect = Objects.equals(selectedName, committed.get("customer_name"))
          && Objects.equals(frozenResultVersion, committed.get("source_batch_no"))
          && "PERSONAL".equals(committed.get("customer_type"))
          && "ACTIVE".equals(committed.get("status"));
      if (!sameFrozenEffect) {
        throw new IllegalStateException(
            "existing customer business effect differs from the frozen SDK result");
      }
    });
  }

}

package io.github.pinpols.batch.e2e;

import io.github.pinpols.batch.sdk.client.BatchPlatformClient;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Properties;

/** Separate JVM SDK worker used to prove process-loss recovery through persisted CLAIM state. */
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
      public SdkTaskResult execute(io.github.pinpols.batch.sdk.task.SdkTaskContext context) {
        try {
          Files.writeString(entered, "task=" + context.taskId());
          if ("block".equals(mode)) {
            while (true) {
              Thread.sleep(1000L);
            }
          }
          if (!properties.getProperty("root-marker")
              .equals(context.parameters().get("rootMarker"))) {
            throw new IllegalStateException("root launch parameters were not preserved");
          }
          var selected = context.crossDayResults("prior_day").getFirst();
          String selectedName = String.valueOf(selected.output().get("sourceBatch"));
          try (var connection = DriverManager.getConnection(
                  properties.getProperty("business.jdbc.url"),
                  properties.getProperty("business.jdbc.username"),
                  properties.getProperty("business.jdbc.password"));
              var statement = connection.prepareStatement(
                  "insert into biz.customer_account (tenant_id, customer_no, customer_name, "
                      + "customer_type, status, source_batch_no, created_by, updated_by) "
                      + "values (?, ?, ?, 'PERSONAL', 'ACTIVE', ?, 'cross-day-sdk-e2e', "
                      + "'cross-day-sdk-e2e')")) {
            statement.setString(1, properties.getProperty("tenant"));
            statement.setString(2, properties.getProperty("customer-no"));
            statement.setString(3, selectedName);
            statement.setString(4, String.valueOf(selected.resultVersionId()));
            statement.executeUpdate();
          }
          Files.writeString(
              completed, selected.resultVersionId() + ":" + selectedName);
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
}

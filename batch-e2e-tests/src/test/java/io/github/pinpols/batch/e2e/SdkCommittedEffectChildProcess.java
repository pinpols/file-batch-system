package io.github.pinpols.batch.e2e;

import io.github.pinpols.batch.sdk.client.BatchPlatformClient;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 独立 SDK 进程在业务提交后停留或回报，由父测试控制真实进程退出与网络故障。 */
public final class SdkCommittedEffectChildProcess {

  private SdkCommittedEffectChildProcess() {}

  public static void main(String[] args) throws Exception {
    Properties properties = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    DriverManagerDataSource businessDataSource = new DriverManagerDataSource(
        properties.getProperty("business.url"),
        properties.getProperty("business.username"),
        properties.getProperty("business.password"));
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "ATOMIC";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext context) {
        String insertEffectSql = """
            insert into biz.customer_account
                (tenant_id, customer_no, customer_name, customer_type, status, source_batch_no)
            values (?, ?, ?, 'PERSONAL', 'ACTIVE', ?)
            """;
        String marker = properties.getProperty("marker");
        if (!marker.equals(context.parameters().get("recoveryMarker"))) {
          throw new IllegalStateException("frozen business marker differs");
        }
        businessJdbc.update(
            insertEffectSql, context.tenantId(), "sdk-process-effect", marker, marker);
        try {
          Files.writeString(Path.of(properties.getProperty("committed")), "committed");
          if (Boolean.parseBoolean(properties.getProperty("block-after-commit"))) {
            while (true) Thread.sleep(1000L);
          }
        } catch (Exception failure) {
          throw new IllegalStateException("SDK child failed after business commit", failure);
        }
        return SdkTaskResult.ok("committed", Map.of("marker", marker));
      }
    };
    BatchPlatformClientConfig configuration = BatchPlatformClientConfig.builder()
        .baseUrl(properties.getProperty("base-url"))
        .tenantId(properties.getProperty("tenant"))
        .workerCode(properties.getProperty("worker-code"))
        .kafkaBootstrap(properties.getProperty("kafka-bootstrap"))
        .kafkaTopicPattern("batch\\.task\\.dispatch\\.atomic.*")
        .kafkaGroupId(properties.getProperty("group"))
        .httpTimeout(Duration.ofSeconds(5))
        .heartbeatInterval(Duration.ofSeconds(10))
        .leaseRenewInterval(Duration.ofSeconds(10))
        .build();
    BatchPlatformClient client =
        BatchPlatformClient.builder(configuration).register(handler).build();
    client.start();
    Files.writeString(Path.of(properties.getProperty("ready")), "ready");
    while (true) Thread.sleep(1000L);
  }
}

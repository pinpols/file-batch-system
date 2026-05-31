package com.example.batch.ext.sample;

import com.example.batch.sdk.client.BatchPlatformClient;
import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-035 Phase 1.4 — 租户自托管 worker 示范进程。
 *
 * <p>用法:配置环境变量 {@code BATCH_BASE_URL / BATCH_TENANT_ID / BATCH_WORKER_CODE / BATCH_KAFKA /
 * BATCH_API_KEY},运行 {@code java -jar sample-tenant-worker.jar}。注册两个示范 handler:
 *
 * <ul>
 *   <li>{@code echo} — 把 parameters 原样返回,演示 handler 最小契约
 *   <li>{@code sleep} — 按参数 {@code millis} sleep,演示长任务 + lease 续约场景
 * </ul>
 *
 * <p>真业务复制本文件,改 main() 里 config,把两个示范 handler 换成自己的实现即可。
 */
public final class SampleTenantWorker {

  private static final Logger log = LoggerFactory.getLogger(SampleTenantWorker.class);

  public static void main(String[] args) {
    BatchPlatformClientConfig config =
        BatchPlatformClientConfig.builder()
            .baseUrl(requireEnv("BATCH_BASE_URL"))
            .apiKey(System.getenv("BATCH_API_KEY")) // P2 启用,P1 阶段可空
            .tenantId(requireEnv("BATCH_TENANT_ID"))
            .workerCode(requireEnv("BATCH_WORKER_CODE"))
            .kafkaBootstrap(requireEnv("BATCH_KAFKA"))
            .kafkaTopicPattern(
                System.getenv().getOrDefault(
                    "BATCH_TOPIC_PATTERN",
                    "batch.task.dispatch." + requireEnv("BATCH_TENANT_ID") + ".*"))
            .kafkaGroupId(
                System.getenv().getOrDefault(
                    "BATCH_KAFKA_GROUP", requireEnv("BATCH_TENANT_ID") + "-sample-workers"))
            .maxConcurrentTasks(4)
            .heartbeatInterval(Duration.ofSeconds(30))
            .leaseRenewInterval(Duration.ofSeconds(60))
            .build();

    BatchPlatformClient client =
        BatchPlatformClient.builder(config)
            .register(new EchoHandler())
            .register(new SleepHandler())
            .build();

    Runtime.getRuntime()
        .addShutdownHook(new Thread(client::stop, "sample-worker-shutdown-hook"));

    log.info("starting sample tenant worker for tenant={}", config.getTenantId());
    client.start();
  }

  private static String requireEnv(String name) {
    String v = System.getenv(name);
    if (v == null || v.isBlank()) {
      throw new IllegalStateException("missing required env: " + name);
    }
    return v;
  }

  // ─── 示范 handler ────────────────────────────────────────────────────────────

  static final class EchoHandler implements SdkTaskHandler {
    @Override
    public String taskType() {
      return "echo";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      log.info("echo handler taskId={} params={}", ctx.taskId(), ctx.parameters());
      return SdkTaskResult.ok("echoed", Map.copyOf(ctx.parameters()));
    }
  }

  static final class SleepHandler implements SdkTaskHandler {
    @Override
    public String taskType() {
      return "sleep";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      Object m = ctx.parameters().get("millis");
      long millis = m instanceof Number n ? n.longValue() : 1000L;
      log.info("sleep handler taskId={} sleeping {}ms", ctx.taskId(), millis);
      try {
        Thread.sleep(millis);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return SdkTaskResult.fail("interrupted");
      }
      return SdkTaskResult.ok("slept " + millis + "ms", Map.of("millis", millis));
    }
  }
}

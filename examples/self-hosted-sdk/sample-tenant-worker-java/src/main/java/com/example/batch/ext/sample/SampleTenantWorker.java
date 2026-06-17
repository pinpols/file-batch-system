package com.example.batch.ext.sample;

import com.example.batch.ext.sample.handlers.*;
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
            // P3 Kafka SASL/SCRAM(prod 必填,本地联调不设走 PLAINTEXT)
            .kafkaSecurityProtocol(System.getenv("BATCH_KAFKA_PROTOCOL"))
            .kafkaSaslMechanism(System.getenv("BATCH_KAFKA_SASL_MECHANISM"))
            .kafkaSaslJaasConfig(System.getenv("BATCH_KAFKA_SASL_JAAS"))
            .build();

    BatchPlatformClient client =
        BatchPlatformClient.builder(config)
            .register(new EchoHandler())
            .register(new SleepHandler())
            // 平台 base workerType(ATOMIC)处理器:演示自托管 worker 直接承接平台 ATOMIC 派单类别。
            // 与 sample_atomic_echo(自定义 taskType,仅 catalog/FakePlatform 演示)不同 —— 平台真实派单的
            // 路由键 = job_definition.job_type(如 ATOMIC),只有 base workerType 能解析出 dispatch topic,
            // 故真链路 dispatch-execute 验证(sim 06 Phase 2)走这个 handler。
            .register(new AtomicBaseEchoHandler())
            // ADR-036 五大业务模板 sample(各 shape 一个 echo demo)
            .register(new AtomicEchoHandler())
            .register(new ImportEchoHandler())
            .register(new ExportEchoHandler())
            .register(new ProcessEchoHandler())
            .register(new DispatchEchoHandler())
            .build();

    Runtime.getRuntime()
        .addShutdownHook(new Thread(client::stop, "sample-worker-shutdown-hook"));

    log.info("starting sample tenant worker for tenant={}", config.getTenantId());
    // P7-3:register 失败 → start() 抛异常,此处不吞,记 FATAL 后以非 0 退出码结束,
    // 让 K8s / systemd 重启拉起(register 失败多为平台不可达 / apiKey 失效 / 配置错误)。
    try {
      client.start();
    } catch (RuntimeException startEx) {
      log.error(
          "FATAL: BatchPlatformClient.start() failed for tenant={}, exiting non-zero for restart",
          config.getTenantId(),
          startEx);
      System.exit(1);
    }
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

  /**
   * 平台 base workerType {@code ATOMIC} 处理器 —— 自托管 worker 承接平台 ATOMIC 派单类别的最小 demo。
   *
   * <p>taskType() 必须等于平台 {@code job_definition.job_type}(派单消息 workerType 字段),平台据此 resolve
   * dispatch topic + 路由;sim 06 dispatch-execute 腿据 "ATOMIC base handler taskId=" 日志断言真链路执行。
   */
  static final class AtomicBaseEchoHandler implements SdkTaskHandler {
    @Override
    public String taskType() {
      return "ATOMIC";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      log.info("ATOMIC base handler taskId={} params={}", ctx.taskId(), ctx.parameters());
      return SdkTaskResult.ok("atomic-echoed", Map.copyOf(ctx.parameters()));
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

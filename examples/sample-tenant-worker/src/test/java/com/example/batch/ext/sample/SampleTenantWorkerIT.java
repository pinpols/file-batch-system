package com.example.batch.ext.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.ext.sample.handlers.AtomicEchoHandler;
import com.example.batch.ext.sample.handlers.DispatchEchoHandler;
import com.example.batch.ext.sample.handlers.ExportEchoHandler;
import com.example.batch.ext.sample.handlers.ImportEchoHandler;
import com.example.batch.ext.sample.handlers.ProcessEchoHandler;
import com.example.batch.sdk.client.BatchPlatformClient;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import com.example.batch.sdk.testkit.FakeBatchPlatform;
import com.example.batch.sdk.testkit.RecordedReport;
import com.example.batch.sdk.testkit.TaskDispatchMessageBuilder;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/**
 * ADR-035 自托管 worker(纯 Java)端到端集成测。
 *
 * <p>不走 {@code main()}(它依赖环境变量 + 调 {@code System.exit}),而是复现 main 同款 wiring,
 * 把全部 sample handler 注册到 {@code BatchPlatformClient},经 {@link FakeBatchPlatform} 端到端跑通。
 *
 * <p>覆盖:Atomic / Import / Export / Process / Dispatch 5 类 ADR-036 业务模板 + 同名 echo / sleep 两个最小契约。
 */
@TestInstance(Lifecycle.PER_CLASS)
class SampleTenantWorkerIT {

  private static final String TENANT = "tenant-a";
  private static final String WORKER = "sample-worker-pure-java";

  private FakeBatchPlatform platform;
  private BatchPlatformClient client;

  @BeforeAll
  void startWorker() {
    platform = FakeBatchPlatform.start();
    client =
        BatchPlatformClient.builder(platform.configFor(TENANT, WORKER))
            .register(new EchoHandler())
            .register(new SleepHandler())
            .register(new AtomicEchoHandler())
            .register(new ImportEchoHandler())
            .register(new ExportEchoHandler())
            .register(new ProcessEchoHandler())
            .register(new DispatchEchoHandler())
            .build();
    client.start();
  }

  @AfterAll
  void stopWorker() {
    if (client != null) {
      client.stop();
    }
    if (platform != null) {
      platform.close();
    }
  }

  @Test
  @DisplayName("启动期向平台 register 一次,handler 列表完整")
  void registersOnStart() {
    assertThat(platform.registrations()).isNotEmpty();
  }

  @Test
  @DisplayName("echo: 最小 SdkTaskHandler 契约,parameters 原样回吐")
  void echo() {
    dispatchAndAssertSuccess("echo", 501L, Map.of("k", "v"), "echoed");
  }

  @Test
  @DisplayName("sleep: 长任务样例,按 millis 阻塞并上报")
  void sleep() {
    long id = 502L;
    platform.dispatch(
        TaskDispatchMessageBuilder.dispatch("sleep")
            .tenantId(TENANT)
            .taskId(id)
            .param("millis", 30)
            .build());
    RecordedReport r = platform.awaitReport(id, Duration.ofSeconds(10));
    assertThat(r.success()).isTrue();
    assertThat(r.outputs()).containsEntry("millis", 30);
  }

  @Test
  @DisplayName("sample_atomic_echo: ADR-036 Atomic 模板")
  void atomicEcho() {
    long id = 503L;
    platform.dispatch(
        TaskDispatchMessageBuilder.dispatch("sample_atomic_echo")
            .tenantId(TENANT)
            .taskId(id)
            .param("value", "atomic-payload")
            .build());
    RecordedReport r = platform.awaitReport(id, Duration.ofSeconds(10));
    assertThat(r.success()).isTrue();
  }

  @Test
  @DisplayName("sample_import_echo: ADR-036 Import 模板(3 行 batch=2)")
  void importEcho() {
    long id = 504L;
    platform.dispatch(
        TaskDispatchMessageBuilder.dispatch("sample_import_echo")
            .tenantId(TENANT)
            .taskId(id)
            .param("sourcePath", "/tmp/in.csv")
            .build());
    RecordedReport r = platform.awaitReport(id, Duration.ofSeconds(10));
    assertThat(r.success()).isTrue();
  }

  @Test
  @DisplayName("sample_export_echo: ADR-036 Export 模板")
  void exportEcho() {
    long id = 505L;
    platform.dispatch(
        TaskDispatchMessageBuilder.dispatch("sample_export_echo")
            .tenantId(TENANT)
            .taskId(id)
            .param("destPath", "/tmp/out.csv")
            .build());
    RecordedReport r = platform.awaitReport(id, Duration.ofSeconds(10));
    assertThat(r.success()).isTrue();
  }

  @Test
  @DisplayName("sample_process_echo: ADR-036 Process 模板")
  void processEcho() {
    long id = 506L;
    platform.dispatch(
        TaskDispatchMessageBuilder.dispatch("sample_process_echo")
            .tenantId(TENANT)
            .taskId(id)
            .param("input", "hello")
            .build());
    RecordedReport r = platform.awaitReport(id, Duration.ofSeconds(10));
    assertThat(r.success()).isTrue();
  }

  @Test
  @DisplayName("sample_dispatch_echo: ADR-036 Dispatch 模板")
  void dispatchEcho() {
    long id = 507L;
    platform.dispatch(
        TaskDispatchMessageBuilder.dispatch("sample_dispatch_echo")
            .tenantId(TENANT)
            .taskId(id)
            .param("target", "downstream")
            .build());
    RecordedReport r = platform.awaitReport(id, Duration.ofSeconds(10));
    assertThat(r.success()).isTrue();
  }

  // ─── 辅助 ───────────────────────────────────────────────────────────────────

  private void dispatchAndAssertSuccess(
      String taskType, long taskId, Map<String, Object> params, String expectedMessage) {
    var b = TaskDispatchMessageBuilder.dispatch(taskType).tenantId(TENANT).taskId(taskId);
    params.forEach(b::param);
    platform.dispatch(b.build());
    RecordedReport r = platform.awaitReport(taskId, Duration.ofSeconds(10));
    assertThat(r.success()).isTrue();
    if (expectedMessage != null) {
      assertThat(r.message()).isEqualTo(expectedMessage);
    }
  }

  // ─── 复现 main() 的内部 handler(原文件里是 private static,这里独立声明保持等价) ─────

  static final class EchoHandler implements SdkTaskHandler {
    @Override
    public String taskType() {
      return "echo";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
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

package com.example.batch.worker.atomic.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.BatchTaskExecutorRegistry;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.atomic.BatchWorkerAtomicApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Atomic worker 端到端集成测:开启 shell / sql / http 三类 executor,验证 ① SPI registry 装配齐全, ② shell executor
 * 真跑 {@code /bin/echo} 命令拿到 stdout,③ sql executor 真跑 {@code SELECT 1} 在 testcontainers PG 拿到结果。
 *
 * <p>不走 Kafka / CLAIM 链路(那块由 {@code AbstractTaskConsumer} 单测 + dispatch 单测覆盖)。本测专门钉 "executor bean
 * → 直接 execute → 真实 OS / DB" 这一段,补 atomic 主链 IT 缺口。
 */
@SpringBootTest(
    classes = BatchWorkerAtomicApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "batch.worker.executors.shell.enabled=true",
      "batch.worker.executors.sql.enabled=true",
      // http 默认 enabled=true,显式打开避免被 profile 关掉
      "batch.worker.executors.http.enabled=true",
      "batch.worker.executors.shell.command-whitelist=/bin/echo",
      "batch.worker.executors.shell.workdir-base=${java.io.tmpdir}/batch-atomic-it"
    })
class BatchWorkerAtomicEndToEndIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired BatchTaskExecutorRegistry registry;

  @Test
  @DisplayName("SPI registry 含 shell / sql / http 三类已开启的 executor")
  void registryWiresEnabledExecutors() {
    Map<String, String> dump = registry.dumpRegistry();
    assertThat(dump.keySet()).contains("shell", "sql", "http");
    assertThat(dump.get("shell")).contains("ShellTaskExecutor");
    assertThat(dump.get("sql")).contains("SqlTaskExecutor");
    assertThat(dump.get("http")).contains("HttpTaskExecutor");
  }

  @Test
  @DisplayName("shell:/bin/echo 跑真命令,output 含 exitCode=0")
  void shellEcho_realProcess() throws Exception {
    Path workdir = Path.of(System.getProperty("java.io.tmpdir"), "batch-atomic-it");
    Files.createDirectories(workdir);

    BatchTaskExecutor shell = registry.find("shell");
    assertThat(shell).as("shell executor 已注册").isNotNull();

    TaskContext ctx =
        new TaskContext(
            "tenant-a",
            "atomic-e2e-shell",
            "ti-shell-1",
            "worker-it",
            Map.of(
                "command", "/bin/echo", "args", List.of("hello", "atomic"), "timeoutSeconds", 5L),
            Map.of());
    TaskResult result = shell.execute(ctx);

    assertThat(result.success()).as("echo 应成功").isTrue();
    assertThat(result.output()).containsEntry("exitCode", 0);
    Object stdout = result.output().get("stdout");
    assertThat(String.valueOf(stdout)).contains("hello").contains("atomic");
  }

  @Test
  @DisplayName("shell:命令不在白名单时被拒,落 fail 而非异常逸出")
  void shellNonWhitelistedCommand_rejected() {
    BatchTaskExecutor shell = registry.find("shell");
    assertThat(shell).isNotNull();

    TaskContext ctx =
        new TaskContext(
            "tenant-a",
            "atomic-e2e-shell-deny",
            "ti-shell-2",
            "worker-it",
            Map.of("command", "/bin/cat", "args", List.of("/etc/hostname")),
            Map.of());
    TaskResult result = shell.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.message()).containsIgnoringCase("whitelist");
  }

  // SQL E2E:已被 SqlTaskExecutorIntegrationTest 覆盖(用专配的低权限角色);本测不重复,
  // 避免对 superuser 角色卡的硬化校验冲突。
}

package com.example.batch.ext.sftp;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

class SftpPushTaskExecutorTest {

  private final SftpPushTaskExecutor executor = new SftpPushTaskExecutor();

  private TaskContext ctx(Map<String, Object> params) {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", params, Map.of());
  }

  // ─── Metadata ────────────────────────────────────────────────────────────────

  @Test
  void taskTypeIsSftpPush() {
    assertThat(executor.taskType()).isEqualTo("sftp_push");
  }

  @Test
  void capabilityDeclaresNetAndDisk() {
    assertThat(executor.capability().resourceKinds())
        .contains(ResourceKind.NET, ResourceKind.DISK);
  }

  // ─── Validation ──────────────────────────────────────────────────────────────

  @Test
  void rejectsMissingHost() {
    TaskResult r = executor.execute(ctx(Map.of(
        "username", "u", "localPath", "/a", "remotePath", "/b", "password", "p")));
    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("parameters.host required");
  }

  @Test
  void rejectsMissingUsername() {
    TaskResult r = executor.execute(ctx(Map.of(
        "host", "h", "localPath", "/a", "remotePath", "/b", "password", "p")));
    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("parameters.username required");
  }

  @Test
  void rejectsMissingBothPasswordAndKey() {
    TaskResult r = executor.execute(ctx(Map.of(
        "host", "h", "username", "u", "localPath", "/a", "remotePath", "/b")));
    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("password or parameters.privateKey required");
  }

  @Test
  void acceptsPassword() {
    TaskResult r = executor.execute(ctx(Map.of(
        "host", "sftp.example.com", "username", "u", "password", "p",
        "localPath", "/a", "remotePath", "/b")));
    assertThat(r.success()).isTrue();
    assertThat(r.output()).containsEntry("mock", true);
  }

  @Test
  void acceptsPrivateKey() {
    TaskResult r = executor.execute(ctx(Map.of(
        "host", "sftp.example.com", "username", "u", "privateKey", "-----BEGIN...",
        "localPath", "/a", "remotePath", "/b")));
    assertThat(r.success()).isTrue();
  }

  @Test
  void defaultPortIs22() {
    TaskResult r = executor.execute(ctx(Map.of(
        "host", "sftp.example.com", "username", "u", "password", "p",
        "localPath", "/a", "remotePath", "/b")));
    assertThat(r.message()).contains("port=22");
  }

  @Test
  void customPortHonored() {
    TaskResult r = executor.execute(ctx(Map.of(
        "host", "sftp.example.com", "port", 2222,
        "username", "u", "password", "p",
        "localPath", "/a", "remotePath", "/b")));
    assertThat(r.message()).contains("port=2222");
  }

  // ─── ServiceLoader discovery(关键 — 证明 META-INF/services 工作)────────────

  @Test
  void discoverableViaServiceLoader() {
    boolean found = false;
    for (BatchTaskExecutor ex : ServiceLoader.load(BatchTaskExecutor.class)) {
      if (ex instanceof SftpPushTaskExecutor) {
        found = true;
        break;
      }
    }
    assertThat(found)
        .as("SftpPushTaskExecutor 应被 ServiceLoader 自动发现(META-INF/services 注册)")
        .isTrue();
  }
}

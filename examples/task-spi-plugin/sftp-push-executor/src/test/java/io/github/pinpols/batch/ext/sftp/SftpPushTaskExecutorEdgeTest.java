package io.github.pinpols.batch.ext.sftp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.spi.task.ResourceKind;
import io.github.pinpols.batch.common.spi.task.TaskCapability;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SftpPushTaskExecutor 边界用例补充(对照 {@link SftpPushTaskExecutorTest} 的 happy-path 覆盖)。
 *
 * <p>聚焦:空字符串 / 缺失关键参数 / capability 详细字段 / output map 字段 / 非数字端口降级。 实际 SFTP 行为是 stub,不在此测;真做要替换
 * {@code doSftpPush}。
 */
class SftpPushTaskExecutorEdgeTest {

  private final SftpPushTaskExecutor executor = new SftpPushTaskExecutor();

  private static TaskContext ctx(Map<String, Object> params) {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", params, Map.of());
  }

  private static Map<String, Object> base() {
    Map<String, Object> p = new HashMap<>();
    p.put("host", "sftp.example.com");
    p.put("username", "u");
    p.put("password", "p");
    p.put("localPath", "/a");
    p.put("remotePath", "/b");
    return p;
  }

  // ─── 空字符串(校验"非空 + trim")─────────────────────────────────────────────

  @Test
  @DisplayName("空字符串 host 被拒(非空校验)")
  void rejectsBlankHost() {
    Map<String, Object> p = base();
    p.put("host", "   ");
    TaskResult r = executor.execute(ctx(p));
    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("parameters.host required");
  }

  @Test
  @DisplayName("空字符串 username 被拒")
  void rejectsBlankUsername() {
    Map<String, Object> p = base();
    p.put("username", "");
    TaskResult r = executor.execute(ctx(p));
    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("parameters.username required");
  }

  @Test
  @DisplayName("缺 localPath 被拒")
  void rejectsMissingLocalPath() {
    Map<String, Object> p = base();
    p.remove("localPath");
    TaskResult r = executor.execute(ctx(p));
    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("parameters.localPath required");
  }

  @Test
  @DisplayName("缺 remotePath 被拒")
  void rejectsMissingRemotePath() {
    Map<String, Object> p = base();
    p.remove("remotePath");
    TaskResult r = executor.execute(ctx(p));
    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("parameters.remotePath required");
  }

  @Test
  @DisplayName("非字符串类型的 host 被拒(类型检查)")
  void rejectsNonStringHost() {
    Map<String, Object> p = base();
    p.put("host", 123);
    TaskResult r = executor.execute(ctx(p));
    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("parameters.host required");
  }

  // ─── 端口处理 ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("非数字 port 静默降级 22(stub 当前契约)")
  void nonNumericPortFallsBackTo22() {
    Map<String, Object> p = base();
    p.put("port", "not-a-number");
    TaskResult r = executor.execute(ctx(p));
    assertThat(r.success()).isTrue();
    assertThat(r.message()).contains("port=22");
  }

  @Test
  @DisplayName("Long 类型 port 被接受(Number.intValue 通路)")
  void longPortAccepted() {
    Map<String, Object> p = base();
    p.put("port", 2222L);
    TaskResult r = executor.execute(ctx(p));
    assertThat(r.success()).isTrue();
    assertThat(r.message()).contains("port=2222");
  }

  // ─── 双凭据并存:当前不互斥(stub 行为)─────────────────────────────────────

  @Test
  @DisplayName("password 与 privateKey 同时给:都接受(无互斥)")
  void bothPasswordAndPrivateKeyAccepted() {
    Map<String, Object> p = base();
    p.put("privateKey", "-----BEGIN KEY-----");
    TaskResult r = executor.execute(ctx(p));
    assertThat(r.success()).isTrue();
  }

  // ─── capability 详细断言 ─────────────────────────────────────────────────────

  @Test
  @DisplayName("capability:timeout=10min,非幂等,不可取消,只声明 NET+DISK")
  void capabilityDetailedFields() {
    TaskCapability cap = executor.capability();
    assertThat(cap.recommendedTimeout()).isEqualTo(Duration.ofMinutes(10));
    assertThat(cap.idempotent()).isFalse();
    assertThat(cap.cancellable()).isFalse();
    assertThat(cap.resourceKinds())
        .containsExactlyInAnyOrder(ResourceKind.NET, ResourceKind.DISK);
  }

  // ─── output map(stub 契约)──────────────────────────────────────────────────

  @Test
  @DisplayName("成功 output 含 bytesTransferred / durationMillis / remotePath / mock=true")
  void outputContainsAllStubFields() {
    Map<String, Object> p = base();
    p.put("remotePath", "/upload/file.csv");
    TaskResult r = executor.execute(ctx(p));
    assertThat(r.success()).isTrue();
    assertThat(r.output())
        .containsKeys("bytesTransferred", "durationMillis", "remotePath", "mock");
    assertThat(r.output()).containsEntry("remotePath", "/upload/file.csv");
    assertThat(r.output()).containsEntry("mock", true);
    assertThat(r.output().get("bytesTransferred")).isEqualTo(0L); // stub
    assertThat(r.output().get("durationMillis")).isInstanceOf(Long.class);
  }

  // ─── 直接调度边界:执行器对空参数 map 的反应 ────────────────────────────

  @Test
  @DisplayName("空 parameters 直接进 execute:返回 fail 而非异常逸出")
  void emptyParametersFailsGracefully() {
    TaskResult r = executor.execute(ctx(Map.of()));
    assertThat(r.success()).isFalse();
    // 第一个缺失字段:host
    assertThat(r.message()).contains("parameters.host required");
  }

  @Test
  @DisplayName("null parameters map:NPE 被 catch 转 fail,而不是从 execute 逸出")
  void nullParametersMapHandledGracefully() {
    TaskResult r = executor.execute(ctx(null));
    assertThat(r.success()).isFalse();
    // catch (RuntimeException) 分支:fail(ex) 把异常类型/message 揉进 message
    assertThat(r.message()).isNotBlank();
  }
}

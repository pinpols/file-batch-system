package io.github.pinpols.batch.ext.sftp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.spi.task.BatchTaskExecutor;
import io.github.pinpols.batch.common.spi.task.ResourceKind;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SFTP 推送任务执行器: 覆盖任务元数据声明、执行参数校验、端口解析与 SPI 自动发现")
class SftpPushTaskExecutorTest {

  private final SftpPushTaskExecutor executor = new SftpPushTaskExecutor();

  private TaskContext ctx(Map<String, Object> params) {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", params, Map.of());
  }

  // ─── Metadata ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("任务类型标识固定为 sftp_push, 供调度侧按类型路由到该推送执行器")
  void shouldReturnSftpPushType_whenTaskTypeQueried() {
    assertThat(executor.taskType()).isEqualTo("sftp_push");
  }

  @Test
  @DisplayName("能力声明同时占用网络与磁盘资源, 便于调度器按资源维度做准入与排队")
  void shouldDeclareNetAndDiskCapability_whenCapabilityQueried() {
    assertThat(executor.capability().resourceKinds())
        .contains(ResourceKind.NET, ResourceKind.DISK);
  }

  // ─── Validation ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("主机参数缺省时直接失败, 并在返回消息中要求补齐 host 后重试")
  void shouldFail_whenHostMissing() {
    TaskResult r = executor.execute(ctx(Map.of(
        "username", "u", "localPath", "/a", "remotePath", "/b", "password", "p")));
    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("parameters.host required");
  }

  @Test
  @DisplayName("用户名参数缺省时判定失败, 返回消息指明 username 为必填项")
  void shouldFail_whenUsernameMissing() {
    TaskResult r = executor.execute(ctx(Map.of(
        "host", "h", "localPath", "/a", "remotePath", "/b", "password", "p")));
    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("parameters.username required");
  }

  @Test
  @DisplayName("密码与私钥都未提供时校验失败, 消息要求二者至少提供一种凭据")
  void shouldFail_whenPasswordAndPrivateKeyBothMissing() {
    TaskResult r = executor.execute(ctx(Map.of(
        "host", "h", "username", "u", "localPath", "/a", "remotePath", "/b")));
    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("password or parameters.privateKey required");
  }

  @Test
  @DisplayName("仅凭密码即可通过参数校验, 输出标记为桩结果, 证明占位实现链路可用")
  void shouldSucceed_whenPasswordProvided() {
    TaskResult r = executor.execute(ctx(Map.of(
        "host", "sftp.example.com", "username", "u", "password", "p",
        "localPath", "/a", "remotePath", "/b")));
    assertThat(r.success()).isTrue();
    assertThat(r.output()).containsEntry("mock", true);
  }

  @Test
  @DisplayName("改用私钥作为凭据时参数校验同样通过, 覆盖免密码登录场景")
  void shouldSucceed_whenPrivateKeyProvided() {
    TaskResult r = executor.execute(ctx(Map.of(
        "host", "sftp.example.com", "username", "u", "privateKey", "-----BEGIN...",
        "localPath", "/a", "remotePath", "/b")));
    assertThat(r.success()).isTrue();
  }

  @Test
  @DisplayName("未指定端口时按默认 22 端口构造调用, 结果消息回显实际使用的端口")
  void shouldUsePort22_whenPortNotProvided() {
    TaskResult r = executor.execute(ctx(Map.of(
        "host", "sftp.example.com", "username", "u", "password", "p",
        "localPath", "/a", "remotePath", "/b")));
    assertThat(r.message()).contains("port=22");
  }

  @Test
  @DisplayName("显式指定 2222 端口时以配置值构造调用, 结果消息回显该端口")
  void shouldUseConfiguredPort_whenPortProvided() {
    TaskResult r = executor.execute(ctx(Map.of(
        "host", "sftp.example.com", "port", 2222,
        "username", "u", "password", "p",
        "localPath", "/a", "remotePath", "/b")));
    assertThat(r.message()).contains("port=2222");
  }

  // ─── ServiceLoader discovery(关键 — 证明 META-INF/services 工作)────────────

  @Test
  @DisplayName("通过服务加载机制能发现该执行器实现, 证明 SPI 注册文件生效可被 worker 装配")
  void shouldBeDiscoverable_whenLoadedViaServiceLoader() {
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

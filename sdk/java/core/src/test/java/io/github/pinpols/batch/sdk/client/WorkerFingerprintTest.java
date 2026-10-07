package io.github.pinpols.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WorkerFingerprint — 进程标识、主机信息与版本号采集")
class WorkerFingerprintTest {

  @Test
  @DisplayName("进程标识返回当前虚拟机进程号")
  void shouldReturnCurrentJvmPid_whenReadingProcessId() {
    assertThat(WorkerFingerprint.processId())
        .isEqualTo(Long.toString(ProcessHandle.current().pid()));
  }

  @Test
  @DisplayName("主机名与地址解析尽力而为,取值非空或为空皆合法且不抛异常")
  void shouldReturnNullOrNonBlank_whenResolvingHostInfo() {
    // 尽力而为:解析成功则非空白,失败则 null —— 二者皆合法,不得抛异常。
    String hostName = WorkerFingerprint.hostName();
    assertThat(hostName == null || !hostName.isBlank()).isTrue();
    String hostIp = WorkerFingerprint.hostIp();
    assertThat(hostIp == null || !hostIp.isBlank()).isTrue();
  }

  @Test
  @DisplayName("未从打包产物运行时版本号取空")
  void shouldReturnNullVersion_whenNotRunningFromJar() {
    // 测试 / IDE classpath 无 jar manifest Implementation-Version → null(打包运行才有值)。
    assertThat(WorkerFingerprint.sdkVersion()).isNull();
  }
}

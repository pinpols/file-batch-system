package io.github.pinpols.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkerFingerprintTest {

  @Test
  void processIdIsCurrentJvmPid() {
    assertThat(WorkerFingerprint.processId())
        .isEqualTo(Long.toString(ProcessHandle.current().pid()));
  }

  @Test
  void hostNameAndIpAreBestEffortNonBlankOrNull() {
    // 尽力而为:解析成功则非空白,失败则 null —— 二者皆合法,不得抛异常。
    String hostName = WorkerFingerprint.hostName();
    assertThat(hostName == null || !hostName.isBlank()).isTrue();
    String hostIp = WorkerFingerprint.hostIp();
    assertThat(hostIp == null || !hostIp.isBlank()).isTrue();
  }

  @Test
  void sdkVersionIsNullWhenRunningOutsidePackagedJar() {
    // 测试 / IDE classpath 无 jar manifest Implementation-Version → null(打包运行才有值)。
    assertThat(WorkerFingerprint.sdkVersion()).isNull();
  }
}

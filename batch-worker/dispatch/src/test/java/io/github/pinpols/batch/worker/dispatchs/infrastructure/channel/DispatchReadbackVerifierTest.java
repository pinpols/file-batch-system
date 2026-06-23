package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-041 Phase1.5:投递后回读校验纯函数辅助。 */
class DispatchReadbackVerifierTest {

  @Test
  @DisplayName("readback_verify_enabled=true 启用;false / 缺省 / null 不启用")
  void enabled_respectsFlag() {
    assertThat(DispatchReadbackVerifier.enabled(Map.of("readback_verify_enabled", true))).isTrue();
    assertThat(DispatchReadbackVerifier.enabled(Map.of("readback_verify_enabled", "true")))
        .isTrue();
    assertThat(DispatchReadbackVerifier.enabled(Map.of("readback_verify_enabled", false)))
        .isFalse();
    assertThat(DispatchReadbackVerifier.enabled(Map.of())).isFalse();
    assertThat(DispatchReadbackVerifier.enabled(null)).isFalse();
  }

  @Test
  @DisplayName("file_size_bytes 取期望大小(Number / String);缺省 / 非数字 → null")
  void expectedSizeBytes_resolution() {
    assertThat(DispatchReadbackVerifier.expectedSizeBytes(Map.of("file_size_bytes", 1024L)))
        .isEqualTo(1024L);
    assertThat(DispatchReadbackVerifier.expectedSizeBytes(Map.of("fileSizeBytes", "2048")))
        .isEqualTo(2048L);
    assertThat(DispatchReadbackVerifier.expectedSizeBytes(Map.of("other", 1))).isNull();
    assertThat(DispatchReadbackVerifier.expectedSizeBytes(Map.of("file_size_bytes", "x"))).isNull();
    assertThat(DispatchReadbackVerifier.expectedSizeBytes(null)).isNull();
  }

  @Test
  @DisplayName("file_size_bytes 为 null 值时安全返回 null(不抛)")
  void expectedSizeBytes_nullValue() {
    Map<String, Object> fileRecord = new HashMap<>();
    fileRecord.put("file_size_bytes", null);
    assertThat(DispatchReadbackVerifier.expectedSizeBytes(fileRecord)).isNull();
  }
}

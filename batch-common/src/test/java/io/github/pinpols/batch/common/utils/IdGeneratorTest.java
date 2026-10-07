package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("标识生成器: 追踪号, 调用号, 业务单号与幂等键的格式与唯一性")
class IdGeneratorTest {

  @Test
  @DisplayName("追踪号生成: 输出 32 位小写十六进制")
  void shouldReturn32HexChars_whenGeneratingTraceId() {
    String id = IdGenerator.newTraceId();
    assertThat(id).hasSize(32).matches("[0-9a-f]+");
  }

  @Test
  @DisplayName("调用号生成: 输出 32 位小写十六进制")
  void shouldReturn32HexChars_whenGeneratingInvocationId() {
    String id = IdGenerator.newInvocationId();
    assertThat(id).hasSize(32).matches("[0-9a-f]+");
  }

  @Test
  @DisplayName("业务单号生成: 以业务前缀开头, 至少含两个分隔符, 末段为 16 位十六进制")
  void shouldStartWithPrefixAndCarrySeparators_whenGeneratingBusinessNo() {
    String no = IdGenerator.newBusinessNo("JOB");
    assertThat(no).startsWith("JOB-");
    assertThat(no.chars().filter(c -> c == '-').count()).isGreaterThanOrEqualTo(2);
    assertThat(no.split("-")[2]).hasSize(16).matches("[0-9a-f]+");
  }

  @Test
  @DisplayName("业务单号生成: 中段时间戳为紧凑 UTC 形式, 不含冒号与连字符")
  void shouldEmbedCompactUtcTimestamp_whenGeneratingBusinessNo() {
    String no = IdGenerator.newBusinessNo("JOB");
    String middle = no.split("-")[1];
    assertThat(middle).matches("\\d{8}T\\d{6}Z");
  }

  @Test
  @DisplayName("批量业务单号: 同一批次共享时间戳, 各条后缀互不相同")
  void shouldShareTimestampAndDifferInSuffix_whenGeneratingBusinessNoBatch() {
    java.util.List<String> ids = IdGenerator.newBusinessNoBatch("PART", 5);
    assertThat(ids).hasSize(5);
    String ts0 = ids.get(0).split("-")[1];
    for (String id : ids) {
      assertThat(id).startsWith("PART-").contains(ts0);
    }
    // 后缀应彼此不同
    assertThat(ids.stream().map(s -> s.split("-")[2]).distinct().count()).isEqualTo(5);
  }

  @Test
  @DisplayName("批量业务单号: 数量非正时抛出非法参数异常")
  void shouldRejectNonPositiveCount_whenGeneratingBusinessNoBatch() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> IdGenerator.newBusinessNoBatch("X", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(">= 1");
  }

  @Test
  @DisplayName("幂等键生成: 以固定前缀开头, 后续为 32 位小写十六进制")
  void shouldCarryPrefixAndHexSuffix_whenGeneratingIdempotencyKey() {
    String k = IdGenerator.newIdempotencyKey();
    assertThat(k).startsWith("idem-");
    assertThat(k.substring(5)).hasSize(32).matches("[0-9a-f]+");
  }

  @Test
  @DisplayName("幂等键生成: 连续生成一千个互不重复")
  void shouldBeUniqueAcrossManyRounds_whenGeneratingIdempotencyKey() {
    java.util.Set<String> keys = new java.util.HashSet<>();
    for (int i = 0; i < 1000; i++) {
      keys.add(IdGenerator.newIdempotencyKey());
    }
    assertThat(keys).hasSize(1000);
  }
}

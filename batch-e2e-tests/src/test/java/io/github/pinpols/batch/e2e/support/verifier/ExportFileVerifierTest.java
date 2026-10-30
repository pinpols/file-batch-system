package io.github.pinpols.batch.e2e.support.verifier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("导出产物内容校验失败路径")
class ExportFileVerifierTest {
  @Test
  @DisplayName("缺少本次作业的产物记录时校验失败")
  void shouldFail_whenOutputFileRecordIsMissing() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq("t1"), eq("job"))).thenReturn(List.of());

    ExportFileVerifier verifier =
        ExportFileVerifier.forTenant("t1").dedupKey("job").platformJdbc(jdbc).build();

    assertThatThrownBy(verifier::verify).isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("对象存储读取失败时校验失败")
  void shouldFail_whenRequestedContentCannotBeRead() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq("t1"), eq("job")))
        .thenReturn(List.of(Map.of("storage_path", "missing-export.json")));

    ExportFileVerifier verifier = ExportFileVerifier.forTenant("t1")
        .dedupKey("job")
        .platformJdbc(jdbc)
        .s3Bucket("test-bucket")
        .expectedContentSnippets("expected")
        .build();

    assertThatThrownBy(verifier::verify)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("failed to read output object");
  }
}

package io.github.pinpols.batch.orchestrator.infrastructure.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

@DisplayName("JDBC 持久化冲突探测适配器: 识别唯一约束冲突")
class JdbcPersistenceConflictDetectionAdapterTest {

  private final JdbcPersistenceConflictDetectionAdapter adapter =
      new JdbcPersistenceConflictDetectionAdapter();

  @Test
  @DisplayName("唯一冲突同时支持 SQLState 与约束名回退识别")
  void shouldRecognizeUniqueConstraintViolation() {
    assertThat(adapter.isUniqueConstraintViolation(
            new RuntimeException(new SQLException("duplicate", "23505")),
            "uk_job_instance_tenant_dedup"))
        .isTrue();
    assertThat(adapter.isUniqueConstraintViolation(
            new RuntimeException(
                new SQLException("violates uk_job_instance_tenant_dedup", (String) null)),
            "uk_job_instance_tenant_dedup"))
        .isTrue();
    assertThat(adapter.isUniqueConstraintViolation(
            new DataIntegrityViolationException("other constraint"),
            "uk_job_instance_tenant_dedup"))
        .isFalse();
  }
}

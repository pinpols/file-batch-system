package io.github.pinpols.batch.console.domain.job.application.contract.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("作业定义更新的优先级约束")
class JobDefinitionUpdateRequestValidationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @DisplayName("优先级遵守数据库范围且允许省略")
  @Test
  void shouldConstrainPriorityToDatabaseRangeAndAllowOmission() {
    assertThat(validator.validateValue(JobDefinitionUpdateRequest.class, "priority", null))
        .isEmpty();
    assertThat(validator.validateValue(JobDefinitionUpdateRequest.class, "priority", 1))
        .isEmpty();
    assertThat(validator.validateValue(JobDefinitionUpdateRequest.class, "priority", 9))
        .isEmpty();
    assertThat(validator.validateValue(JobDefinitionUpdateRequest.class, "priority", 0))
        .isNotEmpty();
    assertThat(validator.validateValue(JobDefinitionUpdateRequest.class, "priority", 10))
        .isNotEmpty();
  }
}

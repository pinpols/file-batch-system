package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncExportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncPreviewRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigMatrixRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigPreviewRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("配置导入、复制、预览与矩阵的集合元素身份校验")
class ConfigSyncTargetIdentityValidationTest {

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  @DisplayName("目标列表中的空元素、空串或空白须在业务入口校验失败")
  void shouldRejectMissingTargetTenantIdentity(String invalidTarget) {
    ConfigSyncImportRequest sync = new ConfigSyncImportRequest();
    sync.setTenantId("audit");
    sync.setSourceEnv("dev");
    sync.setTargetEnv("prod");
    sync.setTargetTenantIds(Collections.singletonList(invalidTarget));
    TenantConfigBatchInitRequest batch = new TenantConfigBatchInitRequest();
    batch.setTargetTenantIds(Collections.singletonList(invalidTarget));
    TenantConfigCopyRequest copy = new TenantConfigCopyRequest();
    copy.setSourceTenantId("source");
    copy.setTargetTenantIds(Collections.singletonList(invalidTarget));
    TenantConfigPreviewRequest preview = new TenantConfigPreviewRequest();
    preview.setSourceTenantId("source");
    preview.setTargetTenantIds(Collections.singletonList(invalidTarget));
    TenantConfigMatrixRequest matrix = new TenantConfigMatrixRequest();
    matrix.setTenantIds(Collections.singletonList(invalidTarget));
    matrix.setJobCodes(List.of("JOB_A"));

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      Validator validator = factory.getValidator();
      Set<ConstraintViolation<ConfigSyncImportRequest>> syncViolations = validator.validate(sync);
      Set<ConstraintViolation<TenantConfigBatchInitRequest>> batchViolations =
          validator.validate(batch);
      assertThat(syncViolations)
          .extracting(violation -> violation.getPropertyPath().toString())
          .anyMatch(path -> path.startsWith("targetTenantIds"));
      assertThat(batchViolations)
          .extracting(violation -> violation.getPropertyPath().toString())
          .anyMatch(path -> path.startsWith("targetTenantIds"));
      Set<ConstraintViolation<TenantConfigCopyRequest>> copyViolations = validator.validate(copy);
      Set<ConstraintViolation<TenantConfigPreviewRequest>> previewViolations =
          validator.validate(preview);
      Set<ConstraintViolation<TenantConfigMatrixRequest>> matrixViolations =
          validator.validate(matrix);
      assertThat(copyViolations)
          .extracting(violation -> violation.getPropertyPath().toString())
          .anyMatch(path -> path.startsWith("targetTenantIds"));
      assertThat(previewViolations)
          .extracting(violation -> violation.getPropertyPath().toString())
          .anyMatch(path -> path.startsWith("targetTenantIds"));
      assertThat(matrixViolations)
          .extracting(violation -> violation.getPropertyPath().toString())
          .anyMatch(path -> path.startsWith("tenantIds"));
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  @DisplayName("作业选择列表不接受缺失的作业身份")
  void shouldRejectMissingSelectedJobIdentity(String invalidJob) {
    TenantConfigCopyRequest copy = new TenantConfigCopyRequest();
    copy.setSourceTenantId("source");
    copy.setTargetTenantIds(List.of("target"));
    copy.setJobCodes(Collections.singletonList(invalidJob));
    TenantConfigPreviewRequest preview = new TenantConfigPreviewRequest();
    preview.setSourceTenantId("source");
    preview.setTargetTenantIds(List.of("target"));
    preview.setJobCodes(Collections.singletonList(invalidJob));
    TenantConfigMatrixRequest matrix = new TenantConfigMatrixRequest();
    matrix.setTenantIds(List.of("target"));
    matrix.setJobCodes(Collections.singletonList(invalidJob));
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      Validator validator = factory.getValidator();
      for (Object request : List.of(copy, preview, matrix)) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        assertThat(violations)
            .extracting(violation -> violation.getPropertyPath().toString())
            .anyMatch(path -> path.startsWith("jobCodes"));
      }
    }
  }

  @Test
  @DisplayName("可选配置类型集合不接受null枚举项")
  void shouldRejectNullConfigurationType() {
    TenantConfigCopyRequest copy = new TenantConfigCopyRequest();
    copy.setSourceTenantId("source");
    copy.setTargetTenantIds(List.of("target"));
    copy.setConfigTypes(Collections.singleton(null));
    TenantConfigPreviewRequest preview = new TenantConfigPreviewRequest();
    preview.setSourceTenantId("source");
    preview.setTargetTenantIds(List.of("target"));
    preview.setConfigTypes(Collections.singleton(null));
    ConfigSyncExportRequest export = new ConfigSyncExportRequest();
    export.setSourceTenantId("source");
    export.setSourceEnv("dev");
    export.setTargetEnv("prod");
    export.setConfigTypes(Collections.singleton(null));
    ConfigSyncPreviewRequest syncPreview = new ConfigSyncPreviewRequest();
    syncPreview.setSourceTenantId("source");
    syncPreview.setTenantId("target");
    syncPreview.setSourceEnv("dev");
    syncPreview.setTargetEnv("prod");
    syncPreview.setConfigTypes(Collections.singleton(null));
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      Validator validator = factory.getValidator();
      for (Object request : List.of(copy, preview, export, syncPreview)) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        assertThat(violations)
            .extracting(violation -> violation.getPropertyPath().toString())
            .anyMatch(path -> path.startsWith("configTypes"));
      }
    }
  }

  @Test
  @DisplayName("省略或清空可选作业/类型集合仍表示全部配置，不被新元素约束拒绝")
  void shouldPreserveOptionalWholeBundleSelection() {
    TenantConfigCopyRequest copy = new TenantConfigCopyRequest();
    copy.setSourceTenantId("source");
    copy.setTargetTenantIds(List.of("target"));
    TenantConfigPreviewRequest preview = new TenantConfigPreviewRequest();
    preview.setSourceTenantId("source");
    preview.setTargetTenantIds(List.of("target"));
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      Validator validator = factory.getValidator();
      Set<ConstraintViolation<TenantConfigCopyRequest>> copyOmitted = validator.validate(copy);
      Set<ConstraintViolation<TenantConfigPreviewRequest>> previewOmitted =
          validator.validate(preview);
      assertThat(copyOmitted).isEmpty();
      assertThat(previewOmitted).isEmpty();
      copy.setJobCodes(List.of());
      copy.setConfigTypes(Set.of());
      preview.setJobCodes(List.of());
      preview.setConfigTypes(Set.of());
      Set<ConstraintViolation<TenantConfigCopyRequest>> copyEmpty = validator.validate(copy);
      Set<ConstraintViolation<TenantConfigPreviewRequest>> previewEmpty =
          validator.validate(preview);
      assertThat(copyEmpty).isEmpty();
      assertThat(previewEmpty).isEmpty();
    }
  }
}

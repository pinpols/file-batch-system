package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobBundleCreateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobBundleImportRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("配置同步与作业包的配置条目级联校验")
class ConfigBundleItemValidationTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private static Stream<Arguments> nullItemCases() {
    return Stream.of(
            "jobDefinitions",
            "workflowDefinitions",
            "fileChannels",
            "fileTemplates",
            "resourceQueues",
            "batchWindows",
            "businessCalendars",
            "quotaPolicies",
            "alertRoutings")
        .flatMap(field -> Stream.of("sync", "jobImport", "jobCreate")
            .map(envelope -> Arguments.of(envelope, field)));
  }

  @ParameterizedTest
  @MethodSource("nullItemCases")
  @DisplayName("三种配置包请求均级联拒绝九类列表中的空条目")
  void shouldRejectNullBundleItemThroughEveryEnvelope(String envelope, String field) {
    Map<String, Object> fields = Collections.singletonMap(field, Collections.singletonList(null));
    ConfigSyncBundlePayload bundle = mapper.convertValue(fields, ConfigSyncBundlePayload.class);
    Object request = request(envelope, bundle);

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      Set<ConstraintViolation<Object>> violations = factory.getValidator().validate(request);

      assertThat(violations)
          .extracting(violation -> violation.getPropertyPath().toString())
          .anyMatch(path -> path.startsWith("bundle." + field + "[0]"));
    }
  }

  private static Stream<Arguments> emptyBundleCases() {
    return Stream.of("sync", "jobImport", "jobCreate")
        .flatMap(envelope -> Stream.of(false, true).map(empty -> Arguments.of(envelope, empty)));
  }

  @ParameterizedTest
  @MethodSource("emptyBundleCases")
  @DisplayName("省略或清空可选配置列表不被条目约束拒绝")
  void shouldAllowOmittedAndEmptyBundleLists(String envelope, boolean empty) {
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    if (empty) {
      bundle.setJobDefinitions(List.of());
      bundle.setWorkflowDefinitions(List.of());
      bundle.setFileChannels(List.of());
      bundle.setFileTemplates(List.of());
      bundle.setResourceQueues(List.of());
      bundle.setBatchWindows(List.of());
      bundle.setBusinessCalendars(List.of());
      bundle.setQuotaPolicies(List.of());
      bundle.setAlertRoutings(List.of());
    }

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(request(envelope, bundle))).isEmpty();
    }
  }

  private static Object request(String envelope, ConfigSyncBundlePayload bundle) {
    return switch (envelope) {
      case "sync" -> {
        ConfigSyncImportRequest request = new ConfigSyncImportRequest();
        request.setTenantId("tenant-a");
        request.setSourceEnv("dev");
        request.setTargetEnv("prod");
        request.setTargetTenantIds(List.of("tenant-b"));
        request.setBundle(bundle);
        yield request;
      }
      case "jobImport" -> {
        JobBundleImportRequest request = new JobBundleImportRequest();
        request.setTenantId("tenant-a");
        request.setTargetTenantIds(List.of("tenant-b"));
        request.setBundle(bundle);
        yield request;
      }
      case "jobCreate" -> {
        JobBundleCreateRequest request = new JobBundleCreateRequest();
        request.setTenantId("tenant-a");
        request.setBundle(bundle);
        yield request;
      }
      default -> throw new IllegalArgumentException(envelope);
    };
  }
}

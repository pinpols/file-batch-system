package com.example.batch.common.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.JobType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ContentVerifierRegistryTest {

  @Test
  void verifiersForFiltersByJobTypeAndStage() {
    ContentVerifier exportAlways =
        new StubVerifier("EXPORT_NON_EMPTY", Set.of(JobType.EXPORT), null, VerifyResult.pass());
    ContentVerifier exportFetchOnly =
        new StubVerifier(
            "EXPORT_FETCH_ROW", Set.of(JobType.EXPORT), "EXPORT_FETCH", VerifyResult.pass());
    ContentVerifier importOnly =
        new StubVerifier("IMPORT_SCHEMA", Set.of(JobType.IMPORT), null, VerifyResult.pass());

    ContentVerifierRegistry registry =
        new ContentVerifierRegistry(
            providerOf(exportAlways, exportFetchOnly, importOnly), providerOf(/* no meter */ ));

    assertThat(
            registry.verifiersFor(JobType.EXPORT, "EXPORT_FETCH").stream()
                .map(ContentVerifier::code))
        .containsExactlyInAnyOrder("EXPORT_NON_EMPTY", "EXPORT_FETCH_ROW");
    assertThat(
            registry.verifiersFor(JobType.EXPORT, "EXPORT_FINALIZE").stream()
                .map(ContentVerifier::code))
        .containsExactly("EXPORT_NON_EMPTY");
    assertThat(registry.verifiersFor(JobType.IMPORT, null).stream().map(ContentVerifier::code))
        .containsExactly("IMPORT_SCHEMA");
    assertThat(registry.verifiersFor(JobType.DISPATCH, "ANY").stream()).isEmpty();
  }

  @Test
  void runRecordsTimerAndFailureCounter() {
    StubVerifier failing =
        new StubVerifier(
            "EXPORT_NON_EMPTY",
            Set.of(JobType.EXPORT),
            null,
            VerifyResult.fail("EXPORT_FILE_EMPTY", "no rows"));
    SimpleMeterRegistry meter = new SimpleMeterRegistry();
    ContentVerifierRegistry registry =
        new ContentVerifierRegistry(providerOf(failing), providerOf(meter));

    VerifyResult result = registry.run(failing, sampleContext());

    assertThat(result.passed()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_FILE_EMPTY");
    assertThat(
            meter
                .find("batch.verifier.duration")
                .tag("code", "EXPORT_NON_EMPTY")
                .tag("outcome", "fail")
                .timer())
        .isNotNull();
    assertThat(
            meter
                .find("batch.verifier.failures")
                .tag("code", "EXPORT_NON_EMPTY")
                .tag("reason", "EXPORT_FILE_EMPTY")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void runSwallowsExceptionAndRecordsErrorOutcome() {
    ContentVerifier throwing =
        new ContentVerifier() {
          @Override
          public String code() {
            return "BAD";
          }

          @Override
          public Set<JobType> appliesTo() {
            return Set.of(JobType.EXPORT);
          }

          @Override
          public VerifyResult verify(VerifyContext context) {
            throw new IllegalStateException("boom");
          }
        };
    SimpleMeterRegistry meter = new SimpleMeterRegistry();
    ContentVerifierRegistry registry =
        new ContentVerifierRegistry(providerOf(throwing), providerOf(meter));

    VerifyResult result = registry.run(throwing, sampleContext());

    assertThat(result.passed()).isFalse();
    assertThat(result.code()).isEqualTo("BAD_EXCEPTION");
    assertThat(meter.find("batch.verifier.duration").tag("outcome", "error").timer().count())
        .isEqualTo(1L);
  }

  @Test
  void runWithoutMeterRegistryIsSafe() {
    ContentVerifier verifier =
        new StubVerifier("OK", Set.of(JobType.IMPORT), null, VerifyResult.pass());
    ContentVerifierRegistry registry =
        new ContentVerifierRegistry(providerOf(verifier), providerOf(/* no meter */ ));

    VerifyResult result = registry.run(verifier, sampleContext());

    assertThat(result.passed()).isTrue();
  }

  private static VerifyContext sampleContext() {
    return VerifyContext.builder()
        .tenantId("t1")
        .jobType(JobType.EXPORT)
        .jobInstanceId(100L)
        .taskId(200L)
        .stageCode("EXPORT_FETCH")
        .payload(Map.of("fileId", 999L))
        .build();
  }

  @SafeVarargs
  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> providerOf(T... beans) {
    ObjectProvider<T> provider = (ObjectProvider<T>) mock(ObjectProvider.class);
    when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(beans));
    when(provider.getIfAvailable()).thenReturn(beans.length == 0 ? null : beans[0]);
    return provider;
  }

  private record StubVerifier(
      String code, Set<JobType> appliesTo, String stageCode, VerifyResult resultToReturn)
      implements ContentVerifier {
    @Override
    public VerifyResult verify(VerifyContext context) {
      return resultToReturn;
    }
  }
}

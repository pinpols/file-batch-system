package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.verifier.ContentVerifier;
import io.github.pinpols.batch.common.verifier.ContentVerifierRegistry;
import io.github.pinpols.batch.common.verifier.VerifyContext;
import io.github.pinpols.batch.common.verifier.VerifyResult;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class PipelineVerifierHookTest {

  @Test
  void writesFailuresIntoAttributes() {
    StubVerifier failing =
        new StubVerifier(
            "EXPORT_NON_EMPTY",
            Set.of(JobType.EXPORT),
            VerifyResult.fail("EXPORT_FILE_EMPTY", "empty"));
    ContentVerifierRegistry registry = registryWith(failing);
    PipelineVerifierHook hook = new PipelineVerifierHook(providerOf(registry));
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("recordCount", 0);

    hook.runVerifiers("t1", "EXPORT", 1L, 2L, "EXPORT_FINALIZE", attributes);

    Object failures = attributes.get(PipelineRuntimeKeys.VERIFIER_FAILURES);
    assertThat(failures).isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list = (List<Map<String, Object>>) failures;
    assertThat(list).hasSize(1);
    assertThat(list.get(0)).containsEntry("code", "EXPORT_FILE_EMPTY");
  }

  @Test
  void doesNotWriteWhenAllPass() {
    StubVerifier passing =
        new StubVerifier("EXPORT_NON_EMPTY", Set.of(JobType.EXPORT), VerifyResult.pass());
    ContentVerifierRegistry registry = registryWith(passing);
    PipelineVerifierHook hook = new PipelineVerifierHook(providerOf(registry));
    Map<String, Object> attributes = new HashMap<>();

    hook.runVerifiers("t1", "EXPORT", 1L, 2L, "EXPORT_FINALIZE", attributes);

    assertThat(attributes).doesNotContainKey(PipelineRuntimeKeys.VERIFIER_FAILURES);
  }

  @Test
  void skipsWhenRegistryAbsent() {
    PipelineVerifierHook hook = new PipelineVerifierHook(providerOf());
    Map<String, Object> attributes = new HashMap<>();

    hook.runVerifiers("t1", "EXPORT", 1L, 2L, "EXPORT_FINALIZE", attributes);

    assertThat(attributes).isEmpty();
  }

  @Test
  void skipsForUnknownPipelineType() {
    ContentVerifierRegistry registry =
        registryWith(new StubVerifier("ANY", Set.of(JobType.EXPORT), VerifyResult.pass()));
    PipelineVerifierHook hook = new PipelineVerifierHook(providerOf(registry));
    Map<String, Object> attributes = new HashMap<>();

    hook.runVerifiers("t1", "BOGUS_TYPE", 1L, 2L, "any", attributes);

    assertThat(attributes).isEmpty();
  }

  @Test
  void nullAttributesIsSafe() {
    PipelineVerifierHook hook = new PipelineVerifierHook(providerOf());
    hook.runVerifiers("t1", "EXPORT", 1L, 2L, "EXPORT_FINALIZE", null);
    // 仅断言不抛异常
  }

  @Test
  void returnsFatalFailureWhenFatalVerifierFails() {
    FatalStubVerifier failing =
        new FatalStubVerifier(
            "DISPATCH_RECEIPT_PRESENT",
            Set.of(JobType.DISPATCH),
            VerifyResult.fail("DISPATCH_RECEIPT_MISSING", "no receipt"));
    ContentVerifierRegistry registry = registryWith(failing);
    PipelineVerifierHook hook = new PipelineVerifierHook(providerOf(registry));
    Map<String, Object> attributes = new HashMap<>();

    PipelineVerifierHook.VerifierHookResult result =
        hook.runVerifiers("t1", "DISPATCH", 1L, 2L, "DISPATCH_COMPLETE", attributes);

    assertThat(result.fatalFailure()).isTrue();
    assertThat(result.firstFatalCode()).isEqualTo("DISPATCH_RECEIPT_MISSING");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> failures =
        (List<Map<String, Object>>) attributes.get(PipelineRuntimeKeys.VERIFIER_FAILURES);
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0)).containsEntry("fatal", true);
  }

  @Test
  void softFailureDoesNotMarkFatal() {
    StubVerifier failing =
        new StubVerifier(
            "EXPORT_NON_EMPTY",
            Set.of(JobType.EXPORT),
            VerifyResult.fail("EXPORT_FILE_EMPTY", "empty"));
    ContentVerifierRegistry registry = registryWith(failing);
    PipelineVerifierHook hook = new PipelineVerifierHook(providerOf(registry));
    Map<String, Object> attributes = new HashMap<>();

    PipelineVerifierHook.VerifierHookResult result =
        hook.runVerifiers("t1", "EXPORT", 1L, 2L, "EXPORT_FINALIZE", attributes);

    assertThat(result.fatalFailure()).isFalse();
    assertThat(result.firstFatalCode()).isNull();
  }

  private static ContentVerifierRegistry registryWith(ContentVerifier... beans) {
    ContentVerifierRegistry registry = mock(ContentVerifierRegistry.class);
    when(registry.verifiersFor(any(JobType.class), any())).thenAnswer(invocation -> List.of(beans));
    when(registry.run(any(ContentVerifier.class), any(VerifyContext.class)))
        .thenAnswer(
            invocation -> {
              ContentVerifier v = invocation.getArgument(0);
              if (v instanceof StubVerifier stub) {
                return stub.resultToReturn();
              }
              if (v instanceof FatalStubVerifier fatal) {
                return fatal.resultToReturn();
              }
              throw new IllegalStateException("Unknown stub type: " + v.getClass());
            });
    return registry;
  }

  @SafeVarargs
  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> providerOf(T... beans) {
    ObjectProvider<T> provider = (ObjectProvider<T>) mock(ObjectProvider.class);
    when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(beans));
    when(provider.getIfAvailable()).thenReturn(beans.length == 0 ? null : beans[0]);
    return provider;
  }

  private record StubVerifier(String code, Set<JobType> appliesTo, VerifyResult resultToReturn)
      implements ContentVerifier {
    @Override
    public VerifyResult verify(VerifyContext context) {
      return resultToReturn;
    }
  }

  private record FatalStubVerifier(String code, Set<JobType> appliesTo, VerifyResult resultToReturn)
      implements ContentVerifier {
    @Override
    public VerifyResult verify(VerifyContext context) {
      return resultToReturn;
    }

    @Override
    public boolean fatal() {
      return true;
    }
  }
}

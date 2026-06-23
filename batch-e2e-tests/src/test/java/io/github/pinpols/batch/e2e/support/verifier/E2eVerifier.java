package io.github.pinpols.batch.e2e.support.verifier;

/**
 * Pluggable content-level assertion strategy for E2E tests.
 *
 * <p>Implementations encapsulate the "状态 + 产物 + 审计" triple-check for a specific pipeline type
 * (export, dispatch, etc.). Tests build a verifier, run the pipeline, then call {@link #verify()}
 * once the task reaches SUCCESS.
 *
 * <p>Each implementation follows a builder pattern so that tests can configure only the assertions
 * they care about — unused assertions are silently skipped.
 */
@FunctionalInterface
public interface E2eVerifier {

  /** Executes all configured assertions. Throws {@link AssertionError} on failure. */
  void verify();
}

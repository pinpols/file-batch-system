package io.github.pinpols.batch.e2e.support.verifier;

/**
 * 供 E2E 测试使用的可插拔内容断言策略。
 *
 * <p>Implementations encapsulate the "状态 + 产物 + 审计" triple-check for a specific pipeline type
 * (export, dispatch, etc.). Tests build a verifier, run the pipeline, then call {@link #verify()}
 * 任务进入 SUCCESS 状态后。
 *
 * <p>Each implementation follows a builder pattern so that tests can configure only the assertions
 * 它们关注的内容；未使用的断言会被静默跳过。
 */
@FunctionalInterface
public interface E2eVerifier {

  /** Executes all configured assertions. Throws {@link AssertionError} on failure. */
  void verify();
}

package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("强制主库切面: 提示的进入,嵌套保留与异常还原")
class RouteToPrimaryAspectTest {

  @AfterEach
  void tearDown() {
    RoutingHints.restore(null);
  }

  @Test
  @DisplayName("切面在调用期间开启强制主库提示,调用结束后恢复原状")
  void shouldSetHintDuringInvocation_andRestoreAfterReturning() throws Throwable {
    RouteToPrimaryAspect aspect = new RouteToPrimaryAspect();
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    boolean[] insideHint = new boolean[1];
    when(pjp.proceed()).thenAnswer(inv -> {
      insideHint[0] = RoutingHints.isForcePrimary();
      return "ok";
    });

    Object result = aspect.wrap(pjp);

    assertThat(result).isEqualTo("ok");
    assertThat(insideHint[0]).isTrue();
    assertThat(RoutingHints.isForcePrimary()).isFalse();
  }

  @Test
  @DisplayName("嵌套调用返回后,外层已开启的强制主库提示仍然保留")
  void shouldRestorePriorHint_whenInvocationIsNested() throws Throwable {
    RouteToPrimaryAspect aspect = new RouteToPrimaryAspect();
    Boolean prev = RoutingHints.enterForcePrimary();
    try {
      ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
      when(pjp.proceed()).thenReturn("inner");

      aspect.wrap(pjp);

      // After inner returns, outer hint should still be set
      assertThat(RoutingHints.isForcePrimary()).isTrue();
    } finally {
      RoutingHints.restore(prev);
    }
    assertThat(RoutingHints.isForcePrimary()).isFalse();
  }

  @Test
  @DisplayName("被通知方法抛出异常时,强制主库提示同样被还原")
  void shouldRestoreHint_whenInvocationThrows() throws Throwable {
    RouteToPrimaryAspect aspect = new RouteToPrimaryAspect();
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.proceed()).thenThrow(new RuntimeException("boom"));

    try {
      aspect.wrap(pjp);
    } catch (RuntimeException ignored) {
      // 符合预期
    }

    assertThat(RoutingHints.isForcePrimary()).isFalse();
  }
}

package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RouteToPrimaryAspectTest {

  @AfterEach
  void tearDown() {
    RoutingHints.restore(null);
  }

  @Test
  void aspectSetsForcePrimaryDuringInvocationAndRestoresAfter() throws Throwable {
    RouteToPrimaryAspect aspect = new RouteToPrimaryAspect();
    ProceedingJoinPoint pjp = Mockito.mock(ProceedingJoinPoint.class);
    boolean[] insideHint = new boolean[1];
    Mockito.when(pjp.proceed())
        .thenAnswer(
            inv -> {
              insideHint[0] = RoutingHints.isForcePrimary();
              return "ok";
            });

    Object result = aspect.wrap(pjp);

    assertThat(result).isEqualTo("ok");
    assertThat(insideHint[0]).isTrue();
    assertThat(RoutingHints.isForcePrimary()).isFalse();
  }

  @Test
  void aspectRestoresPriorHintOnNestedCall() throws Throwable {
    RouteToPrimaryAspect aspect = new RouteToPrimaryAspect();
    Boolean prev = RoutingHints.enterForcePrimary();
    try {
      ProceedingJoinPoint pjp = Mockito.mock(ProceedingJoinPoint.class);
      Mockito.when(pjp.proceed()).thenReturn("inner");

      aspect.wrap(pjp);

      // After inner returns, outer hint should still be set
      assertThat(RoutingHints.isForcePrimary()).isTrue();
    } finally {
      RoutingHints.restore(prev);
    }
    assertThat(RoutingHints.isForcePrimary()).isFalse();
  }

  @Test
  void aspectStillRestoresHintIfMethodThrows() throws Throwable {
    RouteToPrimaryAspect aspect = new RouteToPrimaryAspect();
    ProceedingJoinPoint pjp = Mockito.mock(ProceedingJoinPoint.class);
    Mockito.when(pjp.proceed()).thenThrow(new RuntimeException("boom"));

    try {
      aspect.wrap(pjp);
    } catch (RuntimeException ignored) {
      // 符合预期
    }

    assertThat(RoutingHints.isForcePrimary()).isFalse();
  }
}

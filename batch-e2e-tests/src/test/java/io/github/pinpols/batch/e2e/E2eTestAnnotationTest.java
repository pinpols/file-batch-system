package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.e2e.apps.E2eOrchestratorApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.support.AnnotationSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.test.context.ActiveProfiles;

@DisplayName("E2E 组合注解契约")
class E2eTestAnnotationTest {

  @Test
  @DisplayName("组合注解保留标签、profile 与 Spring Boot 上下文配置")
  void shouldExposeSettings_whenUsingComposedAnnotation() {
    assertThat(AnnotationSupport.isAnnotated(E2eFixture.class, Tag.class)).isTrue();

    ActiveProfiles activeProfiles =
        MergedAnnotations.from(E2eFixture.class).get(ActiveProfiles.class).synthesize();
    assertThat(activeProfiles).isNotNull();
    assertThat(activeProfiles.value()).containsExactly("test", "e2e");

    SpringBootTest springBootTest =
        MergedAnnotations.from(E2eFixture.class).get(SpringBootTest.class).synthesize();
    assertThat(springBootTest).isNotNull();
    assertThat(springBootTest.classes()).containsExactly(E2eOrchestratorApplication.class);
    assertThat(springBootTest.webEnvironment()).isEqualTo(SpringBootTest.WebEnvironment.NONE);
    assertThat(springBootTest.properties()).containsExactly("e2e.fixture=enabled");
  }

  @E2eSpringBootTest(
      classes = E2eOrchestratorApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.NONE,
      properties = "e2e.fixture=enabled")
  private static class E2eFixture {}
}

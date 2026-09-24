package io.github.pinpols.batch.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ServletRequestPathsTest {

  @Test
  void prefersServletMappedPath() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/context/internal/tasks/1");
    request.setContextPath("/context");
    request.setServletPath("/internal/tasks/1");

    assertThat(ServletRequestPaths.applicationPath(request)).isEqualTo("/internal/tasks/1");
  }

  @Test
  void removesContextPathWhenServletPathIsEmpty() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/context/internal/tasks/1");
    request.setContextPath("/context");

    assertThat(ServletRequestPaths.applicationPath(request)).isEqualTo("/internal/tasks/1");
  }

  @Test
  void doesNotRemoveContextPathWithoutPathBoundary() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/contextual/internal/tasks/1");
    request.setContextPath("/context");

    assertThat(ServletRequestPaths.applicationPath(request))
        .isEqualTo("/contextual/internal/tasks/1");
  }
}

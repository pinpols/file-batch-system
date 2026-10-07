package io.github.pinpols.batch.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("请求路径解析:优先采用映射路径并按边界剥离上下文路径")
class ServletRequestPathsTest {

  @Test
  @DisplayName("存在映射路径时优先返回映射路径")
  void shouldPreferServletPath_whenServletMappingExists() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/context/internal/tasks/1");
    request.setContextPath("/context");
    request.setServletPath("/internal/tasks/1");

    assertThat(ServletRequestPaths.applicationPath(request)).isEqualTo("/internal/tasks/1");
  }

  @Test
  @DisplayName("映射路径为空时按上下文路径剥离得到应用内路径")
  void shouldRemoveContextPath_whenServletPathIsEmpty() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/context/internal/tasks/1");
    request.setContextPath("/context");

    assertThat(ServletRequestPaths.applicationPath(request)).isEqualTo("/internal/tasks/1");
  }

  @Test
  @DisplayName("请求地址只是以相同前缀开头时不做剥离,完整路径保留")
  void shouldKeepFullPath_whenContextPathHasNoBoundaryMatch() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/contextual/internal/tasks/1");
    request.setContextPath("/context");

    assertThat(ServletRequestPaths.applicationPath(request))
        .isEqualTo("/contextual/internal/tasks/1");
  }
}

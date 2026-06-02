package com.example.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.batch.worker.atomic.http.HttpExecutorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/** {@link HttpExecutorProdDefaults} 单测:prod profile 隐式翻 enforceAllowlist=true,显式配置不覆盖。 */
@ExtendWith(MockitoExtension.class)
class HttpExecutorProdDefaultsTest {

  @Mock private ObjectProvider<HttpExecutorProperties> httpProvider;

  private MockEnvironment env;

  @BeforeEach
  void setUp() {
    env = new MockEnvironment();
  }

  private HttpExecutorProdDefaults newDefaults() {
    return new HttpExecutorProdDefaults(env, httpProvider);
  }

  @Test
  void shouldFlipEnforceAllowlistToTrue_whenProdAndNotExplicitlyConfigured() {
    // arrange:prod + 用户未在 env/yaml 显式给 enforce-allowlist
    HttpExecutorProperties props = new HttpExecutorProperties();
    assertThat(props.isEnforceAllowlist()).isFalse(); // 出厂默认 false
    when(httpProvider.getIfAvailable()).thenReturn(props);

    // act
    newDefaults().applyProdDefaults();

    // assert:被翻成 true
    assertThat(props.isEnforceAllowlist()).isTrue();
  }

  @Test
  void shouldKeepExplicitFalse_whenUserExplicitlyDisabled() {
    // arrange:用户显式配 false(罕见,但合法 — 由 production guard 在白名单也为空时拒绝)
    env.setProperty(HttpExecutorProdDefaults.PROP_ENFORCE_ALLOWLIST, "false");
    HttpExecutorProperties props = new HttpExecutorProperties();
    when(httpProvider.getIfAvailable()).thenReturn(props);

    newDefaults().applyProdDefaults();

    assertThat(props.isEnforceAllowlist()).isFalse(); // 不动用户显式选择
  }

  @Test
  void shouldKeepExplicitTrue_whenUserExplicitlyEnabled() {
    env.setProperty(HttpExecutorProdDefaults.PROP_ENFORCE_ALLOWLIST, "true");
    HttpExecutorProperties props = new HttpExecutorProperties();
    props.setEnforceAllowlist(true);
    when(httpProvider.getIfAvailable()).thenReturn(props);

    newDefaults().applyProdDefaults();

    assertThat(props.isEnforceAllowlist()).isTrue();
  }

  @Test
  void shouldNoOp_whenHttpPropertiesBeanAbsent() {
    when(httpProvider.getIfAvailable()).thenReturn(null);

    newDefaults().applyProdDefaults(); // 不抛
  }
}

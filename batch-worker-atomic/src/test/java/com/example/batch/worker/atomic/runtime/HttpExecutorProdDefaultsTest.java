package com.example.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.batch.worker.atomic.http.HttpExecutorProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/** {@link HttpExecutorProdDefaults} 单测:prod profile 隐式翻 enforceAllowlist=true,显式配置不覆盖。 */
@ExtendWith(MockitoExtension.class)
class HttpExecutorProdDefaultsTest {

  @Mock private ObjectProvider<HttpExecutorProperties> httpProvider;

  private MockEnvironment env;
  private ListAppender<ILoggingEvent> logAppender;
  private Logger targetLogger;

  @BeforeEach
  void setUp() {
    env = new MockEnvironment();
    targetLogger = (Logger) LoggerFactory.getLogger(HttpExecutorProdDefaults.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    targetLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    targetLogger.detachAppender(logAppender);
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

  /** Round-3 #8:隐式翻 true 时必须打 INFO 日志(运维 / Console 仪表盘可见信号)。 */
  @Test
  void shouldLogInfo_whenAutoEnablingEnforceAllowlist() {
    HttpExecutorProperties props = new HttpExecutorProperties();
    when(httpProvider.getIfAvailable()).thenReturn(props);

    newDefaults().applyProdDefaults();

    assertThat(logAppender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.INFO);
              assertThat(event.getFormattedMessage())
                  .contains("ADR-029 prod hardening")
                  .contains("enforce-allowlist auto-enabled")
                  .contains("was=false");
            });
  }

  /** Round-3 #8:显式配置时也打 INFO,声明 effective 值与来源,便于排查。 */
  @Test
  void shouldLogInfo_whenExplicitlyConfigured() {
    env.setProperty(HttpExecutorProdDefaults.PROP_ENFORCE_ALLOWLIST, "true");
    HttpExecutorProperties props = new HttpExecutorProperties();
    props.setEnforceAllowlist(true);
    when(httpProvider.getIfAvailable()).thenReturn(props);

    newDefaults().applyProdDefaults();

    assertThat(logAppender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.INFO);
              assertThat(event.getFormattedMessage()).contains("explicitly configured");
            });
  }
}

package io.github.pinpols.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.pinpols.batch.worker.atomic.http.HttpExecutorProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/** {@link HttpExecutorProdDefaults} 单测:prod profile 隐式翻 enforceAllowlist=true,显式配置不覆盖。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("接口执行器生产默认值: 强制白名单开关的隐式翻转")
class HttpExecutorProdDefaultsTest {

  @Mock
  private ObjectProvider<HttpExecutorProperties> httpProvider;

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
  @DisplayName("生产档位且未显式配置时, 应把强制白名单开关隐式翻转为开启")
  void shouldFlipEnforceAllowlistToTrue_whenProdAndNotExplicitlyConfigured() {
    // 准备:prod + 用户未在 env/yaml 显式给 enforce-allowlist
    HttpExecutorProperties props = new HttpExecutorProperties();
    assertThat(props.isEnforceAllowlist()).isFalse(); // 出厂默认 false
    when(httpProvider.getIfAvailable()).thenReturn(props);

    // 执行
    newDefaults().applyProdDefaults();

    // 断言:被翻成 true
    assertThat(props.isEnforceAllowlist()).isTrue();
  }

  @Test
  @DisplayName("用户显式关闭强制白名单时应尊重其选择, 不再自动翻转")
  void shouldKeepExplicitFalse_whenUserExplicitlyDisabled() {
    // 准备:用户显式配 false(罕见,但合法 — 由 production guard 在白名单也为空时拒绝)
    env.setProperty(HttpExecutorProdDefaults.PROP_ENFORCE_ALLOWLIST, "false");
    HttpExecutorProperties props = new HttpExecutorProperties();
    when(httpProvider.getIfAvailable()).thenReturn(props);

    newDefaults().applyProdDefaults();

    assertThat(props.isEnforceAllowlist()).isFalse(); // 不动用户显式选择
  }

  @Test
  @DisplayName("用户显式开启强制白名单时应保持开启不变")
  void shouldKeepExplicitTrue_whenUserExplicitlyEnabled() {
    env.setProperty(HttpExecutorProdDefaults.PROP_ENFORCE_ALLOWLIST, "true");
    HttpExecutorProperties props = new HttpExecutorProperties();
    props.setEnforceAllowlist(true);
    when(httpProvider.getIfAvailable()).thenReturn(props);

    newDefaults().applyProdDefaults();

    assertThat(props.isEnforceAllowlist()).isTrue();
  }

  @Test
  @DisplayName("接口执行器配置 bean 缺失时应静默跳过, 不抛出异常")
  void shouldNoOp_whenHttpPropertiesBeanAbsent() {
    when(httpProvider.getIfAvailable()).thenReturn(null);

    assertThatCode(() -> newDefaults().applyProdDefaults()).doesNotThrowAnyException();
    verify(httpProvider).getIfAvailable();
  }

  /** Round-3 #8:隐式翻 true 时必须打 INFO 日志(运维 / Console 仪表盘可见信号)。 */
  @Test
  @DisplayName("自动翻转开关时应输出信息级日志, 说明变更前后取值")
  void shouldLogInfo_whenAutoEnablingEnforceAllowlist() {
    HttpExecutorProperties props = new HttpExecutorProperties();
    when(httpProvider.getIfAvailable()).thenReturn(props);

    newDefaults().applyProdDefaults();

    assertThat(logAppender.list).anySatisfy(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.INFO);
      assertThat(event.getFormattedMessage())
          .contains("ADR-029 prod hardening")
          .contains("enforce-allowlist auto-enabled")
          .contains("was=false");
    });
  }

  /** Round-3 #8:显式配置时也打 INFO,声明 effective 值与来源,便于排查。 */
  @Test
  @DisplayName("用户显式配置时应输出信息级日志, 声明生效值与来源")
  void shouldLogInfo_whenExplicitlyConfigured() {
    env.setProperty(HttpExecutorProdDefaults.PROP_ENFORCE_ALLOWLIST, "true");
    HttpExecutorProperties props = new HttpExecutorProperties();
    props.setEnforceAllowlist(true);
    when(httpProvider.getIfAvailable()).thenReturn(props);

    newDefaults().applyProdDefaults();

    assertThat(logAppender.list).anySatisfy(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.INFO);
      assertThat(event.getFormattedMessage()).contains("explicitly configured");
    });
  }
}

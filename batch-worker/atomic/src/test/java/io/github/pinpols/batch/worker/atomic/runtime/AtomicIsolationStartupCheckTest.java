package io.github.pinpols.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.atomic.http.HttpExecutorProperties;
import io.github.pinpols.batch.worker.atomic.shell.ShellExecutorProperties;
import io.github.pinpols.batch.worker.atomic.spark.SparkSubmitExecutorProperties;
import io.github.pinpols.batch.worker.atomic.sql.SqlExecutorProperties;
import io.github.pinpols.batch.worker.atomic.storedproc.StoredProcExecutorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

/**
 * {@link AtomicIsolationStartupCheck} 单测 —— mock {@link Environment} + executor {@link
 * ObjectProvider}。
 *
 * <p>覆盖三条核心路径:启用 dual-use + require-isolation + 未 ack → throw;启用但不 require → 只 warn 不 throw;ack 过 →
 * 不 throw。
 */
@ExtendWith(MockitoExtension.class)
class AtomicIsolationStartupCheckTest {

  @Mock private ObjectProvider<ShellExecutorProperties> shellProvider;
  @Mock private ObjectProvider<SqlExecutorProperties> sqlProvider;
  @Mock private ObjectProvider<StoredProcExecutorProperties> storedProcProvider;
  @Mock private ObjectProvider<HttpExecutorProperties> httpProvider;
  @Mock private ObjectProvider<SparkSubmitExecutorProperties> sparkProvider;
  @Mock private Environment environment;

  private AtomicIsolationStartupCheck newCheck() {
    return new AtomicIsolationStartupCheck(
        shellProvider, sqlProvider, storedProcProvider, httpProvider, sparkProvider, environment);
  }

  private void enableShell() {
    ShellExecutorProperties shell = new ShellExecutorProperties();
    shell.setEnabled(true);
    when(shellProvider.getIfAvailable()).thenReturn(shell);
  }

  /** 把其余 provider 都设为「无 bean」,避免每个测试重复写。 */
  private void noOtherExecutors() {
    lenient().when(sqlProvider.getIfAvailable()).thenReturn(null);
    lenient().when(storedProcProvider.getIfAvailable()).thenReturn(null);
    lenient().when(httpProvider.getIfAvailable()).thenReturn(null);
    lenient().when(shellProvider.getIfAvailable()).thenReturn(null);
    lenient().when(sparkProvider.getIfAvailable()).thenReturn(null);
  }

  private void enableSpark() {
    SparkSubmitExecutorProperties spark = new SparkSubmitExecutorProperties();
    spark.setEnabled(true);
    when(sparkProvider.getIfAvailable()).thenReturn(spark);
  }

  @Test
  void sparkEnabled_requireIsolation_notAcked_throws() {
    // S-2:spark-submit 是 dual-use RCE,启用 + require-isolation + 未 ack 必须 fail-fast。
    noOtherExecutors();
    enableSpark();
    stubFlags(true, false);

    assertThatThrownBy(() -> newCheck().checkOnStartup())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spark-submit");
  }

  private void stubFlags(boolean requireIsolation, boolean acknowledged) {
    lenient()
        .when(
            environment.getProperty(
                AtomicIsolationStartupCheck.PROP_REQUIRE_ISOLATION, Boolean.class, false))
        .thenReturn(requireIsolation);
    lenient()
        .when(
            environment.getProperty(
                AtomicIsolationStartupCheck.PROP_ISOLATION_ACKNOWLEDGED, Boolean.class, false))
        .thenReturn(acknowledged);
  }

  @Test
  void enabled_requireIsolation_notAcked_throws() {
    noOtherExecutors();
    enableShell();
    stubFlags(true, false);

    assertThatThrownBy(() -> newCheck().checkOnStartup())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ADR-029 isolation gate")
        .hasMessageContaining("shell");
  }

  @Test
  void enabled_notRequired_onlyWarns_noThrow() {
    noOtherExecutors();
    enableShell();
    stubFlags(false, false);

    assertThatCode(() -> newCheck().checkOnStartup()).doesNotThrowAnyException();
  }

  @Test
  void enabled_requireIsolation_acked_noThrow() {
    noOtherExecutors();
    enableShell();
    stubFlags(true, true);

    assertThatCode(() -> newCheck().checkOnStartup()).doesNotThrowAnyException();
  }

  @Test
  void noDualUseEnabled_noThrow() {
    noOtherExecutors();

    assertThatCode(() -> newCheck().checkOnStartup()).doesNotThrowAnyException();
  }
}

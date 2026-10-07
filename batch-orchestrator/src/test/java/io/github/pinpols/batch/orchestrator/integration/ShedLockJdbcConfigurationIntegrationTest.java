package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.MeteredLockProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "batch.shedlock.provider=jdbc")
@DisplayName("ShedLock JDBC 切换: 完整 Orchestrator 上下文装配与互斥语义")
class ShedLockJdbcConfigurationIntegrationTest extends AbstractIntegrationTest {

  private final LockProvider lockProvider;

  @Autowired
  ShedLockJdbcConfigurationIntegrationTest(LockProvider lockProvider) {
    this.lockProvider = lockProvider;
  }

  @Test
  @DisplayName("provider=jdbc 时装配 JDBC 提供者且同名锁保持互斥")
  void shouldSwitchToJdbcProviderAndKeepMutualExclusion() {
    assertThat(lockProvider)
        .isInstanceOfSatisfying(
            MeteredLockProvider.class,
            provider -> assertThat(provider.providerType()).isEqualTo("jdbc"));

    String lockName = "it-jdbc-provider-switch-" + System.nanoTime();
    LockConfiguration lockConfiguration = new LockConfiguration(
        BatchDateTimeSupport.utcNow(), lockName, Duration.ofSeconds(5), Duration.ZERO);
    Optional<SimpleLock> first = lockProvider.lock(lockConfiguration);
    Optional<SimpleLock> second = lockProvider.lock(lockConfiguration);

    assertThat(first).isPresent();
    assertThat(second).isEmpty();
    first.ifPresent(SimpleLock::unlock);
    Optional<SimpleLock> reacquired = lockProvider.lock(lockConfiguration);
    assertThat(reacquired).isPresent();
    reacquired.orElseThrow().unlock();
  }
}

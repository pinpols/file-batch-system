package io.github.pinpols.batch.console.support.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.ConsoleMaintenanceProperties;
import io.github.pinpols.batch.console.domain.ops.mapper.MaintenanceStateMapper;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("维护状态读取与应用启动时序")
class MaintenanceStateHolderTest {

  private MaintenanceStateHolder stateHolder;

  @AfterEach
  void shutDownStateHolder() {
    if (stateHolder != null) {
      stateHolder.shutdown();
    }
  }

  @DisplayName("应用就绪后才读取已迁移维护状态表")
  @Test
  void shouldWaitUntilApplicationReadyBeforeReadingMigratedMaintenanceTable() {
    ConsoleMaintenanceProperties properties = new ConsoleMaintenanceProperties();
    MaintenanceStateMapper mapper = mock(MaintenanceStateMapper.class);
    MaintenanceStateEntity entity = new MaintenanceStateEntity();
    entity.setEnabled(true);
    entity.setReadOnly(true);
    entity.setMessage("maintenance loaded after migration");
    entity.setAffectedServicesJson("[\"workflow\"]");
    entity.setVersion(7L);
    entity.setUpdatedAt(Instant.parse("2026-10-17T00:00:00Z"));
    when(mapper.selectSingleton()).thenReturn(entity);
    stateHolder = new MaintenanceStateHolder(properties, mapper, new ObjectMapper());

    stateHolder.initFromProperties();

    verifyNoInteractions(mapper);
    assertThat(stateHolder.sharedStateAvailable()).isFalse();

    ReflectionTestUtils.invokeMethod(stateHolder, "refreshAfterApplicationReady");

    verify(mapper).selectSingleton();
    assertThat(stateHolder.sharedStateAvailable()).isTrue();
    assertThat(stateHolder.current().enabled()).isTrue();
    assertThat(stateHolder.current().readOnly()).isTrue();
    assertThat(stateHolder.current().affectedServices()).containsExactly("workflow");
    assertThat(stateHolder.current().version()).isEqualTo(7L);
  }
}

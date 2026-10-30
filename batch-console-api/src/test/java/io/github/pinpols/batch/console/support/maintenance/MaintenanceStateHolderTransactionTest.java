package io.github.pinpols.batch.console.support.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.ConsoleMaintenanceProperties;
import io.github.pinpols.batch.console.domain.ops.mapper.MaintenanceStateMapper;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("维护状态事务：通知只消费已提交的共享状态")
class MaintenanceStateHolderTransactionTest {
  private MaintenanceStateHolder holder;

  @BeforeEach
  void setUp() {
    MaintenanceStateMapper mapper = mock(MaintenanceStateMapper.class);
    when(mapper.updateIfVersion(any())).thenReturn(1);
    holder =
        new MaintenanceStateHolder(new ConsoleMaintenanceProperties(), mapper, new ObjectMapper());
    holder.initFromProperties();
    TransactionSynchronizationManager.initSynchronization();
  }

  @AfterEach
  void tearDown() {
    TransactionSynchronizationManager.clearSynchronization();
    holder.shutdown();
  }

  @Test
  @DisplayName("提交前和事务回滚后均不发布未提交维护状态")
  void shouldNotExposeStateBeforeCommitOrAfterRollback() {
    MaintenanceState next = new MaintenanceState(true, true, "维护", null, List.of("trigger"));
    holder.update(next);
    assertThat(holder.current().enabled()).isFalse();
    for (TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    }
    assertThat(holder.current().enabled()).isFalse();
  }

  @Test
  @DisplayName("数据库提交后才开放维护状态给通知同步与请求拦截")
  void shouldExposeStateAfterCommit() {
    MaintenanceState next = new MaintenanceState(true, true, "维护", null, List.of("trigger"));
    holder.update(next);
    for (TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      synchronization.afterCommit();
    }
    assertThat(holder.current().enabled()).isTrue();
    assertThat(holder.current().version()).isEqualTo(1L);
  }
}

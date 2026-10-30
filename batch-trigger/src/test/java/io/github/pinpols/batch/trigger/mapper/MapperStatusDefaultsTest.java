package io.github.pinpols.batch.trigger.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.persistence.entity.TriggerMisfirePendingEntity;
import io.github.pinpols.batch.common.persistence.entity.TriggerOutboxEventEntity;
import io.github.pinpols.batch.trigger.domain.param.TriggerMisfirePendingUpdateApprovalStatusParam;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Trigger Mapper 状态默认方法:固定状态与调用方参数保持一致")
class MapperStatusDefaultsTest {

  @Test
  @DisplayName("误触发待审流程装配所有状态转换参数")
  void shouldDelegateMisfireStatuses() {
    TriggerMisfirePendingMapper mapper =
        mock(TriggerMisfirePendingMapper.class, CALLS_REAL_METHODS);
    TriggerMisfirePendingEntity entity = mock(TriggerMisfirePendingEntity.class);
    Instant now = Instant.parse("2026-10-13T00:00:00Z");
    doReturn(1).when(mapper).insertPendingWithStatus(entity, "PENDING");
    doReturn(List.of()).when(mapper).selectByTenantAndStatus("tenant-a", "PENDING", 15);
    doReturn(1)
        .when(mapper)
        .updateApprovalStatus(TriggerMisfirePendingUpdateApprovalStatusParam.builder()
            .id(3L)
            .approvedBy("reviewer")
            .rejectionReason(null)
            .writeRejectionReason(false)
            .targetStatus("APPROVED")
            .expectedStatus("PENDING")
            .build());
    doReturn(1)
        .when(mapper)
        .updateApprovalStatus(TriggerMisfirePendingUpdateApprovalStatusParam.builder()
            .id(3L)
            .approvedBy("reviewer")
            .rejectionReason("invalid")
            .writeRejectionReason(true)
            .targetStatus("REJECTED")
            .expectedStatus("PENDING")
            .build());
    doReturn(1).when(mapper).expireBefore(now, "EXPIRED", "PENDING");

    assertThat(mapper.insertPending(entity)).isEqualTo(1);
    assertThat(mapper.selectPendingByTenant("tenant-a", 15)).isEmpty();
    assertThat(mapper.approve(3L, "reviewer")).isEqualTo(1);
    assertThat(mapper.reject(3L, "reviewer", "invalid")).isEqualTo(1);
    assertThat(mapper.markExpired(now)).isEqualTo(1);
  }

  @Test
  @DisplayName("Trigger outbox 插入固定使用 NEW 状态")
  void shouldInsertTriggerOutboxAsNew() {
    TriggerOutboxEventMapper mapper = mock(TriggerOutboxEventMapper.class, CALLS_REAL_METHODS);
    TriggerOutboxEventEntity entity = mock(TriggerOutboxEventEntity.class);
    doReturn(1).when(mapper).insertWithDefaultStatus(entity, "NEW");

    assertThat(mapper.insert(entity)).isEqualTo(1);
  }
}

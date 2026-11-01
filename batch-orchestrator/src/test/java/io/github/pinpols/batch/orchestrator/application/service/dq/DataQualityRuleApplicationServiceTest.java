package io.github.pinpols.batch.orchestrator.application.service.dq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.command.DataQualityRuleWriteCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import io.github.pinpols.batch.orchestrator.mapper.DataQualityRuleMapper;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("数据质量规则应用服务: 调用方租户覆盖与标识绑定口径")
class DataQualityRuleApplicationServiceTest {

  @Mock
  private DataQualityRuleMapper mapper;

  @Test
  @DisplayName("create 以调用方 tenantId 覆盖 body 租户")
  void shouldOverrideBodyTenant_whenCreating() {
    DataQualityRuleWriteCommand rule = DataQualityRuleWriteCommand.builder()
        .tenantId("attacker-tenant")
        .ruleCode("RULE")
        .build();
    DataQualityRuleApplicationService service = new DataQualityRuleApplicationService(mapper);
    Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
    DataQualityRuleEntity persisted = new DataQualityRuleEntity();
    persisted.setId(42L);
    persisted.setTenantId("trusted-tenant");
    persisted.setRuleCode("RULE");
    persisted.setSeverity("WARN");
    persisted.setEnabled(true);
    persisted.setCreatedAt(createdAt);
    when(mapper.insert(any(DataQualityRuleEntity.class))).thenAnswer(invocation -> {
      DataQualityRuleEntity inserted = invocation.getArgument(0);
      inserted.setId(42L);
      return 1;
    });
    when(mapper.selectById(42L, "trusted-tenant")).thenReturn(persisted);

    DataQualityRuleEntity created = service.create("trusted-tenant", rule);

    assertThat(created).isSameAs(persisted);
    assertThat(created.getSeverity()).isEqualTo("WARN");
    assertThat(created.getEnabled()).isTrue();
    assertThat(created.getCreatedAt()).isEqualTo(createdAt);
    org.mockito.ArgumentCaptor<DataQualityRuleEntity> inserted =
        org.mockito.ArgumentCaptor.forClass(DataQualityRuleEntity.class);
    verify(mapper).insert(inserted.capture());
    assertThat(inserted.getValue().getTenantId()).isEqualTo("trusted-tenant");
  }

  @Test
  @DisplayName("update 以调用方 tenantId 覆盖 body 租户并绑定 id")
  void shouldOverrideTenantAndBindId_whenUpdating() {
    DataQualityRuleWriteCommand rule = DataQualityRuleWriteCommand.builder()
        .tenantId("attacker-tenant")
        .ruleCode("RULE")
        .build();
    DataQualityRuleApplicationService service = new DataQualityRuleApplicationService(mapper);
    Instant updatedAt = Instant.parse("2026-01-02T00:00:00Z");
    DataQualityRuleEntity persisted = new DataQualityRuleEntity();
    persisted.setId(42L);
    persisted.setTenantId("trusted-tenant");
    persisted.setRuleCode("ORIGINAL_RULE");
    persisted.setUpdatedAt(updatedAt);

    when(mapper.update(any(DataQualityRuleEntity.class))).thenReturn(1);
    when(mapper.selectById(42L, "trusted-tenant")).thenReturn(persisted);
    DataQualityRuleEntity updated = service.update("trusted-tenant", 42L, rule);

    assertThat(updated).isSameAs(persisted);
    assertThat(updated.getTenantId()).isEqualTo("trusted-tenant");
    assertThat(updated.getId()).isEqualTo(42L);
    assertThat(updated.getRuleCode()).isEqualTo("ORIGINAL_RULE");
    assertThat(updated.getUpdatedAt()).isEqualTo(updatedAt);
    org.mockito.ArgumentCaptor<DataQualityRuleEntity> submitted =
        org.mockito.ArgumentCaptor.forClass(DataQualityRuleEntity.class);
    verify(mapper).update(submitted.capture());
    assertThat(submitted.getValue().getTenantId()).isEqualTo("trusted-tenant");
    assertThat(submitted.getValue().getId()).isEqualTo(42L);
  }
}

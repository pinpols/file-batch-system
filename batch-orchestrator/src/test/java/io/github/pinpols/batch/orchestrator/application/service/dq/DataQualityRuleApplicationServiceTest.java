package io.github.pinpols.batch.orchestrator.application.service.dq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import io.github.pinpols.batch.orchestrator.mapper.DataQualityRuleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DataQualityRuleApplicationServiceTest {

  @Mock private DataQualityRuleMapper mapper;

  @Test
  @DisplayName("create 以调用方 tenantId 覆盖 body 租户")
  void createOverridesBodyTenant() {
    DataQualityRuleEntity rule = new DataQualityRuleEntity();
    rule.setTenantId("attacker-tenant");
    DataQualityRuleApplicationService service = new DataQualityRuleApplicationService(mapper);

    DataQualityRuleEntity created = service.create("trusted-tenant", rule);

    assertThat(created.getTenantId()).isEqualTo("trusted-tenant");
    verify(mapper).insert(rule);
  }

  @Test
  @DisplayName("update 以调用方 tenantId 覆盖 body 租户并绑定 id")
  void updateOverridesBodyTenantAndBindsId() {
    DataQualityRuleEntity rule = new DataQualityRuleEntity();
    rule.setTenantId("attacker-tenant");
    DataQualityRuleApplicationService service = new DataQualityRuleApplicationService(mapper);

    DataQualityRuleEntity updated = service.update("trusted-tenant", 42L, rule);

    assertThat(updated.getTenantId()).isEqualTo("trusted-tenant");
    assertThat(updated.getId()).isEqualTo(42L);
    verify(mapper).update(rule);
  }
}

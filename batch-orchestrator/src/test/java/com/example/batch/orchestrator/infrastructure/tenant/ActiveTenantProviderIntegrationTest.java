package com.example.batch.orchestrator.infrastructure.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.mapper.TenantRoutingMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** IT:验证 ActiveTenantProvider 能正确查出启用租户、过滤停用租户,并在 30s 内命中内存缓存。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    // 本类专测缓存命中语义,覆盖 application-test.yml 的全局 0(关缓存)为 30s;
    // 用 invalidateCache() 控制确定性。其余调度测试靠全局 0 避免 staleness。
    properties = {
      "batch.startup-self-check.enabled=false",
      "batch.tenant.active-cache-ttl-millis=30000"
    })
class ActiveTenantProviderIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ActiveTenantProvider provider;
  @MockitoSpyBean private TenantRoutingMapper tenantRoutingMapper;

  private String activeTenantId;
  private String inactiveTenantId;

  @AfterEach
  void cleanup() {
    if (activeTenantId != null) {
      jdbcTemplate.update("DELETE FROM batch.tenant WHERE tenant_id = ?", activeTenantId);
    }
    if (inactiveTenantId != null) {
      jdbcTemplate.update("DELETE FROM batch.tenant WHERE tenant_id = ?", inactiveTenantId);
    }
  }

  @Test
  @DisplayName("activeTenantIds 应含 ACTIVE 租户、不含 SUSPENDED 租户")
  void shouldReturnOnlyActiveTenants() {
    // 全量套件中其他测试可能已触发缓存(30s 窗口),先失效保证读到本测试新插租户
    provider.invalidateCache();
    // arrange
    long suffix = System.nanoTime();
    activeTenantId = "atp-active-" + suffix;
    inactiveTenantId = "atp-inactive-" + suffix;

    jdbcTemplate.update(
        "INSERT INTO batch.tenant (tenant_id, tenant_name, status, created_by)"
            + " VALUES (?, ?, 'ACTIVE', 'it')",
        activeTenantId,
        "IT Active Tenant " + suffix);
    jdbcTemplate.update(
        "INSERT INTO batch.tenant (tenant_id, tenant_name, status, created_by)"
            + " VALUES (?, ?, 'SUSPENDED', 'it')",
        inactiveTenantId,
        "IT Suspended Tenant " + suffix);

    // act
    List<String> result = provider.activeTenantIds();

    // assert
    assertThat(result).as("启用租户应出现在结果中").contains(activeTenantId);
    assertThat(result).as("SUSPENDED 租户不应出现在结果中").doesNotContain(inactiveTenantId);
  }

  @Test
  @DisplayName("连续第二次调用应命中缓存,mapper 只被调用 1 次")
  void shouldHitCacheOnSecondCall() {
    // arrange
    long suffix = System.nanoTime();
    activeTenantId = "atp-cache-" + suffix;
    inactiveTenantId = "atp-cache-inactive-" + suffix;

    jdbcTemplate.update(
        "INSERT INTO batch.tenant (tenant_id, tenant_name, status, created_by)"
            + " VALUES (?, ?, 'ACTIVE', 'it')",
        activeTenantId,
        "IT Cache Tenant " + suffix);
    jdbcTemplate.update(
        "INSERT INTO batch.tenant (tenant_id, tenant_name, status, created_by)"
            + " VALUES (?, ?, 'SUSPENDED', 'it')",
        inactiveTenantId,
        "IT Cache Suspended " + suffix);

    // act: 强制让 provider 缓存失效,使第一次调用打库
    provider.invalidateCache();
    List<String> first = provider.activeTenantIds();
    List<String> second = provider.activeTenantIds();

    // assert
    assertThat(first).as("两次返回结果应相同").isEqualTo(second);
    verify(tenantRoutingMapper, times(1)).selectActiveTenantIds();
  }
}

package com.example.batch.console.domain.ops.service;

import com.example.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 测试数据清理 service。console 只做入口编排，实际清理由 orchestrator 内部接口执行。 */
@Service
@RequiredArgsConstructor
public class ConsoleAdminTestDataCleanupService {

  private final ConsoleOrchestratorProxyService orchestratorProxyService;

  public Map<String, Integer> cleanupByPrefix(String prefix) {
    return orchestratorProxyService.adminTestDataCleanupByPrefix(prefix);
  }

  public Map<String, Integer> cleanupByExactTenantIds(List<String> tenantIds) {
    return orchestratorProxyService.adminTestDataCleanupByExactTenantIds(tenantIds);
  }
}

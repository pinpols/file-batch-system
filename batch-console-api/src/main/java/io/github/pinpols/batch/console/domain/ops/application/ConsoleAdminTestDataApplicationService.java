package io.github.pinpols.batch.console.domain.ops.application;

import io.github.pinpols.batch.common.config.BatchProfileSupport;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Application boundary for guarded test-data cleanup requests. */
@Service
@RequiredArgsConstructor
public class ConsoleAdminTestDataApplicationService {

  private final ConsoleOrchestratorPort orchestratorPort;
  private final Environment environment;

  public Map<String, Integer> cleanupByPrefix(String prefix) {
    assertCleanupAllowed();
    return orchestratorPort.adminTestDataCleanupByPrefix(prefix);
  }

  public Map<String, Integer> cleanupByExactIds(String ids) {
    assertCleanupAllowed();
    List<String> tenantIds = Arrays.stream(ids.split(","))
        .map(String::trim)
        .filter(tenantId -> !tenantId.isEmpty())
        .toList();
    return orchestratorPort.adminTestDataCleanupByExactTenantIds(tenantIds);
  }

  private void assertCleanupAllowed() {
    if (BatchProfileSupport.isProductionProfile(environment)) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.admin.test_data_cleanup_prod_forbidden");
    }
  }
}

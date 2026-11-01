package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.common.config.BatchProfileSupport;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.infrastructure.admin.AdminTestDataCleanupRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 测试数据级联清理服务。只暴露给 orchestrator 内部接口，console 通过代理触发。 */
@Service
@RequiredArgsConstructor
public class AdminTestDataCleanupService {

  private final AdminTestDataCleanupRepository repository;
  private final Environment environment;

  // 捕获、歧义检查及删除共享同一快照，避免后续裸 ID 弱引用清理看到新插入的范围外实例。
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public Map<String, Integer> cleanupByPrefix(String prefix) {
    rejectProductionProfile();
    return repository.cleanupByPrefix(prefix);
  }

  @Transactional
  public Map<String, Integer> cleanupByExactTenantIds(String ids) {
    rejectProductionProfile();
    List<String> tenantIds = Arrays.stream(ids.split(","))
        .map(String::trim)
        .filter(value -> EmptyChecks.isNotEmpty(value))
        .toList();
    if (EmptyChecks.isEmpty(tenantIds)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.required");
    }
    return repository.cleanupByExactTenantIds(tenantIds);
  }

  private void rejectProductionProfile() {
    if (BatchProfileSupport.isProductionProfile(environment)) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.admin.test_data_cleanup_prod_forbidden");
    }
  }
}

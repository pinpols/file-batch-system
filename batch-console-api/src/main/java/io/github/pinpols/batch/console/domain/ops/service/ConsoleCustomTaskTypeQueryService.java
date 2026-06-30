package io.github.pinpols.batch.console.domain.ops.service;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.ops.entity.CustomTaskTypeEntity;
import io.github.pinpols.batch.console.domain.ops.mapper.CustomTaskTypeMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 自定义 taskType 控制台只读查询服务。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsoleCustomTaskTypeQueryService {

  private final CustomTaskTypeMapper mapper;
  private final ConsoleTenantGuard tenantGuard;

  public List<CustomTaskTypeEntity> listActive(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return mapper.selectActiveByTenant(resolved);
  }

  public long countActive(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return mapper.countActiveByTenant(resolved);
  }

  public CustomTaskTypeEntity detail(String tenantId, String taskTypeCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    CustomTaskTypeEntity entity = mapper.selectByTenantAndCode(resolved, taskTypeCode);
    if (entity == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.common.not_found_detail", taskTypeCode);
    }
    return entity;
  }
}

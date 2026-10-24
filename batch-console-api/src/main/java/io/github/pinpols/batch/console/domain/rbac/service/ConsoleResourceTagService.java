package io.github.pinpols.batch.console.domain.rbac.service;

import io.github.pinpols.batch.console.domain.rbac.param.ConsoleResourceTagUpsertParam;


import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.domain.rbac.entity.ResourceTagEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleResourceTagMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConsoleResourceTagService {

  private static final Set<String> VALID_RESOURCE_TYPES =
      Set.of("JOB", "WORKFLOW", "FILE_CHANNEL", "FILE_TEMPLATE");

  private final ConsoleResourceTagMapper repository;
  private final ConsoleTenantGuard tenantGuard;

  public List<ResourceTagEntity> listByResource(
      String tenantId, String resourceType, String resourceCode) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    String normalizedResourceType = validateResourceType(resourceType);
    return repository.findByResource(resolvedTenantId, normalizedResourceType, resourceCode);
  }

  public List<ResourceTagEntity> listByTagKey(String tenantId, String tagKey, String tagValue) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    if (tagValue != null && !tagValue.isBlank()) {
      return repository.findByTagKeyAndValue(resolved, tagKey, tagValue);
    }
    return repository.findByTagKey(resolved, tagKey);
  }

  public List<String> listDistinctKeys(String tenantId) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    return repository.findDistinctTagKeys(resolvedTenantId);
  }

  @Transactional
  public void upsert(ResourceTagUpsertInput input) {
    String resolvedTenant = tenantGuard.resolveTenant(input.tenantId());
    String normalizedType = validateResourceType(input.resourceType());
    String resourceCode = input.resourceCode();
    String tagKey = input.tagKey();
    String tagValue = input.tagValue();
    String operator = input.operator();
    Guard.requireText(resourceCode, "resourceCode is required");
    repository.lockResourceTagIdentity(resolvedTenant, normalizedType, resourceCode);
    if (EmptyChecks.isNull(repository.selectResourceTagTarget(resolvedTenant, normalizedType, resourceCode))) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "resource does not exist in tenant: " + normalizedType + "/" + resourceCode);
    }
    ConsoleResourceTagUpsertParam consoleResourceTagUpsertParam = ConsoleResourceTagUpsertParam.builder()
        .tenantId(resolvedTenant)
        .resourceType(normalizedType)
        .resourceCode(resourceCode)
        .tagKey(tagKey)
        .tagValue(tagValue == null ? "" : tagValue)
        .operator(operator)
        .build();
    repository.upsert(
        consoleResourceTagUpsertParam);
  }

  @Transactional
  public void delete(String tenantId, String resourceType, String resourceCode, String tagKey) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    String normalizedResourceType = validateResourceType(resourceType);
    repository.deleteByResourceAndKey(
        resolvedTenantId, normalizedResourceType, resourceCode, tagKey);
  }

  @Transactional
  public void deleteAllByResource(String tenantId, String resourceType, String resourceCode) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    String normalizedResourceType = validateResourceType(resourceType);
    repository.deleteAllByResource(resolvedTenantId, normalizedResourceType, resourceCode);
  }

  private String validateResourceType(String resourceType) {
    Guard.requireText(resourceType, "resourceType is required");
    String normalized = resourceType.toUpperCase(Locale.ROOT);
    if (!VALID_RESOURCE_TYPES.contains(normalized)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "resourceType must be one of: " + VALID_RESOURCE_TYPES);
    }
    return normalized;
  }
}

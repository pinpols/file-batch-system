package com.example.batch.console.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.domain.entity.ResourceTagEntity;
import com.example.batch.console.mapper.ConsoleResourceTagMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
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
    return repository.findByResource(
        tenantGuard.resolveTenant(tenantId), validateResourceType(resourceType), resourceCode);
  }

  public List<ResourceTagEntity> listByTagKey(String tenantId, String tagKey, String tagValue) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    if (tagValue != null && !tagValue.isBlank()) {
      return repository.findByTagKeyAndValue(resolved, tagKey, tagValue);
    }
    return repository.findByTagKey(resolved, tagKey);
  }

  public List<String> listDistinctKeys(String tenantId) {
    return repository.findDistinctTagKeys(tenantGuard.resolveTenant(tenantId));
  }

  @Transactional
  public void upsert(
      String tenantId,
      String resourceType,
      String resourceCode,
      String tagKey,
      String tagValue,
      String operator) {
    repository.upsert(
        tenantGuard.resolveTenant(tenantId),
        validateResourceType(resourceType),
        resourceCode,
        tagKey,
        tagValue == null ? "" : tagValue,
        operator);
  }

  @Transactional
  public void delete(String tenantId, String resourceType, String resourceCode, String tagKey) {
    repository.deleteByResourceAndKey(
        tenantGuard.resolveTenant(tenantId),
        validateResourceType(resourceType),
        resourceCode,
        tagKey);
  }

  @Transactional
  public void deleteAllByResource(String tenantId, String resourceType, String resourceCode) {
    repository.deleteAllByResource(
        tenantGuard.resolveTenant(tenantId), validateResourceType(resourceType), resourceCode);
  }

  private String validateResourceType(String resourceType) {
    Guard.requireText(resourceType, "resourceType is required");
    String normalized = resourceType.toUpperCase(Locale.ROOT);
    if (!VALID_RESOURCE_TYPES.contains(normalized)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "resourceType must be one of: " + VALID_RESOURCE_TYPES);
    }
    return normalized;
  }
}

package io.github.pinpols.batch.console.domain.rbac.service;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.ConsoleOidcProperties;
import io.github.pinpols.batch.console.domain.rbac.application.contract.request.ConsoleOidcIdentityRequest;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleOidcIdentityResponse;
import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleOidcIdentityEntity;
import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleUserAccountEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleOidcIdentityMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理 OIDC 主体到本地账号的显式映射；IdP claims 不参与角色授予。 */
@Service
@RequiredArgsConstructor
public class ConsoleOidcIdentityService {

  private final ConsoleOidcIdentityMapper identityMapper;
  private final ConsoleUserAccountMapper userAccountMapper;
  private final ConsoleOidcProperties properties;

  public List<ConsoleOidcIdentityResponse> list(String tenantId) {
    requireConfiguredTenant(tenantId);
    return identityMapper.findByTenant(tenantId).stream().map(this::toResponse).toList();
  }

  @Transactional
  public ConsoleOidcIdentityResponse bind(
      String tenantId, ConsoleOidcIdentityRequest request, String linkedBy) {
    requireConfiguredTenant(tenantId);
    if (request == null || linkedBy == null || linkedBy.isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.console.oidc.identity_invalid");
    }
    String subject = request.subject();
    if (subject == null || subject.isBlank() || subject.length() > 255 || !isAscii(subject)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.console.oidc.identity_invalid");
    }
    ConsoleUserAccountEntity account = userAccountMapper
        .findByUsernameIgnoreCase(request.username())
        .filter(user -> user.isEnabled() && tenantId.equals(user.getTenantId()))
        .orElseThrow(() ->
            BizException.of(ResultCode.NOT_FOUND, "error.account.not_found", request.username()));
    try {
      identityMapper.insert(
          tenantId, account.getId(), properties.getIssuerUri(), subject, linkedBy);
    } catch (DuplicateKeyException exception) {
      throw BizException.of(ResultCode.CONFLICT, "error.console.oidc.identity_conflict");
    }
    return identityMapper
        .findByAccountAndTenant(account.getId(), tenantId)
        .map(this::toResponse)
        .orElseThrow(() ->
            BizException.of(ResultCode.SYSTEM_ERROR, "error.console.oidc.identity_write_failed"));
  }

  @Transactional
  public void unbind(String tenantId, long identityId) {
    requireConfiguredTenant(tenantId);
    if (identityMapper.deleteByIdAndTenant(identityId, tenantId) != 1) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.console.oidc.identity_not_found");
    }
  }

  private void requireConfiguredTenant(String tenantId) {
    if (!properties.isEnabled() || tenantId == null || !tenantId.equals(properties.getTenantId())) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.console.oidc.provider_unavailable");
    }
  }

  private ConsoleOidcIdentityResponse toResponse(ConsoleOidcIdentityEntity entity) {
    return new ConsoleOidcIdentityResponse(
        entity.getId(),
        entity.getTenantId(),
        entity.getAccountId(),
        entity.getUsername(),
        entity.getIssuer(),
        entity.getSubject(),
        entity.getLinkedBy(),
        entity.getLinkedAt());
  }

  private static boolean isAscii(String value) {
    return value.chars().allMatch(character -> character <= 0x7f);
  }
}

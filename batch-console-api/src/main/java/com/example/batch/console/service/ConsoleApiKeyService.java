package com.example.batch.console.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.entity.ApiKeyEntity;
import com.example.batch.console.repository.ConsoleApiKeyRepository;
import com.example.batch.console.support.ConsoleTenantGuard;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConsoleApiKeyService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int RAW_KEY_BYTES = 32;

    private final ConsoleApiKeyRepository repository;
    private final ConsoleTenantGuard tenantGuard;

    public List<ApiKeyEntity> list(String tenantId) {
        return repository.findAllByTenant(tenantGuard.resolveTenant(tenantId));
    }

    public ApiKeyEntity detail(String tenantId, Long id) {
        return repository
                .findByTenantAndId(tenantGuard.resolveTenant(tenantId), id)
                .orElseThrow(() -> new BizException(ResultCode.NOT_FOUND, "api key not found"));
    }

    /** 创建 API Key，返回明文密钥（仅此一次可见）。 */
    @Transactional
    public CreateResult create(
            String tenantId, String keyName, String scopes, Instant expiresAt, String operator) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        repository
                .findByTenantAndName(resolved, keyName)
                .ifPresent(
                        existing -> {
                            throw new BizException(
                                    ResultCode.CONFLICT, "api key name already exists");
                        });

        String rawKey = generateRawKey();
        String prefix = rawKey.substring(0, 8);
        String hash = sha256Hex(rawKey);

        repository.insert(
                resolved,
                keyName,
                prefix,
                hash,
                scopes == null || scopes.isBlank() ? "*" : scopes,
                expiresAt,
                operator);

        ApiKeyEntity entity =
                repository
                        .findByTenantAndName(resolved, keyName)
                        .orElseThrow(
                                () ->
                                        new BizException(
                                                ResultCode.SYSTEM_ERROR,
                                                "api key created but not found"));
        return new CreateResult(entity, rawKey);
    }

    @Transactional
    public void revoke(String tenantId, Long id, String operator) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        repository
                .findByTenantAndId(resolved, id)
                .orElseThrow(() -> new BizException(ResultCode.NOT_FOUND, "api key not found"));
        repository.revoke(resolved, id, operator);
    }

    private String generateRawKey() {
        byte[] bytes = new byte[RAW_KEY_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return "bk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record CreateResult(ApiKeyEntity entity, String rawKey) {}
}

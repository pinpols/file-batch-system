package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.EmptyValues;
import io.github.pinpols.batch.worker.dispatchs.mapper.DispatchSecretVersionMapper;

/** 依据实例固定的版本引用解析凭据，并在每次外发前验证该版本仍可用。 */
@Component
@RequiredArgsConstructor
public class DispatchSecretResolver {

    private static final Set<String> ALLOWED_CREDENTIAL_KEYS =
            Set.of("sftp_password", "smtp_username", "smtp_password", "api_push_api_key", "authorization");

    private final DispatchSecretVersionMapper secretVersionMapper;
    private final SecretPayloadProtector secretPayloadProtector;
    private final ObjectMapper objectMapper;

    public Set<String> resolveInto(
            String tenantId, String channelCode, Map<String, Object> payload, Map<String, Object> channelConfig) {
        Map<String, Object> bindings = map(payload.get(DispatchRuntimeKeys.DISPATCH_SECRET_BINDINGS));
        Map<String, Object> binding = map(bindings.get(channelCode));
        if (EmptyValues.isEmpty(binding)) {
            if (channelConfig.get("secret_ref") instanceof String secretRef && EmptyValues.hasText(secretRef)) {
                throw new DispatchSecretBlockedException("DISPATCH_SECRET_BINDING_MISSING");
            }
            return Set.of();
        }
        String pinnedSecretRef = String.valueOf(binding.getOrDefault("secretRef", ""));
        Object configuredSecretRef = channelConfig.get("secret_ref");
        if (!EmptyValues.hasText(pinnedSecretRef)
                || !(configuredSecretRef instanceof String ref)
                || !pinnedSecretRef.equals(ref)) {
            throw new DispatchSecretBlockedException("DISPATCH_SECRET_VERSION_INVALID");
        }
        Object rawId = binding.get("secretVersionId");
        if (rawId == null) {
            throw new DispatchSecretBlockedException("DISPATCH_SECRET_VERSION_MISSING");
        }
        Long secretVersionId;
        try {
            String secretVersionIdText = String.valueOf(rawId);
            secretVersionId = Long.valueOf(secretVersionIdText);
        } catch (NumberFormatException exception) {
            throw new DispatchSecretBlockedException("DISPATCH_SECRET_VERSION_INVALID");
        }
        String protectedPayload = secretVersionMapper.selectPayloadForDispatch(tenantId, secretVersionId);
        if (EmptyValues.isBlank(protectedPayload)) {
            throw new DispatchSecretBlockedException("DISPATCH_SECRET_REVOKED_OR_EXPIRED");
        }
        Map<String, Object> credentials;
        try {
            String revealedPayload = secretPayloadProtector.reveal(protectedPayload);
            credentials = parseCredentials(revealedPayload);
        } catch (RuntimeException exception) {
            throw new DispatchSecretBlockedException("DISPATCH_SECRET_PAYLOAD_INVALID");
        }
        if (!ALLOWED_CREDENTIAL_KEYS.containsAll(credentials.keySet())) {
            throw new DispatchSecretBlockedException("DISPATCH_SECRET_FIELD_NOT_ALLOWED");
        }
        if (EmptyValues.isEmpty(credentials)) {
            throw new DispatchSecretBlockedException("DISPATCH_SECRET_PAYLOAD_EMPTY");
        }
        channelConfig.putAll(credentials);
        return Set.copyOf(credentials.keySet());
    }

    private Map<String, Object> parseCredentials(String json) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception exception) {
            throw new DispatchSecretBlockedException("DISPATCH_SECRET_PAYLOAD_INVALID");
        }
    }

    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }
}

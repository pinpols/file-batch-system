package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.github.pinpols.batch.common.dto.FrozenDispatchChannelSnapshot;
import io.github.pinpols.batch.common.enums.ReplayOperationType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.EmptyValues;
import io.github.pinpols.batch.common.utils.JsonUtils;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 解析任务携带的精确受保护通道行，不读取更新后的行。 */
@Component
@RequiredArgsConstructor
public class FrozenDispatchChannelResolver {

    private static final String OPERATION_TYPE_KEY = "operationType";
    private static final JsonFactory PAYLOAD_JSON_FACTORY = new JsonFactory();

    private final SecretPayloadProtector secretPayloadProtector;

    public Optional<Map<String, Object>> resolve(String tenantId, String channelCode, String rawPayload) {
        PayloadSelection selection = selectPayloadFields(rawPayload);
        String protectedSnapshot = selection.snapshots().get(channelCode);
        if (EmptyValues.isBlank(protectedSnapshot)) {
            if (ReplayOperationType.WORKFLOW_RUN.code().equals(selection.operationType())) {
                throw invalidSnapshot();
            }
            if (EmptyValues.isEmpty(Objects.requireNonNull(selection.snapshots()))) {
                return Optional.empty();
            }
            throw invalidSnapshot();
        }
        Map<String, Object> channelConfig = reveal(protectedSnapshot, tenantId, channelCode);
        return Optional.of(channelConfig);
    }

    private Map<String, Object> reveal(String protectedSnapshot, String tenantId, String channelCode) {
        try {
            String plaintext = secretPayloadProtector.revealEncrypted(protectedSnapshot);
            FrozenDispatchChannelSnapshot snapshot =
                    JsonUtils.fromJsonStrict(plaintext, FrozenDispatchChannelSnapshot.class);
            if (snapshot == null) {
                throw invalidSnapshot();
            }
            snapshot.requireIdentity(tenantId, channelCode);
            return new LinkedHashMap<>(snapshot.configuration());
        } catch (RuntimeException invalidProtectedSnapshot) {
            throw invalidSnapshot();
        }
    }

    private PayloadSelection selectPayloadFields(String rawPayload) {
        if (EmptyValues.isBlank(rawPayload)) {
            return new PayloadSelection(null, Map.of());
        }
        String operationType = null;
        boolean operationTypeSeen = false;
        boolean snapshotsSeen = false;
        Map<String, String> snapshots = new LinkedHashMap<>();
        try (JsonParser parser = PAYLOAD_JSON_FACTORY.createParser(rawPayload)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return new PayloadSelection(null, Map.of());
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw invalidSnapshot();
                }
                String fieldName = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if (OPERATION_TYPE_KEY.equals(fieldName)) {
                    if (operationTypeSeen || valueToken != JsonToken.VALUE_STRING) {
                        throw invalidSnapshot();
                    }
                    operationTypeSeen = true;
                    operationType = parser.getText();
                } else if (FrozenDispatchChannelSnapshot.COLLECTION_KEY.equals(fieldName)) {
                    if (snapshotsSeen || valueToken != JsonToken.START_OBJECT) {
                        throw invalidSnapshot();
                    }
                    snapshotsSeen = true;
                    readSnapshots(parser, snapshots);
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                throw invalidSnapshot();
            }
            return new PayloadSelection(operationType, Map.copyOf(snapshots));
        } catch (IOException malformedPayload) {
            if (operationTypeSeen || snapshotsSeen) {
                throw invalidSnapshot();
            }
            return new PayloadSelection(null, Map.of());
        }
    }

    private void readSnapshots(JsonParser parser, Map<String, String> snapshots) throws IOException {
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                throw invalidSnapshot();
            }
            String channelCode = parser.currentName();
            if (EmptyValues.isBlank(channelCode) || parser.nextToken() != JsonToken.VALUE_STRING) {
                throw invalidSnapshot();
            }
            String snapshotText = parser.getText();
            if (EmptyValues.isBlank(snapshotText)) {
                throw invalidSnapshot();
            }
            String snapshotForInsert = parser.getText();
            boolean duplicateChannelSnapshot = snapshots.putIfAbsent(channelCode, snapshotForInsert) != null;
            if (duplicateChannelSnapshot) {
                throw invalidSnapshot();
            }
        }
    }

    private BizException invalidSnapshot() {
        return BizException.of(ResultCode.INVALID_ARGUMENT, "error.worker.frozen_dispatch_channel_invalid");
    }

    private record PayloadSelection(String operationType, Map<String, String> snapshots) {}
}

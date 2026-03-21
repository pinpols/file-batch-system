package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.common.utils.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LocalDispatchChannelAdapter implements DispatchChannelAdapter {

    private static final Set<String> SUPPORTED_TYPES = Set.of("LOCAL", "NAS", "OSS", "SFTP", "EMAIL");

    @Override
    public boolean supports(String channelType) {
        return channelType != null && SUPPORTED_TYPES.contains(channelType.toUpperCase());
    }

    @Override
    public DispatchResult dispatch(DispatchCommand command) {
        try {
            Map<String, Object> channelConfig = command.channelConfig();
            String receiptPolicy = String.valueOf(channelConfig.getOrDefault("receipt_policy", "NONE"));
            String externalRequestId = command.payload().externalRequestId() != null && !command.payload().externalRequestId().isBlank()
                    ? command.payload().externalRequestId()
                    : UUID.randomUUID().toString();
            String receiptCode = command.payload().receiptCode() != null && !command.payload().receiptCode().isBlank()
                    ? command.payload().receiptCode()
                    : "R-" + externalRequestId;
            boolean acknowledged = "NONE".equalsIgnoreCase(receiptPolicy) || "SYNC".equalsIgnoreCase(receiptPolicy);
            boolean pending = "ASYNC".equalsIgnoreCase(receiptPolicy) || "POLLING".equalsIgnoreCase(receiptPolicy);

            String endpoint = channelConfig.get("target_endpoint") == null
                    ? null
                    : String.valueOf(channelConfig.get("target_endpoint"));
            if (endpoint == null || endpoint.isBlank()) {
                endpoint = System.getProperty("java.io.tmpdir") + "/batch-dispatch-outbox";
            }
            Path directory = Path.of(endpoint);
            Files.createDirectories(directory);
            String channelCode = String.valueOf(channelConfig.getOrDefault("channel_code", "channel"));
            Path envelopePath = directory.resolve(channelCode + "-" + externalRequestId + ".json");

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("tenantId", command.tenantId());
            envelope.put("traceId", command.traceId());
            envelope.put("dispatchedAt", Instant.now().toString());
            envelope.put("channelType", channelConfig.get("channel_type"));
            envelope.put("dispatchTarget", command.payload().dispatchTarget());
            envelope.put("externalRequestId", externalRequestId);
            envelope.put("receiptCode", receiptCode);
            envelope.put("acknowledged", acknowledged);
            envelope.put("receiptPending", pending);
            envelope.put("fileRecord", command.fileRecord());
            envelope.put("payload", command.payload());
            Files.writeString(envelopePath, JsonUtils.toJson(envelope), StandardCharsets.UTF_8);

            return new DispatchResult(
                    true,
                    externalRequestId,
                    receiptCode,
                    acknowledged,
                    pending,
                    "dispatched via local adapter",
                    envelopePath.toString()
            );
        } catch (Exception ex) {
            return new DispatchResult(false, null, null, false, false, ex.getMessage(), null);
        }
    }
}

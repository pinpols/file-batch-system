package io.github.pinpols.batch.worker.core.support;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.github.pinpols.batch.common.dto.FrozenFileTemplateSnapshot;
import io.github.pinpols.batch.common.enums.ReplayOperationType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineDefinitionRepository;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Resolves a protected replay snapshot before considering ordinary runtime/live configuration. */
@Component
@RequiredArgsConstructor
public class FrozenFileTemplateResolver {

  private static final String OPERATION_TYPE_KEY = "operationType";
  private static final JsonFactory PAYLOAD_JSON_FACTORY = new JsonFactory();
  private final PlatformPipelineDefinitionRepository definitions;
  private final SecretPayloadProtector secretPayloadProtector;

  public Map<String, Object> resolve(FileTemplateConfigRequest request) {
    Map<String, Object> payload = parsePayload(request.rawPayload());
    Map<String, Object> attributes =
        EmptyChecks.isNull(request.attributes()) ? Map.of() : request.attributes();
    if (payload.containsKey(FrozenFileTemplateSnapshot.PAYLOAD_KEY)) {
      return reveal(payload.get(FrozenFileTemplateSnapshot.PAYLOAD_KEY), request);
    }
    if (attributes.containsKey(FrozenFileTemplateSnapshot.PAYLOAD_KEY)) {
      return reveal(attributes.get(FrozenFileTemplateSnapshot.PAYLOAD_KEY), request);
    }
    boolean wholeWorkflowReplay =
        ReplayOperationType.WORKFLOW_RUN.code().equals(payload.get(OPERATION_TYPE_KEY))
            || ReplayOperationType.WORKFLOW_RUN.code().equals(attributes.get(OPERATION_TYPE_KEY));
    if (wholeWorkflowReplay) {
      throw invalidSnapshot();
    }
    Object existing = attributes.get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (existing instanceof Map<?, ?> configuration && EmptyChecks.isNotEmpty(configuration)) {
      Map<String, Object> copy = new LinkedHashMap<>();
      configuration.forEach((key, value) -> {
        if (!(key instanceof String text)) {
          throw new IllegalArgumentException("file template configuration keys must be strings");
        }
        copy.put(text, value);
      });
      return copy;
    }
    if (EmptyChecks.isBlank(request.tenantId()) || EmptyChecks.isBlank(request.templateCode())) {
      return Map.of();
    }
    Map<String, Object> configuration = definitions.loadLatestTemplateConfig(
        request.tenantId(), request.templateCode(), request.templateType());
    return EmptyChecks.isNull(configuration) ? Map.of() : configuration;
  }

  private Map<String, Object> reveal(Object protectedValue, FileTemplateConfigRequest request) {
    try {
      if (!(protectedValue instanceof String ciphertext) || EmptyChecks.isBlank(ciphertext)) {
        throw invalidSnapshot();
      }
      String plaintext = secretPayloadProtector.revealEncrypted(ciphertext);
      FrozenFileTemplateSnapshot snapshot =
          JsonUtils.fromJsonStrict(plaintext, FrozenFileTemplateSnapshot.class);
      if (EmptyChecks.isNull(snapshot)) {
        throw invalidSnapshot();
      }
      snapshot.requireIdentity(request.tenantId(), request.templateCode(), request.templateType());
      return new LinkedHashMap<>(snapshot.configuration());
    } catch (RuntimeException invalidProtectedSnapshot) {
      // Never surface decrypted JSON or key material through stage errors or logs.
      throw invalidSnapshot();
    }
  }

  private BizException invalidSnapshot() {
    return BizException.of(
        ResultCode.INVALID_ARGUMENT, "error.worker.frozen_file_template_invalid");
  }

  private Map<String, Object> parsePayload(String rawPayload) {
    if (EmptyChecks.isBlank(rawPayload)) {
      return Map.of();
    }
    Map<String, Object> selectedFields = new LinkedHashMap<>();
    try (JsonParser parser = PAYLOAD_JSON_FACTORY.createParser(rawPayload)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        return Map.of();
      }
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        if (parser.currentToken() != JsonToken.FIELD_NAME) {
          throw invalidSnapshot();
        }
        String fieldName = parser.currentName();
        JsonToken valueToken = parser.nextToken();
        if (OPERATION_TYPE_KEY.equals(fieldName)
            || FrozenFileTemplateSnapshot.PAYLOAD_KEY.equals(fieldName)) {
          if (selectedFields.containsKey(fieldName)) {
            throw invalidSnapshot();
          }
          if (OPERATION_TYPE_KEY.equals(fieldName) && valueToken != JsonToken.VALUE_STRING) {
            throw invalidSnapshot();
          }
          String selectedValue = valueToken == JsonToken.VALUE_STRING ? parser.getText() : null;
          selectedFields.put(fieldName, selectedValue);
        }
        // Inline file content can be large. Do not materialize unrelated values a second time.
        parser.skipChildren();
      }
      if (EmptyChecks.isNotNull(parser.nextToken())) {
        throw invalidSnapshot();
      }
      return selectedFields;
    } catch (IOException invalidRawPayload) {
      if (EmptyChecks.isNotEmpty(selectedFields)) {
        throw invalidSnapshot();
      }
      // Existing typed stage contexts can supply their own payload when raw JSON is unavailable.
      return Map.of();
    }
  }
}

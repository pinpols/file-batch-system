package io.github.pinpols.batch.console.domain.audit.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Locale;
import java.util.Map;

/** Removes credentials from operation-audit parameter snapshots on both write and read paths. */
public final class AuditParamRedactor {

  static final String REDACTED = "[REDACTED]";

  private AuditParamRedactor() {}

  public static JsonNode toRedactedTree(ObjectMapper objectMapper, Object value) {
    JsonNode tree = objectMapper.valueToTree(value);
    redact(tree);
    return tree;
  }

  /**
   * Sanitizes persisted JSON before returning it to clients. Invalid historical payloads fail closed
   * instead of echoing unparsed text that may contain a credential.
   */
  public static String redactJson(ObjectMapper objectMapper, String json) {
    if (EmptyChecks.isBlank(json)) {
      return json;
    }
    try {
      JsonNode tree = objectMapper.readTree(json);
      redact(tree);
      return objectMapper.writeValueAsString(tree);
    } catch (JsonProcessingException ignored) {
      return "{\"_redacted\":true,\"_reason\":\"invalid_audit_params\"}";
    }
  }

  private static void redact(JsonNode node) {
    if (node instanceof ObjectNode objectNode) {
      for (Map.Entry<String, JsonNode> field : objectNode.properties()) {
        if (isSensitiveField(field.getKey())) {
          field.setValue(TextNode.valueOf(REDACTED));
        } else {
          redact(field.getValue());
        }
      }
      return;
    }
    if (node instanceof ArrayNode arrayNode) {
      arrayNode.forEach(AuditParamRedactor::redact);
    }
  }

  private static boolean isSensitiveField(String fieldName) {
    String normalized =
        fieldName.replace("_", "").replace("-", "").replace(".", "").toLowerCase(Locale.ROOT);
    return normalized.contains("password")
        || normalized.contains("secret")
        || normalized.contains("credential")
        || normalized.contains("privatekey")
        || normalized.equals("apikey")
        || normalized.equals("plaintext")
        || normalized.contains("ciphertext")
        || normalized.endsWith("token");
  }
}

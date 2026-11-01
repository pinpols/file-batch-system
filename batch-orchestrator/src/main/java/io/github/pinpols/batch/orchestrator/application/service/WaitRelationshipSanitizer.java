package io.github.pinpols.batch.orchestrator.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.query.WaitRelationshipRow;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 仅输出调度器快照的已知安全字段；损坏内容和未知原因不能进入 Console。 */
@Component
@RequiredArgsConstructor
public class WaitRelationshipSanitizer {
  private static final int MAX_SNAPSHOT_LENGTH = 524288;
  private static final int MAX_DEPENDENCIES = 5000;
  private static final Set<String> CALENDAR_FIELDS = Set.of("upstreamCode", "rule");
  private static final Set<String> JOB_FIELDS =
      Set.of("upstreamJobCode", "bizDateAlignment", "upstreamBizDate", "resolutionError");
  private static final Set<String> REASONS = Set.of(
      "BLOCKED_BY_UPSTREAM_CALENDAR",
      "UPSTREAM_NOT_EFFECTIVE",
      "DEPENDENCY_SNAPSHOT_INVALID",
      "DEPENDENCY_CALENDAR_UNAVAILABLE",
      "DEPENDENCY_CALENDAR_HAS_NO_PRIOR_WORKDAY",
      "CAPACITY_LIMIT",
      "occurrence-already-terminated",
      "readiness-window-exceeded",
      "fired-by-trigger");
  private static final Set<String> RESOLUTION_ERRORS =
      Set.of("DEPENDENCY_CALENDAR_UNAVAILABLE", "DEPENDENCY_CALENDAR_HAS_NO_PRIOR_WORKDAY");
  private final ObjectMapper json;

  public void sanitize(WaitRelationshipRow row) {
    row.setReason(safeReason(row.getReason(), REASONS));
    if ("CAPACITY_ADMISSION".equals(row.getWaitType())) {
      row.setDependencySnapshot(null);
      row.setDependencySnapshotValid(true);
      return;
    }
    try {
      String value = row.getDependencySnapshot();
      if (EmptyChecks.isNull(value) || value.length() > MAX_SNAPSHOT_LENGTH)
        throw new IllegalArgumentException();
      String safe =
          switch (row.getWaitType()) {
            case "CALENDAR" -> sanitizeJson(value, true, row.getBizDate());
            case "JOB" -> sanitizeJson(value, false, row.getBizDate());
            case "READINESS" -> sanitizeReadiness(value);
            default -> throw new IllegalArgumentException();
          };
      row.setDependencySnapshot(safe);
      row.setDependencySnapshotValid(true);
    } catch (IllegalArgumentException | JsonProcessingException ex) {
      row.setDependencySnapshot(null);
      row.setDependencySnapshotValid(false);
    }
  }

  private String sanitizeJson(String raw, boolean calendar, LocalDate bizDate)
      throws JsonProcessingException {
    JsonNode parsed =
        json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
    if (EmptyChecks.isNull(parsed) || !parsed.isArray()) throw new IllegalArgumentException();
    boolean emptyArray = parsed.isEmpty(); // empty-check: allow - Jackson JsonNode 不是集合
    if (emptyArray || parsed.size() > MAX_DEPENDENCIES) throw new IllegalArgumentException();
    ArrayNode safe = json.createArrayNode();
    for (JsonNode dependency : parsed) {
      if (!dependency.isObject()) throw new IllegalArgumentException();
      Set<String> allowed = calendar ? CALENDAR_FIELDS : JOB_FIELDS;
      dependency.fieldNames().forEachRemaining(field -> {
        if (!allowed.contains(field)) throw new IllegalArgumentException();
      });
      ObjectNode item = safe.addObject();
      if (calendar) {
        item.put("upstreamCode", code(dependency.get("upstreamCode")));
        String rule = code(dependency.get("rule"));
        if (!Set.of("WAIT_SETTLED", "WAIT_CUTOFF", "SAME_DAY_PARALLEL").contains(rule))
          throw new IllegalArgumentException();
        item.put("rule", rule);
      } else {
        item.put("upstreamJobCode", code(dependency.get("upstreamJobCode")));
        String alignment = code(dependency.get("bizDateAlignment"));
        if (!Set.of("SAME_DAY", "PREVIOUS_BIZ_DAY").contains(alignment))
          throw new IllegalArgumentException();
        item.put("bizDateAlignment", alignment);
        JsonNode date = dependency.get("upstreamBizDate");
        LocalDate upstreamDate = null;
        if (EmptyChecks.isNotNull(date) && !date.isNull()) {
          String dateText = code(date);
          try {
            upstreamDate = LocalDate.parse(dateText);
          } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(ex);
          }
          item.put("upstreamBizDate", dateText);
        }
        JsonNode error = dependency.get("resolutionError");
        String safeError = null;
        if (EmptyChecks.isNotNull(error) && !error.isNull()) {
          safeError = safeReason(code(error), RESOLUTION_ERRORS);
          if (EmptyChecks.isNull(safeError)) throw new IllegalArgumentException();
          item.put("resolutionError", safeError);
        }
        if (EmptyChecks.isNull(bizDate)) throw new IllegalArgumentException();
        if ("SAME_DAY".equals(alignment)) {
          if (EmptyChecks.isNotNull(safeError)
              || (EmptyChecks.isNotNull(upstreamDate) && !bizDate.equals(upstreamDate)))
            throw new IllegalArgumentException();
        } else if (EmptyChecks.isNull(upstreamDate) == EmptyChecks.isNull(safeError)
            || (EmptyChecks.isNotNull(upstreamDate) && !upstreamDate.isBefore(bizDate))) {
          throw new IllegalArgumentException();
        }
      }
    }
    return json.writeValueAsString(safe);
  }

  private static String sanitizeReadiness(String raw) {
    List<String> codes = List.of(raw.split(",", -1));
    if (EmptyChecks.isEmpty(codes) || codes.size() > MAX_DEPENDENCIES)
      throw new IllegalArgumentException();
    return codes.stream()
        .map(WaitRelationshipSanitizer::code)
        .collect(java.util.stream.Collectors.joining(","));
  }

  private static String code(JsonNode node) {
    if (EmptyChecks.isNull(node) || !node.isTextual()) throw new IllegalArgumentException();
    return code(node.textValue());
  }

  private static String code(String value) {
    if (EmptyChecks.isBlank(value)
        || value.length() > 128
        || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
    return value;
  }

  private static String safeReason(String value, Set<String> allowed) {
    if (EmptyChecks.isNull(value)) return null;
    int colon = value.indexOf(':');
    String code = colon < 0 ? value : value.substring(0, colon);
    return allowed.contains(code) ? code : null;
  }
}

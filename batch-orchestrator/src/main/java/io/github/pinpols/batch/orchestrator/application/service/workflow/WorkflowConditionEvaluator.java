package io.github.pinpols.batch.orchestrator.application.service.workflow;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class WorkflowConditionEvaluator {

  private static final Object UNRESOLVED = new Object();

  /**
   * 支持的最小语法： `&&` / `||` / `!` / 括号； `=` `==` `!=` `>` `>=` `<` `<=`； `in` / `not in` / `contains`
   * / `startsWith` / `endsWith`。
   */
  @SuppressWarnings("unchecked")
  public boolean matches(String conditionExpr, String payloadJson) {
    // P2-4 fail-closed：CONDITION 边的 condition_expr 为空属配置错误。
    // 旧行为 "blank → true" 让空表达式静默退化成 ALWAYS，无法区分用户意图。
    // 现在抛出 BizException，让运维在静态校验 / 配置 save 期间就发现问题。
    if (conditionExpr == null || conditionExpr.isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.workflow.condition_expr_blank");
    }
    Map<String, Object> payload = Collections.emptyMap();
    if (payloadJson != null && !payloadJson.isBlank()) {
      try {
        Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
        if (payloadObject instanceof Map<?, ?> payloadMap) {
          payload = (Map<String, Object>) payloadMap;
        }
      } catch (IllegalArgumentException ignored) {
        SwallowedExceptionLogger.info(
            WorkflowConditionEvaluator.class, "catch:IllegalArgumentException", ignored);

        payload = Collections.emptyMap();
      }
    }
    String trimmedExpression = conditionExpr.trim();
    String normalizedExpression = stripOuterParentheses(trimmedExpression);
    return evaluateExpression(normalizedExpression, payload);
  }

  private boolean evaluateExpression(String expression, Map<String, Object> payload) {
    String trimmedExpression = expression.trim();
    String normalized = stripOuterParentheses(trimmedExpression);
    if (normalized.isBlank()) {
      return true;
    }
    List<String> orParts = splitTopLevel(normalized, "||", " OR ");
    if (orParts.size() > 1) {
      return orParts.stream().anyMatch(part -> evaluateExpression(part, payload));
    }
    List<String> andParts = splitTopLevel(normalized, "&&", " AND ");
    if (andParts.size() > 1) {
      return andParts.stream().allMatch(part -> evaluateExpression(part, payload));
    }
    if (normalized.startsWith("!")) {
      String negatedExpression = normalized.substring(1);
      return !evaluateExpression(negatedExpression, payload);
    }
    return evaluateAtomicExpression(normalized, payload);
  }

  private boolean evaluateAtomicExpression(String expression, Map<String, Object> payload) {
    AtomicOperator operator = AtomicOperator.find(expression);
    if (operator == null) {
      Object value = resolveLeftOperand(expression, payload);
      return isTruthy(value);
    }
    String leftOperandText = expression.substring(0, operator.index());
    String leftToken = leftOperandText.trim();
    String rightOperandText =
        expression.substring(operator.index() + operator.symbol().length());
    String rightToken = rightOperandText.trim();
    Object leftValue = resolveLeftOperand(leftToken, payload);
    return switch (operator.type()) {
      case EQ -> {
        Object rightValue = resolveRightOperand(rightToken, payload);
        yield compareEquals(leftValue, rightValue);
      }
      case NE -> {
        Object rightValue = resolveRightOperand(rightToken, payload);
        yield !compareEquals(leftValue, rightValue);
      }
      case GT -> {
        Object rightValue = resolveRightOperand(rightToken, payload);
        yield compareComparable(leftValue, rightValue) > 0;
      }
      case GE -> {
        Object rightValue = resolveRightOperand(rightToken, payload);
        yield compareComparable(leftValue, rightValue) >= 0;
      }
      case LT -> {
        Object rightValue = resolveRightOperand(rightToken, payload);
        yield compareComparable(leftValue, rightValue) < 0;
      }
      case LE -> {
        Object rightValue = resolveRightOperand(rightToken, payload);
        yield compareComparable(leftValue, rightValue) <= 0;
      }
      case IN -> evaluateIn(leftValue, rightToken, payload, false);
      case NOT_IN -> evaluateIn(leftValue, rightToken, payload, true);
      case CONTAINS -> {
        Object rightValue = resolveRightOperand(rightToken, payload);
        yield evaluateContains(leftValue, rightValue);
      }
      case STARTS_WITH -> {
        String leftText = stringify(leftValue);
        Object rightValue = resolveRightOperand(rightToken, payload);
        String rightText = stringify(rightValue);
        yield leftText.startsWith(rightText);
      }
      case ENDS_WITH -> {
        String leftText = stringify(leftValue);
        Object rightValue = resolveRightOperand(rightToken, payload);
        String rightText = stringify(rightValue);
        yield leftText.endsWith(rightText);
      }
    };
  }

  private boolean evaluateIn(
      Object leftValue, String rightToken, Map<String, Object> payload, boolean negate) {
    List<Object> candidates = parseListLiteral(rightToken, payload);
    boolean contains =
        candidates.stream().anyMatch(candidate -> compareEquals(leftValue, candidate));
    return negate ? !contains : contains;
  }

  private boolean evaluateContains(Object leftValue, Object rightValue) {
    if (leftValue instanceof String leftString) {
      String rightText = stringify(rightValue);
      return leftString.contains(rightText);
    }
    if (leftValue instanceof Collection<?> collection) {
      return collection.stream().anyMatch(item -> compareEquals(item, rightValue));
    }
    return false;
  }

  private int compareComparable(Object leftValue, Object rightValue) {
    BigDecimal leftNumber = toNumber(leftValue);
    BigDecimal rightNumber = toNumber(rightValue);
    if (leftNumber != null && rightNumber != null) {
      return leftNumber.compareTo(rightNumber);
    }
    String leftText = stringify(leftValue);
    String rightText = stringify(rightValue);
    return leftText.compareTo(rightText);
  }

  private boolean compareEquals(Object leftValue, Object rightValue) {
    BigDecimal leftNumber = toNumber(leftValue);
    BigDecimal rightNumber = toNumber(rightValue);
    if (leftNumber != null && rightNumber != null) {
      return leftNumber.compareTo(rightNumber) == 0;
    }
    Object normalizedLeftValue = normalizeValue(leftValue);
    Object normalizedRightValue = normalizeValue(rightValue);
    return Objects.equals(normalizedLeftValue, normalizedRightValue);
  }

  private Object resolveLeftOperand(String token, Map<String, Object> payload) {
    Object literal = resolveLiteralOperand(token, payload);
    if (literal != UNRESOLVED) {
      return literal;
    }
    String trimmedPath = token.trim();
    String normalizedPath = stripOuterParentheses(trimmedPath);
    return readPath(payload, normalizedPath);
  }

  private Object resolveRightOperand(String token, Map<String, Object> payload) {
    Object literalValue = resolveLiteralOperand(token, payload);
    if (literalValue != UNRESOLVED) {
      return literalValue;
    }
    String trimmedToken = token.trim();
    String value = stripOuterParentheses(trimmedToken);
    Object payloadValue = readPath(payload, value);
    return payloadValue == null ? value : payloadValue;
  }

  private Object resolveLiteralOperand(String token, Map<String, Object> payload) {
    String trimmedToken = token.trim();
    String value = stripOuterParentheses(trimmedToken);
    if (value.isBlank()) {
      return null;
    }
    if (isQuoted(value)) {
      return normalizeLiteral(value);
    }
    if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
      return Boolean.parseBoolean(value);
    }
    if ("null".equalsIgnoreCase(value)) {
      return null;
    }
    if (looksLikeNumber(value)) {
      return toNumber(value);
    }
    if (value.startsWith("[") && value.endsWith("]")) {
      return parseListLiteral(value, payload);
    }
    return UNRESOLVED;
  }

  private List<Object> parseListLiteral(String token, Map<String, Object> payload) {
    String value = token.trim();
    if (value.startsWith("[") && value.endsWith("]")) {
      value = value.substring(1, value.length() - 1);
    }
    if (value.isBlank()) {
      return List.of();
    }
    List<String> items = splitListItems(value);
    List<Object> result = new ArrayList<>(items.size());
    for (String item : items) {
      Object resolvedItem = resolveRightOperand(item, payload);
      result.add(resolvedItem);
    }
    return result;
  }

  private List<String> splitListItems(String value) {
    List<String> items = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    boolean inSingleQuote = false;
    boolean inDoubleQuote = false;
    for (int index = 0; index < value.length(); index++) {
      char ch = value.charAt(index);
      if (ch == '\'' && !inDoubleQuote) {
        inSingleQuote = !inSingleQuote;
      } else if (ch == '"' && !inSingleQuote) {
        inDoubleQuote = !inDoubleQuote;
      } else if (!inSingleQuote && !inDoubleQuote) {
        if (ch == '[' || ch == '(') {
          depth++;
        } else if (ch == ']' || ch == ')') {
          depth--;
        } else if (ch == ',' && depth == 0) {
          String rawItem = current.toString();
          String item = rawItem.trim();
          items.add(item);
          current.setLength(0);
          continue;
        }
      }
      current.append(ch);
    }
    if (!current.isEmpty()) {
      String rawItem = current.toString();
      String item = rawItem.trim();
      items.add(item);
    }
    return items;
  }

  private List<String> splitTopLevel(String expression, String... operators) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    boolean inSingleQuote = false;
    boolean inDoubleQuote = false;
    for (int index = 0; index < expression.length(); index++) {
      char ch = expression.charAt(index);
      if (ch == '\'' && !inDoubleQuote) {
        inSingleQuote = !inSingleQuote;
      } else if (ch == '"' && !inSingleQuote) {
        inDoubleQuote = !inDoubleQuote;
      }
      if (!inSingleQuote && !inDoubleQuote) {
        if (ch == '(') {
          depth++;
        } else if (ch == ')') {
          depth--;
        }
        if (depth == 0) {
          String matchedOperator = matchOperator(expression, index, operators);
          if (matchedOperator != null) {
            String rawPart = current.toString();
            String part = rawPart.trim();
            parts.add(part);
            current.setLength(0);
            index += matchedOperator.length() - 1;
            continue;
          }
        }
      }
      current.append(ch);
    }
    if (!current.isEmpty()) {
      String rawPart = current.toString();
      String part = rawPart.trim();
      parts.add(part);
    }
    return parts;
  }

  private String matchOperator(String expression, int index, String... operators) {
    for (String operator : operators) {
      if (index + operator.length() > expression.length()) {
        continue;
      }
      String candidate = expression.substring(index, index + operator.length());
      if (candidate.equals(operator) || candidate.equalsIgnoreCase(operator)) {
        return operator;
      }
    }
    return null;
  }

  private boolean isTruthy(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean booleanValue) {
      return booleanValue;
    }
    if (value instanceof Number number) {
      return number.doubleValue() != 0D;
    }
    if (value instanceof Collection<?> collection) {
      return !collection.isEmpty();
    }
    String truthinessText = value.toString();
    if (EmptyChecks.isBlank(Objects.requireNonNull(truthinessText))) {
      return false;
    }
    String falseCheckText = value.toString();
    return !"false".equalsIgnoreCase(falseCheckText);
  }

  private Object normalizeValue(Object value) {
    if (value instanceof String stringValue) {
      return stringValue.trim();
    }
    return value;
  }

  private String stringify(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private BigDecimal toNumber(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      String numericText = n.toString();
      return new BigDecimal(numericText);
    }
    String text = String.valueOf(value);
    // fast-path:非数字格式直接返 null,避免 NumberFormatException 噪音日志
    // (条件 mode == 'fast' 时每个字符都会被试探,造成日志量过大)
    if (!looksLikeNumber(text)) {
      return null;
    }
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException exception) {
      SwallowedExceptionLogger.info(
          WorkflowConditionEvaluator.class, "catch:NumberFormatException", exception);
      return null;
    }
  }

  private boolean looksLikeNumber(String value) {
    return value.matches("-?\\d+(\\.\\d+)?");
  }

  private boolean isQuoted(String value) {
    return (value.startsWith("\"") && value.endsWith("\""))
        || (value.startsWith("'") && value.endsWith("'"));
  }

  private String stripOuterParentheses(String expression) {
    String value = expression;
    while (value.startsWith("(") && value.endsWith(")") && isWrappedBySinglePair(value)) {
      String innerExpression = value.substring(1, value.length() - 1);
      value = innerExpression.trim();
    }
    return value;
  }

  private boolean isWrappedBySinglePair(String expression) {
    int depth = 0;
    boolean inSingleQuote = false;
    boolean inDoubleQuote = false;
    for (int index = 0; index < expression.length(); index++) {
      char ch = expression.charAt(index);
      if (ch == '\'' && !inDoubleQuote) {
        inSingleQuote = !inSingleQuote;
      } else if (ch == '"' && !inSingleQuote) {
        inDoubleQuote = !inDoubleQuote;
      }
      if (inSingleQuote || inDoubleQuote) {
        continue;
      }
      if (ch == '(') {
        depth++;
      } else if (ch == ')') {
        depth--;
      }
      if (depth == 0 && index < expression.length() - 1) {
        return false;
      }
    }
    return depth == 0;
  }

  @SuppressWarnings("unchecked")
  private Object readPath(Map<String, Object> payload, String keyPath) {
    if (payload == null || keyPath == null || keyPath.isBlank()) {
      return null;
    }
    Object current = payload;
    for (String segment : keyPath.split("\\.")) {
      if (!(current instanceof Map<?, ?> currentMap)) {
        return null;
      }
      current = ((Map<String, Object>) currentMap).get(segment);
    }
    return current;
  }

  private String normalizeLiteral(String literal) {
    String value = literal;
    boolean isQuotedLiteral = (value.startsWith("\"") && value.endsWith("\""))
        || (value.startsWith("'") && value.endsWith("'"));
    if (isQuotedLiteral) {
      value = value.substring(1, value.length() - 1);
    }
    return value;
  }

  private enum OperatorType {
    EQ,
    NE,
    GT,
    GE,
    LT,
    LE,
    IN,
    NOT_IN,
    CONTAINS,
    STARTS_WITH,
    ENDS_WITH
  }

  private record AtomicOperator(OperatorType type, String symbol, int index) {
    private static AtomicOperator find(String expression) {
      String[] orderedOperators = {
        " not in ",
        " startsWith ",
        " endsWith ",
        " contains ",
        ">=",
        "<=",
        "!=",
        "==",
        " in ",
        ">",
        "<",
        "="
      };
      for (String operator : orderedOperators) {
        int index = indexOfTopLevel(expression, operator);
        if (index >= 0) {
          OperatorType operatorType = resolveType(operator);
          return new AtomicOperator(operatorType, operator, index);
        }
      }
      return null;
    }

    private static int indexOfTopLevel(String expression, String operator) {
      int depth = 0;
      boolean inSingleQuote = false;
      boolean inDoubleQuote = false;
      for (int index = 0; index <= expression.length() - operator.length(); index++) {
        char ch = expression.charAt(index);
        if (ch == '\'' && !inDoubleQuote) {
          inSingleQuote = !inSingleQuote;
        } else if (ch == '"' && !inSingleQuote) {
          inDoubleQuote = !inDoubleQuote;
        }
        if (inSingleQuote || inDoubleQuote) {
          continue;
        }
        if (ch == '(') {
          depth++;
        } else if (ch == ')') {
          depth--;
        }
        if (depth == 0) {
          String candidate = expression.substring(index, index + operator.length());
          if (candidate.equals(operator)) {
            return index;
          }
        }
      }
      return -1;
    }

    private static OperatorType resolveType(String operator) {
      return switch (operator.trim()) {
        case "=", "==" -> OperatorType.EQ;
        case "!=" -> OperatorType.NE;
        case ">" -> OperatorType.GT;
        case ">=" -> OperatorType.GE;
        case "<" -> OperatorType.LT;
        case "<=" -> OperatorType.LE;
        case "in" -> OperatorType.IN;
        case "not in" -> OperatorType.NOT_IN;
        case "contains" -> OperatorType.CONTAINS;
        case "startsWith" -> OperatorType.STARTS_WITH;
        case "endsWith" -> OperatorType.ENDS_WITH;
        default -> throw new IllegalArgumentException("Unsupported operator: " + operator);
      };
    }
  }
}

package com.example.batch.orchestrator.application.service.workflow;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.JsonUtils;
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
    if (conditionExpr == null || conditionExpr.isBlank()) {
      return true;
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
    return evaluateExpression(stripOuterParentheses(conditionExpr.trim()), payload);
  }

  private boolean evaluateExpression(String expression, Map<String, Object> payload) {
    String normalized = stripOuterParentheses(expression.trim());
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
      return !evaluateExpression(normalized.substring(1), payload);
    }
    return evaluateAtomicExpression(normalized, payload);
  }

  private boolean evaluateAtomicExpression(String expression, Map<String, Object> payload) {
    AtomicOperator operator = findAtomicOperator(expression);
    if (operator == null) {
      Object value = resolveLeftOperand(expression, payload);
      return isTruthy(value);
    }
    String leftToken = expression.substring(0, operator.index()).trim();
    String rightToken = expression.substring(operator.index() + operator.symbol().length()).trim();
    Object leftValue = resolveLeftOperand(leftToken, payload);
    return switch (operator.type()) {
      case EQ -> compareEquals(leftValue, resolveRightOperand(rightToken, payload));
      case NE -> !compareEquals(leftValue, resolveRightOperand(rightToken, payload));
      case GT -> compareComparable(leftValue, resolveRightOperand(rightToken, payload)) > 0;
      case GE -> compareComparable(leftValue, resolveRightOperand(rightToken, payload)) >= 0;
      case LT -> compareComparable(leftValue, resolveRightOperand(rightToken, payload)) < 0;
      case LE -> compareComparable(leftValue, resolveRightOperand(rightToken, payload)) <= 0;
      case IN -> evaluateIn(leftValue, rightToken, payload, false);
      case NOT_IN -> evaluateIn(leftValue, rightToken, payload, true);
      case CONTAINS -> evaluateContains(leftValue, resolveRightOperand(rightToken, payload));
      case STARTS_WITH ->
          stringify(leftValue).startsWith(stringify(resolveRightOperand(rightToken, payload)));
      case ENDS_WITH ->
          stringify(leftValue).endsWith(stringify(resolveRightOperand(rightToken, payload)));
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
      return leftString.contains(stringify(rightValue));
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
    return stringify(leftValue).compareTo(stringify(rightValue));
  }

  private boolean compareEquals(Object leftValue, Object rightValue) {
    BigDecimal leftNumber = toNumber(leftValue);
    BigDecimal rightNumber = toNumber(rightValue);
    if (leftNumber != null && rightNumber != null) {
      return leftNumber.compareTo(rightNumber) == 0;
    }
    return Objects.equals(normalizeValue(leftValue), normalizeValue(rightValue));
  }

  private Object resolveLeftOperand(String token, Map<String, Object> payload) {
    Object literal = resolveLiteralOperand(token, payload);
    if (literal != UNRESOLVED) {
      return literal;
    }
    return readPath(payload, stripOuterParentheses(token.trim()));
  }

  private Object resolveRightOperand(String token, Map<String, Object> payload) {
    Object literalValue = resolveLiteralOperand(token, payload);
    if (literalValue != UNRESOLVED) {
      return literalValue;
    }
    String value = stripOuterParentheses(token.trim());
    Object payloadValue = readPath(payload, value);
    return payloadValue == null ? value : payloadValue;
  }

  private Object resolveLiteralOperand(String token, Map<String, Object> payload) {
    String value = stripOuterParentheses(token.trim());
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
      result.add(resolveRightOperand(item, payload));
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
          items.add(current.toString().trim());
          current.setLength(0);
          continue;
        }
      }
      current.append(ch);
    }
    if (!current.isEmpty()) {
      items.add(current.toString().trim());
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
            parts.add(current.toString().trim());
            current.setLength(0);
            index += matchedOperator.length() - 1;
            continue;
          }
        }
      }
      current.append(ch);
    }
    if (!current.isEmpty()) {
      parts.add(current.toString().trim());
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

  private AtomicOperator findAtomicOperator(String expression) {
    return AtomicOperator.find(expression);
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
    return !value.toString().isBlank() && !"false".equalsIgnoreCase(value.toString());
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
    try {
      return new BigDecimal(String.valueOf(value));
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
      value = value.substring(1, value.length() - 1).trim();
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
    if ((value.startsWith("\"") && value.endsWith("\""))
        || (value.startsWith("'") && value.endsWith("'"))) {
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
          return new AtomicOperator(resolveType(operator), operator, index);
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

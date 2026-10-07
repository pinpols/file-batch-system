package io.github.pinpols.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowConditionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("流程条件求值器: 比较运算, 逻辑组合与取值路径解析口径")
class WorkflowConditionEvaluatorTest {

  private WorkflowConditionEvaluator evaluator;

  @BeforeEach
  void setUp() {
    evaluator = new WorkflowConditionEvaluator();
  }

  // --- blank / null condition (P2-4 fail-closed) ---

  @Test
  @DisplayName("条件表达式为空时抛出业务异常并提示表达式缺失")
  void shouldThrowWhenConditionIsNull() {
    // 旧行为 blank → true 已改为 fail-closed：CONDITION 边必须配置非空表达式
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> evaluator.matches(null, "{}"))
        .isInstanceOf(io.github.pinpols.batch.common.exception.BizException.class)
        .hasMessageContaining("condition_expr_blank");
  }

  @Test
  @DisplayName("条件表达式为空白时抛出业务异常并提示表达式缺失")
  void shouldThrowWhenConditionIsBlank() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> evaluator.matches("  ", "{}"))
        .isInstanceOf(io.github.pinpols.batch.common.exception.BizException.class)
        .hasMessageContaining("condition_expr_blank");
  }

  // --- equality ---

  @Test
  @DisplayName("单等号比较相等时命中, 不相等时不命中")
  void shouldMatchEqualityWithEqualsOperator() {
    assertThat(evaluator.matches("status = 'SUCCESS'", "{\"status\":\"SUCCESS\"}"))
        .isTrue();
    assertThat(evaluator.matches("status = 'SUCCESS'", "{\"status\":\"FAILED\"}"))
        .isFalse();
  }

  @Test
  @DisplayName("双等号写法同样按相等比较命中")
  void shouldMatchEqualityWithDoubleEquals() {
    assertThat(evaluator.matches("status == 'SUCCESS'", "{\"status\":\"SUCCESS\"}"))
        .isTrue();
  }

  @Test
  @DisplayName("不等比较在取值不同时命中, 相同时不命中")
  void shouldMatchNotEquals() {
    assertThat(evaluator.matches("status != 'FAILED'", "{\"status\":\"SUCCESS\"}"))
        .isTrue();
    assertThat(evaluator.matches("status != 'FAILED'", "{\"status\":\"FAILED\"}"))
        .isFalse();
  }

  // --- numeric comparison ---

  @Test
  @DisplayName("数值大于比较按取值命中或不命中")
  void shouldMatchNumericGreaterThan() {
    assertThat(evaluator.matches("count > 5", "{\"count\":10}")).isTrue();
    assertThat(evaluator.matches("count > 5", "{\"count\":3}")).isFalse();
  }

  @Test
  @DisplayName("数值小于等于比较按取值命中或不命中")
  void shouldMatchNumericLessThanOrEqual() {
    assertThat(evaluator.matches("count <= 5", "{\"count\":5}")).isTrue();
    assertThat(evaluator.matches("count <= 5", "{\"count\":6}")).isFalse();
  }

  @Test
  @DisplayName("数值大于等于比较按取值命中或不命中")
  void shouldMatchNumericGreaterThanOrEqual() {
    assertThat(evaluator.matches("count >= 10", "{\"count\":10}")).isTrue();
    assertThat(evaluator.matches("count >= 10", "{\"count\":9}")).isFalse();
  }

  @Test
  @DisplayName("数值小于比较按取值命中或不命中")
  void shouldMatchNumericLessThan() {
    assertThat(evaluator.matches("count < 10", "{\"count\":5}")).isTrue();
    assertThat(evaluator.matches("count < 10", "{\"count\":10}")).isFalse();
  }

  // --- logical operators ---

  @Test
  @DisplayName("与运算要求两侧同时成立才命中")
  void shouldMatchAndCondition() {
    assertThat(evaluator.matches(
            "status = 'SUCCESS' && code = 'JOB1'", "{\"status\":\"SUCCESS\",\"code\":\"JOB1\"}"))
        .isTrue();
    assertThat(evaluator.matches(
            "status = 'SUCCESS' && code = 'JOB1'", "{\"status\":\"SUCCESS\",\"code\":\"JOB2\"}"))
        .isFalse();
  }

  @Test
  @DisplayName("或运算任一侧成立即命中")
  void shouldMatchOrCondition() {
    assertThat(evaluator.matches(
            "status = 'SUCCESS' || status = 'PARTIAL_FAILED'", "{\"status\":\"PARTIAL_FAILED\"}"))
        .isTrue();
    assertThat(evaluator.matches(
            "status = 'SUCCESS' || status = 'PARTIAL_FAILED'", "{\"status\":\"FAILED\"}"))
        .isFalse();
  }

  @Test
  @DisplayName("取反运算对布尔字段反向判定")
  void shouldMatchNegation() {
    assertThat(evaluator.matches("!flag", "{\"flag\":false}")).isTrue();
    assertThat(evaluator.matches("!flag", "{\"flag\":true}")).isFalse();
  }

  // --- in / not in ---

  @Test
  @DisplayName("集合包含运算在取值属于集合时命中")
  void shouldMatchInOperator() {
    assertThat(
            evaluator.matches("status in ['SUCCESS','PARTIAL_FAILED']", "{\"status\":\"SUCCESS\"}"))
        .isTrue();
    assertThat(
            evaluator.matches("status in ['SUCCESS','PARTIAL_FAILED']", "{\"status\":\"FAILED\"}"))
        .isFalse();
  }

  @Test
  @DisplayName("集合排除运算在取值不属于集合时命中")
  void shouldMatchNotInOperator() {
    assertThat(
            evaluator.matches("status not in ['FAILED','CANCELLED']", "{\"status\":\"SUCCESS\"}"))
        .isTrue();
    assertThat(evaluator.matches("status not in ['FAILED','CANCELLED']", "{\"status\":\"FAILED\"}"))
        .isFalse();
  }

  // --- contains / startsWith / endsWith ---

  @Test
  @DisplayName("字符串包含子串时命中, 不包含时不命中")
  void shouldMatchContainsOnString() {
    assertThat(evaluator.matches(
            "message contains 'error'", "{\"message\":\"file parse error occurred\"}"))
        .isTrue();
    assertThat(evaluator.matches("message contains 'error'", "{\"message\":\"all good\"}"))
        .isFalse();
  }

  @Test
  @DisplayName("字符串以指定前缀开头时命中")
  void shouldMatchStartsWith() {
    assertThat(evaluator.matches("code startsWith 'JOB'", "{\"code\":\"JOB_001\"}"))
        .isTrue();
    assertThat(evaluator.matches("code startsWith 'JOB'", "{\"code\":\"TASK_001\"}"))
        .isFalse();
  }

  @Test
  @DisplayName("字符串以指定后缀结尾时命中")
  void shouldMatchEndsWith() {
    assertThat(evaluator.matches("filename endsWith '.csv'", "{\"filename\":\"data.csv\"}"))
        .isTrue();
    assertThat(evaluator.matches("filename endsWith '.csv'", "{\"filename\":\"data.json\"}"))
        .isFalse();
  }

  // --- nested path resolution ---

  @Test
  @DisplayName("按层级路径取到嵌套字段后完成比较")
  void shouldResolveNestedPath() {
    assertThat(evaluator.matches("result.status = 'OK'", "{\"result\":{\"status\":\"OK\"}}"))
        .isTrue();
    assertThat(evaluator.matches("result.status = 'OK'", "{\"result\":{\"status\":\"FAIL\"}}"))
        .isFalse();
  }

  // --- parentheses grouping ---

  @Test
  @DisplayName("括号改变运算优先级时按组合逻辑求值")
  void shouldHandleParenthesesGrouping() {
    String expr = "(status = 'SUCCESS' || status = 'PARTIAL_FAILED') && retryCount < 3";
    assertThat(evaluator.matches(expr, "{\"status\":\"PARTIAL_FAILED\",\"retryCount\":2}"))
        .isTrue();
    assertThat(evaluator.matches(expr, "{\"status\":\"FAILED\",\"retryCount\":2}"))
        .isFalse();
  }

  // --- literal boolean and null ---

  @Test
  @DisplayName("布尔字面量比较按取值命中或不命中")
  void shouldHandleBooleanLiteral() {
    assertThat(evaluator.matches("enabled = true", "{\"enabled\":true}")).isTrue();
    assertThat(evaluator.matches("enabled = true", "{\"enabled\":false}")).isFalse();
  }

  @Test
  @DisplayName("载荷中缺少表达式引用的字段时判定不命中")
  void shouldReturnFalseWhenPayloadKeyMissing() {
    assertThat(evaluator.matches("status = 'SUCCESS'", "{}")).isFalse();
  }

  @Test
  @DisplayName("非空判断在字段有值时命中, 字段缺失时不命中")
  void shouldMatchNotNullPayloadField() {
    assertThat(evaluator.matches("bizDate != null", "{\"bizDate\":\"2026-09-25\"}"))
        .isTrue();
    assertThat(evaluator.matches("bizDate != null", "{}")).isFalse();
  }

  @Test
  @DisplayName("载荷为空或格式非法时判定不命中而不抛异常")
  void shouldHandleNullOrInvalidPayloadJson() {
    assertThat(evaluator.matches("status = 'SUCCESS'", null)).isFalse();
    assertThat(evaluator.matches("status = 'SUCCESS'", "not-json")).isFalse();
  }
}

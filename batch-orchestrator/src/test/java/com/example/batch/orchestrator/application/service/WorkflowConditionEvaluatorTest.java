package com.example.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowConditionEvaluatorTest {

  private WorkflowConditionEvaluator evaluator;

  @BeforeEach
  void setUp() {
    evaluator = new WorkflowConditionEvaluator();
  }

  // --- blank / null condition ---

  @Test
  void shouldReturnTrueWhenConditionIsNull() {
    assertThat(evaluator.matches(null, "{}")).isTrue();
  }

  @Test
  void shouldReturnTrueWhenConditionIsBlank() {
    assertThat(evaluator.matches("  ", "{}")).isTrue();
  }

  // --- equality ---

  @Test
  void shouldMatchEqualityWithEqualsOperator() {
    assertThat(evaluator.matches("status = 'SUCCESS'", "{\"status\":\"SUCCESS\"}")).isTrue();
    assertThat(evaluator.matches("status = 'SUCCESS'", "{\"status\":\"FAILED\"}")).isFalse();
  }

  @Test
  void shouldMatchEqualityWithDoubleEquals() {
    assertThat(evaluator.matches("status == 'SUCCESS'", "{\"status\":\"SUCCESS\"}")).isTrue();
  }

  @Test
  void shouldMatchNotEquals() {
    assertThat(evaluator.matches("status != 'FAILED'", "{\"status\":\"SUCCESS\"}")).isTrue();
    assertThat(evaluator.matches("status != 'FAILED'", "{\"status\":\"FAILED\"}")).isFalse();
  }

  // --- numeric comparison ---

  @Test
  void shouldMatchNumericGreaterThan() {
    assertThat(evaluator.matches("count > 5", "{\"count\":10}")).isTrue();
    assertThat(evaluator.matches("count > 5", "{\"count\":3}")).isFalse();
  }

  @Test
  void shouldMatchNumericLessThanOrEqual() {
    assertThat(evaluator.matches("count <= 5", "{\"count\":5}")).isTrue();
    assertThat(evaluator.matches("count <= 5", "{\"count\":6}")).isFalse();
  }

  @Test
  void shouldMatchNumericGreaterThanOrEqual() {
    assertThat(evaluator.matches("count >= 10", "{\"count\":10}")).isTrue();
    assertThat(evaluator.matches("count >= 10", "{\"count\":9}")).isFalse();
  }

  @Test
  void shouldMatchNumericLessThan() {
    assertThat(evaluator.matches("count < 10", "{\"count\":5}")).isTrue();
    assertThat(evaluator.matches("count < 10", "{\"count\":10}")).isFalse();
  }

  // --- logical operators ---

  @Test
  void shouldMatchAndCondition() {
    assertThat(
            evaluator.matches(
                "status = 'SUCCESS' && code = 'JOB1'",
                "{\"status\":\"SUCCESS\",\"code\":\"JOB1\"}"))
        .isTrue();
    assertThat(
            evaluator.matches(
                "status = 'SUCCESS' && code = 'JOB1'",
                "{\"status\":\"SUCCESS\",\"code\":\"JOB2\"}"))
        .isFalse();
  }

  @Test
  void shouldMatchOrCondition() {
    assertThat(
            evaluator.matches(
                "status = 'SUCCESS' || status = 'PARTIAL_FAILED'",
                "{\"status\":\"PARTIAL_FAILED\"}"))
        .isTrue();
    assertThat(
            evaluator.matches(
                "status = 'SUCCESS' || status = 'PARTIAL_FAILED'", "{\"status\":\"FAILED\"}"))
        .isFalse();
  }

  @Test
  void shouldMatchNegation() {
    assertThat(evaluator.matches("!flag", "{\"flag\":false}")).isTrue();
    assertThat(evaluator.matches("!flag", "{\"flag\":true}")).isFalse();
  }

  // --- in / not in ---

  @Test
  void shouldMatchInOperator() {
    assertThat(
            evaluator.matches("status in ['SUCCESS','PARTIAL_FAILED']", "{\"status\":\"SUCCESS\"}"))
        .isTrue();
    assertThat(
            evaluator.matches("status in ['SUCCESS','PARTIAL_FAILED']", "{\"status\":\"FAILED\"}"))
        .isFalse();
  }

  @Test
  void shouldMatchNotInOperator() {
    assertThat(
            evaluator.matches("status not in ['FAILED','CANCELLED']", "{\"status\":\"SUCCESS\"}"))
        .isTrue();
    assertThat(evaluator.matches("status not in ['FAILED','CANCELLED']", "{\"status\":\"FAILED\"}"))
        .isFalse();
  }

  // --- contains / startsWith / endsWith ---

  @Test
  void shouldMatchContainsOnString() {
    assertThat(
            evaluator.matches(
                "message contains 'error'", "{\"message\":\"file parse error occurred\"}"))
        .isTrue();
    assertThat(evaluator.matches("message contains 'error'", "{\"message\":\"all good\"}"))
        .isFalse();
  }

  @Test
  void shouldMatchStartsWith() {
    assertThat(evaluator.matches("code startsWith 'JOB'", "{\"code\":\"JOB_001\"}")).isTrue();
    assertThat(evaluator.matches("code startsWith 'JOB'", "{\"code\":\"TASK_001\"}")).isFalse();
  }

  @Test
  void shouldMatchEndsWith() {
    assertThat(evaluator.matches("filename endsWith '.csv'", "{\"filename\":\"data.csv\"}"))
        .isTrue();
    assertThat(evaluator.matches("filename endsWith '.csv'", "{\"filename\":\"data.json\"}"))
        .isFalse();
  }

  // --- nested path resolution ---

  @Test
  void shouldResolveNestedPath() {
    assertThat(evaluator.matches("result.status = 'OK'", "{\"result\":{\"status\":\"OK\"}}"))
        .isTrue();
    assertThat(evaluator.matches("result.status = 'OK'", "{\"result\":{\"status\":\"FAIL\"}}"))
        .isFalse();
  }

  // --- parentheses grouping ---

  @Test
  void shouldHandleParenthesesGrouping() {
    String expr = "(status = 'SUCCESS' || status = 'PARTIAL_FAILED') && retryCount < 3";
    assertThat(evaluator.matches(expr, "{\"status\":\"PARTIAL_FAILED\",\"retryCount\":2}"))
        .isTrue();
    assertThat(evaluator.matches(expr, "{\"status\":\"FAILED\",\"retryCount\":2}")).isFalse();
  }

  // --- literal boolean and null ---

  @Test
  void shouldHandleBooleanLiteral() {
    assertThat(evaluator.matches("enabled = true", "{\"enabled\":true}")).isTrue();
    assertThat(evaluator.matches("enabled = true", "{\"enabled\":false}")).isFalse();
  }

  @Test
  void shouldReturnFalseWhenPayloadKeyMissing() {
    assertThat(evaluator.matches("status = 'SUCCESS'", "{}")).isFalse();
  }

  @Test
  void shouldHandleNullOrInvalidPayloadJson() {
    assertThat(evaluator.matches("status = 'SUCCESS'", null)).isFalse();
    assertThat(evaluator.matches("status = 'SUCCESS'", "not-json")).isFalse();
  }
}

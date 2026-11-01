package io.github.pinpols.batch.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure.Kind;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("接口异常处理器: 各类响应状态异常到统一业务响应码的映射与兜底口径")
class OrchestratorApiExceptionHandlerTest {

  private final OrchestratorApiExceptionHandler handler =
      OrchestratorApiExceptionHandler.forStandaloneTest();

  @Test
  @DisplayName("用例失败保持现有 HTTP 状态与响应码，内部原因不作为对外消息")
  void shouldMapApplicationFailuresToExistingWireContract() {
    Map<Kind, Integer> statuses = Map.of(
        Kind.INVALID_INPUT, 400,
        Kind.NOT_FOUND, 404,
        Kind.CONFLICT, 409,
        Kind.INVALID_CONFIGURATION, 422,
        Kind.RATE_LIMITED, 429,
        Kind.TEMPORARILY_UNAVAILABLE, 503);
    Map<Kind, ResultCode> codes = Map.of(
        Kind.INVALID_INPUT, ResultCode.INVALID_ARGUMENT,
        Kind.NOT_FOUND, ResultCode.NOT_FOUND,
        Kind.CONFLICT, ResultCode.CONFLICT,
        Kind.INVALID_CONFIGURATION, ResultCode.SYSTEM_ERROR,
        Kind.RATE_LIMITED, ResultCode.RATE_LIMITED,
        Kind.TEMPORARILY_UNAVAILABLE, ResultCode.SYSTEM_ERROR);
    statuses.forEach((kind, status) -> {
      IllegalStateException cause = new IllegalStateException("database cause");
      OperationFailure failure = new OperationFailure(kind, "internal diagnostic", cause);
      ResponseEntity<CommonResponse<Void>> response = handler.handleOperationFailure(failure);
      assertThat(failure.getCause()).isSameAs(cause);
      assertThat(response.getStatusCode().value()).isEqualTo(status);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().code()).isEqualTo(codes.get(kind));
      assertThat(response.getBody().message()).isEqualTo(codes.get(kind).label());
    });
  }

  @Test
  @DisplayName("触发限流时映射为限流响应码,不得降级成系统错误")
  void shouldMapTooManyRequestsToRateLimited() {
    // LaunchApplicationService 限流抛 429,不能落 default 被降级成 SYSTEM_ERROR。
    ResponseEntity<CommonResponse<Void>> response = handler.handleResponseStatus(
        new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "launch rate limit exceeded"));

    assertThat(response.getStatusCode().value()).isEqualTo(429);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ResultCode.RATE_LIMITED);
  }

  @Test
  @DisplayName("请求参数不合法时映射为参数错误响应码")
  void shouldMapBadRequestToInvalidArgument() {
    ResponseEntity<CommonResponse<Void>> response =
        handler.handleResponseStatus(new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody().code()).isEqualTo(ResultCode.INVALID_ARGUMENT);
    assertThat(response.getBody().message()).isEqualTo(ResultCode.INVALID_ARGUMENT.label());
  }

  @Test
  @DisplayName("未认证与无权限分别映射为对应的认证与权限响应码")
  void shouldMapUnauthorizedAndForbidden() {
    ResponseEntity<CommonResponse<Void>> unauthorized =
        handler.handleResponseStatus(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no"));
    assertThat(unauthorized.getStatusCode().value()).isEqualTo(401);
    assertThat(unauthorized.getBody().code()).isEqualTo(ResultCode.UNAUTHORIZED);

    ResponseEntity<CommonResponse<Void>> forbidden =
        handler.handleResponseStatus(new ResponseStatusException(HttpStatus.FORBIDDEN, "nope"));
    assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
    assertThat(forbidden.getBody().code()).isEqualTo(ResultCode.FORBIDDEN);
  }

  @Test
  @DisplayName("资源不存在与状态冲突分别映射为对应的不存在与冲突响应码")
  void shouldMapNotFoundAndConflict() {
    ResponseEntity<CommonResponse<Void>> notFound =
        handler.handleResponseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND, "gone"));
    assertThat(notFound.getStatusCode().value()).isEqualTo(404);
    assertThat(notFound.getBody().code()).isEqualTo(ResultCode.NOT_FOUND);

    ResponseEntity<CommonResponse<Void>> conflict =
        handler.handleResponseStatus(new ResponseStatusException(HttpStatus.CONFLICT, "clash"));
    assertThat(conflict.getStatusCode().value()).isEqualTo(409);
    assertThat(conflict.getBody().code()).isEqualTo(ResultCode.CONFLICT);
  }

  @Test
  @DisplayName("迟到的 Worker claim 保持 404 响应并按预期拒绝记录 INFO")
  void shouldClassifyUnavailableTaskClaimAsExpectedInfo() {
    BizException unavailableClaim =
        BizException.of(ResultCode.NOT_FOUND, "error.task.claim_not_available", "42");
    BizException unrelatedNotFound =
        BizException.of(ResultCode.NOT_FOUND, "error.job.definition_not_found");

    ResponseEntity<CommonResponse<Void>> response = handler.handleBizException(unavailableClaim);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ResultCode.NOT_FOUND);
    assertThat(handler.logBizExceptionAtInfo(unavailableClaim)).isTrue();
    assertThat(handler.logBizExceptionAtInfo(unrelatedNotFound)).isFalse();
  }

  @Test
  @DisplayName("未覆盖的响应状态统一兜底为系统错误响应码")
  void shouldFallbackToSystemErrorForUnmappedStatus() {
    ResponseEntity<CommonResponse<Void>> response = handler.handleResponseStatus(
        new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream"));

    assertThat(response.getStatusCode().value()).isEqualTo(502);
    assertThat(response.getBody().code()).isEqualTo(ResultCode.SYSTEM_ERROR);
  }
}

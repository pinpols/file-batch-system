package com.example.batch.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class OrchestratorApiExceptionHandlerTest {

  private final OrchestratorApiExceptionHandler handler =
      OrchestratorApiExceptionHandler.forStandaloneTest();

  @Test
  void shouldMapTooManyRequestsToRateLimited() {
    // LaunchApplicationService 限流抛 429,不能落 default 被降级成 SYSTEM_ERROR。
    ResponseEntity<CommonResponse<Void>> response =
        handler.handleResponseStatus(
            new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "launch rate limit exceeded"));

    assertThat(response.getStatusCode().value()).isEqualTo(429);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ResultCode.RATE_LIMITED);
  }

  @Test
  void shouldMapBadRequestToInvalidArgument() {
    ResponseEntity<CommonResponse<Void>> response =
        handler.handleResponseStatus(new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody().code()).isEqualTo(ResultCode.INVALID_ARGUMENT);
  }

  @Test
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
  void shouldFallbackToSystemErrorForUnmappedStatus() {
    ResponseEntity<CommonResponse<Void>> response =
        handler.handleResponseStatus(
            new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream"));

    assertThat(response.getStatusCode().value()).isEqualTo(502);
    assertThat(response.getBody().code()).isEqualTo(ResultCode.SYSTEM_ERROR);
  }
}

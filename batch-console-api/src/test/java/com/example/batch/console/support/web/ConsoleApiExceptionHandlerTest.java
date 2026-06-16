package com.example.batch.console.support.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link ConsoleApiExceptionHandler} 的 status/code 映射单测,聚焦两条审查修复:
 *
 * <ul>
 *   <li>DataIntegrityViolation 按约束类型分流:UNIQUE/FK → 409,CHECK/NOT_NULL → 400。
 *   <li>下游 body 不可解析时,按真实 HTTP status 反查 ResultCode,不再一律 SYSTEM_ERROR。
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ConsoleApiExceptionHandlerTest {

  @Mock private ConsoleResponseFactory responseFactory;

  private ConsoleApiExceptionHandler handler() {
    // responseFactory 直接产出带 code 的 CommonResponse,便于断言映射后的 code。
    lenient()
        .when(responseFactory.failure(any(), any()))
        .thenAnswer(
            inv ->
                CommonResponse.failure(
                    inv.getArgument(0, ResultCode.class), inv.getArgument(1, String.class)));
    return new ConsoleApiExceptionHandler(responseFactory, null);
  }

  private static DataIntegrityViolationException divFrom(String rootMessage) {
    return new DataIntegrityViolationException("wrapper", new RuntimeException(rootMessage));
  }

  @SuppressWarnings("unchecked")
  private static CommonResponse<Void> bodyOf(ResponseEntity<?> entity) {
    return (CommonResponse<Void>) entity.getBody();
  }

  @Test
  void shouldReturn409Conflict_whenUniqueConstraintViolation() {
    ResponseEntity<?> response =
        handler()
            .handleDataIntegrityViolation(
                divFrom(
                    "ERROR: duplicate key value violates unique constraint \"uk_tenant_code\""));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.CONFLICT);
  }

  @Test
  void shouldReturn409Conflict_whenForeignKeyConstraintViolation() {
    ResponseEntity<?> response =
        handler()
            .handleDataIntegrityViolation(
                divFrom(
                    "ERROR: update or delete on table \"parent\" violates foreign key constraint"
                        + " \"fk_child_parent\" on table \"child\""));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.CONFLICT);
  }

  @Test
  void shouldReturn400Validation_whenCheckConstraintViolation() {
    ResponseEntity<?> response =
        handler()
            .handleDataIntegrityViolation(
                divFrom("ERROR: new row violates check constraint \"ck_amount_positive\""));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.VALIDATION_ERROR);
  }

  @Test
  void shouldReturn400Validation_whenNotNullConstraintViolation() {
    ResponseEntity<?> response =
        handler()
            .handleDataIntegrityViolation(
                divFrom("ERROR: null value in column \"name\" violates not-null constraint"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.VALIDATION_ERROR);
  }

  @Test
  void shouldReturn400Validation_whenUnknownConstraintViolation() {
    ResponseEntity<?> response =
        handler().handleDataIntegrityViolation(divFrom("ERROR: some unrecognized integrity error"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.VALIDATION_ERROR);
  }

  @Test
  void shouldMapDownstream409ToConflict_whenBodyUnparseable() {
    ResponseEntity<?> response =
        handler().handleDownstreamRestError(restError(HttpStatus.CONFLICT, "not-json"));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.CONFLICT);
  }

  @Test
  void shouldMapDownstream404ToNotFound_whenBodyUnparseable() {
    ResponseEntity<?> response =
        handler().handleDownstreamRestError(restError(HttpStatus.NOT_FOUND, "<<garbage>>"));

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.NOT_FOUND);
  }

  @Test
  void shouldFallBackToSystemError_whenDownstream500WithUnparseableBody() {
    ResponseEntity<?> response =
        handler()
            .handleDownstreamRestError(
                restError(HttpStatus.INTERNAL_SERVER_ERROR, "boom not json"));

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.SYSTEM_ERROR);
  }

  private static RestClientResponseException restError(HttpStatus status, String body) {
    return new RestClientResponseException(
        "downstream error",
        status.value(),
        status.getReasonPhrase(),
        new HttpHeaders(),
        body.getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }
}

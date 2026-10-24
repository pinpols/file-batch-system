package io.github.pinpols.batch.console.support.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.exception.SystemException;
import io.github.pinpols.batch.common.i18n.BizMessageResolver;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * {@link ConsoleApiExceptionHandler} 的 status/code 映射单测,聚焦两条审查修复:
 *
 * <ul>
 *   <li>DataIntegrityViolation 按约束类型分流:UNIQUE/FK → 409,CHECK/NOT_NULL → 400。
 *   <li>下游 body 不可解析时,按真实 HTTP status 反查 ResultCode,不再一律 SYSTEM_ERROR。
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("控制台接口异常处理器: 约束冲突分流,下游错误映射与本地化文案")
class ConsoleApiExceptionHandlerTest {

  @Test
  @DisplayName("已终结任务的迟到 claim 按 INFO 记录，其他业务异常仍按 WARN 记录")
  void shouldLogUnavailableClaimAtInfoAndOtherBusinessFailuresAtWarn() {
    Logger logger = (Logger) LoggerFactory.getLogger(ConsoleApiExceptionHandler.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      ConsoleApiExceptionHandler handler = handler();
      handler.handleBizException(
          BizException.of(ResultCode.NOT_FOUND, "error.task.claim_not_available", "296"));
      handler.handleBizException(BizException.of(ResultCode.CONFLICT, "error.test.conflict"));

      assertThat(appender.list)
          .extracting(ILoggingEvent::getLevel)
          .containsExactly(Level.INFO, Level.WARN);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Mock
  private ConsoleResponseFactory responseFactory;

  private ConsoleApiExceptionHandler handler() {
    // responseFactory 直接产出带 code 的 CommonResponse,便于断言映射后的 code。
    lenient()
        .when(responseFactory.failure(any(), any()))
        .thenAnswer(inv -> CommonResponse.failure(
            inv.getArgument(0, ResultCode.class), inv.getArgument(1, String.class)));
    return new ConsoleApiExceptionHandler(responseFactory, null);
  }

  private ConsoleApiExceptionHandler localizedHandler() {
    lenient()
        .when(responseFactory.failure(any(), any()))
        .thenAnswer(inv -> CommonResponse.failure(
            inv.getArgument(0, ResultCode.class), inv.getArgument(1, String.class)));
    ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasename("messages");
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    return new ConsoleApiExceptionHandler(responseFactory, new BizMessageResolver(source));
  }

  private static DataIntegrityViolationException divFrom(String rootMessage) {
    return new DataIntegrityViolationException("wrapper", new RuntimeException(rootMessage));
  }

  @SuppressWarnings("unchecked")
  private static CommonResponse<Void> bodyOf(ResponseEntity<?> entity) {
    return (CommonResponse<Void>) entity.getBody();
  }

  @Test
  @DisplayName("唯一约束冲突时,返回 409 与冲突业务码")
  void shouldReturn409Conflict_whenUniqueConstraintViolation() {
    ResponseEntity<?> response = handler()
        .handleDataIntegrityViolation(
            divFrom("ERROR: duplicate key value violates unique constraint \"uk_tenant_code\""));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.CONFLICT);
  }

  @Test
  @DisplayName("外键约束冲突时,返回 409 与冲突业务码")
  void shouldReturn409Conflict_whenForeignKeyConstraintViolation() {
    ResponseEntity<?> response = handler()
        .handleDataIntegrityViolation(
            divFrom("ERROR: update or delete on table \"parent\" violates foreign key constraint"
                + " \"fk_child_parent\" on table \"child\""));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.CONFLICT);
  }

  @Test
  @DisplayName("检查约束冲突时,返回 400 与参数校验错误码")
  void shouldReturn400Validation_whenCheckConstraintViolation() {
    ResponseEntity<?> response = handler()
        .handleDataIntegrityViolation(
            divFrom("ERROR: new row violates check constraint \"ck_amount_positive\""));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.VALIDATION_ERROR);
  }

  @Test
  @DisplayName("依赖环数据库兜底返回稳定参数错误码")
  void shouldReturnStableInvalidArgument_whenJobDependencyCycleConstraintViolation() {
    ServerErrorMessage serverError = new ServerErrorMessage(
        "SERROR\u0000C23514\u0000Mjob dependency graph contains a cycle\u0000"
            + "sbatch\u0000tjob_dependency\u0000nck_job_dependency_acyclic\u0000\u0000");
    ResponseEntity<?> response = handler()
        .handleDataIntegrityViolation(
            new DataIntegrityViolationException("wrapper", new PSQLException(serverError)));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.INVALID_ARGUMENT);
    assertThat(bodyOf(response).message()).isEqualTo("参数非法");
  }

  @Test
  @DisplayName("expectation日历复合外键竞态返回稳定状态冲突")
  void shouldReturnStableStateConflict_whenWorkflowCalendarExpectationFkRejectsRace() {
    ServerErrorMessage serverError = new ServerErrorMessage(
        "SERROR\u0000C23503\u0000Mworkflow calendar has recurring expectation definitions\u0000"
            + "sbatch\u0000texternal_event_expectation_definition"
            + "\u0000nfk_external_event_expectation_workflow_calendar\u0000\u0000");
    ResponseEntity<?> response = handler()
        .handleDataIntegrityViolation(
            new DataIntegrityViolationException("wrapper", new PSQLException(serverError)));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.STATE_CONFLICT);
    assertThat(bodyOf(response).message()).isEqualTo(ResultCode.STATE_CONFLICT.label());
  }

  @Test
  @DisplayName("Workflow 业务日历冲突按当前语言返回文案")
  void shouldLocalizeWorkflowCalendarExpectationConflict() {
    ServerErrorMessage serverError = new ServerErrorMessage(
        "SERROR\u0000C23503\u0000Mworkflow calendar has recurring expectation definitions\u0000"
            + "sbatch\u0000texternal_event_expectation_definition"
            + "\u0000nfk_external_event_expectation_workflow_calendar\u0000\u0000");
    Locale previous = LocaleContextHolder.getLocale();
    try {
      LocaleContextHolder.setLocale(Locale.ENGLISH);
      assertThat(bodyOf(localizedHandler()
                  .handleDataIntegrityViolation(new DataIntegrityViolationException(
                      "wrapper", new PSQLException(serverError))))
              .message())
          .isEqualTo(
              "Workflow calendar cannot change while recurring file-group or external-event expectations are configured; remove those definitions first.");
      LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
      assertThat(bodyOf(localizedHandler()
                  .handleDataIntegrityViolation(new DataIntegrityViolationException(
                      "wrapper", new PSQLException(serverError))))
              .message())
          .isEqualTo("工作流已配置文件组或外部事件预期，暂不能更换业务日历；请先删除相关预期定义。");
    } finally {
      LocaleContextHolder.setLocale(previous);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "23503, CONFLICT, 409",
    "23505, CONFLICT, 409",
    "23514, VALIDATION_ERROR, 400",
    "23502, VALIDATION_ERROR, 400"
  })
  @DisplayName("本地化数据库文案按SQLSTATE识别且删除被引用上游仍返回409")
  void shouldClassifyLocalizedConstraintByState(String state, ResultCode code, int status) {
    ServerErrorMessage serverError =
        new ServerErrorMessage("SERROR\u0000C" + state + "\u0000M本地化数据库拒绝\u0000"
            + "sbatch\u0000tjob_dependency\u0000nfk_job_dependency_upstream\u0000\u0000");
    ResponseEntity<?> response = handler()
        .handleDataIntegrityViolation(
            new DataIntegrityViolationException("wrapper", new PSQLException(serverError)));

    assertThat(response.getStatusCode().value()).isEqualTo(status);
    assertThat(bodyOf(response).code()).isEqualTo(code);
  }

  @ParameterizedTest
  @CsvSource({"other_schema, job_dependency", "batch, other_table"})
  @DisplayName("其它表或Schema的同名约束不误报Job依赖环")
  void shouldNotClassifyUnrelatedConstraintAsDependencyCycle(String schema, String table) {
    ServerErrorMessage serverError =
        new ServerErrorMessage("SERROR\u0000C23514\u0000Mck_job_dependency_acyclic\u0000s" + schema
            + "\u0000t" + table + "\u0000nck_job_dependency_acyclic\u0000\u0000");
    ResponseEntity<?> response = handler()
        .handleDataIntegrityViolation(
            new DataIntegrityViolationException("wrapper", new PSQLException(serverError)));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.VALIDATION_ERROR);
  }

  @Test
  @DisplayName("非空约束冲突时,返回 400 与参数校验错误码")
  void shouldReturn400Validation_whenNotNullConstraintViolation() {
    ResponseEntity<?> response = handler()
        .handleDataIntegrityViolation(
            divFrom("ERROR: null value in column \"name\" violates not-null constraint"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.VALIDATION_ERROR);
  }

  @Test
  @DisplayName("无法识别的完整性约束错误时,按参数校验错误处理")
  void shouldReturn400Validation_whenUnknownConstraintViolation() {
    ResponseEntity<?> response =
        handler().handleDataIntegrityViolation(divFrom("ERROR: some unrecognized integrity error"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.VALIDATION_ERROR);
  }

  @Test
  @DisplayName("下游返回冲突状态且响应体无法解析时,仍映射为冲突错误")
  void shouldMapDownstream409ToConflict_whenBodyUnparseable() {
    ResponseEntity<?> response =
        handler().handleDownstreamRestError(restError(HttpStatus.CONFLICT, "not-json"));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.CONFLICT);
  }

  @Test
  @DisplayName("下游返回资源不存在且响应体无法解析时,仍映射为未找到")
  void shouldMapDownstream404ToNotFound_whenBodyUnparseable() {
    ResponseEntity<?> response =
        handler().handleDownstreamRestError(restError(HttpStatus.NOT_FOUND, "<<garbage>>"));

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.NOT_FOUND);
  }

  @Test
  @DisplayName("下游返回服务端错误且响应体无法解析时,回退为系统错误")
  void shouldFallBackToSystemError_whenDownstream500WithUnparseableBody() {
    ResponseEntity<?> response = handler()
        .handleDownstreamRestError(restError(HttpStatus.INTERNAL_SERVER_ERROR, "boom not json"));

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.SYSTEM_ERROR);
    assertThat(bodyOf(response).message()).isEqualTo(ResultCode.SYSTEM_ERROR.label());
  }

  @Test
  @DisplayName("下游不可解析的响应正文不透传到控制台 API")
  void shouldNotExposeUnparseableDownstreamBody() {
    String sensitiveBody = "password=top-secret";

    ResponseEntity<?> response = handler()
        .handleDownstreamRestError(restError(HttpStatus.INTERNAL_SERVER_ERROR, sensitiveBody));

    assertThat(bodyOf(response).message()).doesNotContain("top-secret");
    assertThat(bodyOf(response).message()).isEqualTo(ResultCode.SYSTEM_ERROR.label());
  }

  @Test
  @DisplayName("下游系统错误的响应文案不透传内部细节")
  void shouldReplaceDownstreamServerErrorMessageWithDefault() {
    String sensitiveBody = "{\"code\":\"SYSTEM_ERROR\",\"message\":\"password=top-secret\"}";

    ResponseEntity<?> response = handler()
        .handleDownstreamRestError(restError(HttpStatus.INTERNAL_SERVER_ERROR, sensitiveBody));

    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.SYSTEM_ERROR);
    assertThat(bodyOf(response).message()).isEqualTo(ResultCode.SYSTEM_ERROR.label());
    assertThat(bodyOf(response).message()).doesNotContain("top-secret");
  }

  @Test
  @DisplayName("下游客户端错误继续透传业务文案")
  void shouldPreserveDownstreamClientErrorMessage() {
    ResponseEntity<?> response = handler()
        .handleDownstreamRestError(restError(
            HttpStatus.BAD_REQUEST,
            "{\"code\":\"INVALID_ARGUMENT\",\"message\":\"invalid business date\"}"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.INVALID_ARGUMENT);
    assertThat(bodyOf(response).message()).isEqualTo("invalid business date");
  }

  @Test
  @DisplayName("请求参数类型转换失败时,返回 400 与参数非法错误码")
  void shouldReturn400InvalidArgument_whenRequestParameterTypeMismatch() {
    ResponseEntity<?> response = handler()
        .handleTypeMismatch(
            new MethodArgumentTypeMismatchException("1", LocalDate.class, "bizDate", null, null),
            new MockHttpServletRequest("GET", "/api/console/asset-partitions/readiness"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(bodyOf(response).code()).isEqualTo(ResultCode.INVALID_ARGUMENT);
  }

  @Test
  @DisplayName("请求格式异常使用当前语言的通用错误文案")
  void shouldLocalizeFrameworkValidationMessagesInsteadOfExposingRawExceptionText() {
    Locale previous = LocaleContextHolder.getLocale();
    try {
      LocaleContextHolder.setLocale(Locale.ENGLISH);
      ResponseEntity<?> response = localizedHandler()
          .handleTypeMismatch(
              new MethodArgumentTypeMismatchException("1", LocalDate.class, "bizDate", null, null),
              new MockHttpServletRequest("GET", "/api/console/asset-partitions/readiness"));
      assertThat(bodyOf(response).message()).isEqualTo("invalid argument");
    } finally {
      LocaleContextHolder.setLocale(previous);
    }
  }

  @Test
  @DisplayName("系统异常向客户端返回本地化通用文案而不暴露内部消息")
  void shouldLocalizeSystemExceptionAndHideInternalMessage() {
    Locale previous = LocaleContextHolder.getLocale();
    try {
      LocaleContextHolder.setLocale(Locale.ENGLISH);
      ResponseEntity<?> response =
          localizedHandler().handleSystemException(new SystemException("database password leaked"));

      assertThat(bodyOf(response).message()).isEqualTo("system error");
      assertThat(bodyOf(response).message()).doesNotContain("password");
    } finally {
      LocaleContextHolder.setLocale(previous);
    }
  }

  @Test
  @DisplayName("业务异常文案按当前语言环境渲染,中英文各取对应文本")
  void shouldRenderBizExceptionMessageForCurrentLocale() {
    BizException exception =
        BizException.of(ResultCode.NOT_FOUND, "error.tenant.already_exists", "acme");
    Locale previous = LocaleContextHolder.getLocale();
    try {
      LocaleContextHolder.setLocale(Locale.ENGLISH);
      assertThat(bodyOf(localizedHandler().handleBizException(exception)).message())
          .isEqualTo("tenant already exists: acme");

      LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
      assertThat(bodyOf(localizedHandler().handleBizException(exception)).message())
          .isEqualTo("租户已存在:acme");
    } finally {
      LocaleContextHolder.setLocale(previous);
    }
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

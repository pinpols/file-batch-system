package com.example.batch.console.support.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.exception.SystemException;
import com.example.batch.common.i18n.BizMessageResolver;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.service.ConsoleResponseFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Tolerate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Console API 统一异常映射：把 Spring / Jakarta Validation / 业务异常翻译为 {@code CommonResponse} 并挑 HTTP status。
 *
 * <p>按语义归为 4 组分支：
 *
 * <ul>
 *   <li><b>业务/系统自定义异常</b>（{@code BizException} / {@code SystemException}）：直接用 {@code
 *       ResultCode.httpStatus()} 定 HTTP status，WARN/ERROR 分级 log。
 *   <li><b>请求格式/参数错误</b>（{@code MethodArgumentNotValidException} / {@code
 *       ConstraintViolationException} / {@code MissingRequestHeaderException} / {@code
 *       MissingServletRequestParameterException} / {@code HttpMessageNotReadableException} / {@code
 *       HttpRequestMethodNotSupportedException} / {@code NoResourceFoundException}）：映射到 400 / 404 /
 *       405 + {@code VALIDATION_ERROR / INVALID_ARGUMENT}； idempotency-key header 缺失特判为 {@code
 *       MISSING_IDEMPOTENCY_KEY}。
 *   <li><b>权限拒绝</b>（{@code AuthorizationDeniedException} / {@code AccessDeniedException}）： 统一 403 +
 *       {@code FORBIDDEN}，避免暴露内部授权逻辑细节。
 *   <li><b>下游调用异常</b>（{@link #handleDownstreamRestError}）：console 作为 BFF 调 orchestrator / trigger
 *       时， 优先解析下游 {@code CommonResponse} body 透传其 code + message，解析失败至少保留真实 HTTP status ——防止下游 409
 *       / 404 被一律降级为 500 误导前端。
 * </ul>
 *
 * <p>兜底 {@link #handleException} 捕获未显式处理的 {@code Exception}，返回 500 + {@code SYSTEM_ERROR} 并 ERROR
 * log 带堆栈，便于线上诊断。
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class ConsoleApiExceptionHandler {

  private final ConsoleResponseFactory responseFactory;

  // CLAUDE.md §Java #3:构造器注入(原 setter @Autowired 已迁移)。
  // BizMessageResolver 在 batch-common 自动装配;@Tolerate 单参构造保留大量
  // standalone MockMvc 测试的 `new ConsoleApiExceptionHandler(responseFactory)` 兼容。
  private final BizMessageResolver bizMessageResolver;

  @Tolerate
  public ConsoleApiExceptionHandler(ConsoleResponseFactory responseFactory) {
    this(responseFactory, null);
  }

  @ExceptionHandler(BizException.class)
  public ResponseEntity<?> handleBizException(BizException exception) {
    log.warn(
        "console biz exception: code={} message={}", exception.getCode(), exception.getMessage());
    String message =
        bizMessageResolver == null ? exception.getMessage() : bizMessageResolver.resolve(exception);
    return ResponseEntity.status(exception.getCode().httpStatus())
        .body(responseFactory.failure(exception.getCode(), message));
  }

  @ExceptionHandler(SystemException.class)
  public ResponseEntity<?> handleSystemException(SystemException exception) {
    log.error("console system exception", exception);
    return ResponseEntity.status(exception.getCode().httpStatus())
        .body(responseFactory.failure(exception.getCode(), exception.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<?> handleMethodArgumentNotValidException(
      MethodArgumentNotValidException exception) {
    log.warn("console validation exception: {}", exception.getMessage());
    String message =
        exception.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));
    return ResponseEntity.badRequest()
        .body(
            responseFactory.failure(
                ResultCode.VALIDATION_ERROR,
                message.isBlank() ? CommonErrorMessages.VALIDATION_FAILED : message));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<?> handleConstraintViolationException(
      ConstraintViolationException exception) {
    log.warn("console constraint violation exception: {}", exception.getMessage());
    return ResponseEntity.badRequest()
        .body(responseFactory.failure(ResultCode.VALIDATION_ERROR, exception.getMessage()));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<?> handleMissingRequestHeaderException(
      MissingRequestHeaderException exception) {
    log.warn("console missing request header exception: header={}", exception.getHeaderName());
    ResultCode code =
        CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(exception.getHeaderName())
            ? ResultCode.MISSING_IDEMPOTENCY_KEY
            : ResultCode.INVALID_ARGUMENT;
    return ResponseEntity.badRequest()
        .body(
            responseFactory.failure(
                code,
                CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(
                        exception.getHeaderName())
                    ? CommonErrorMessages.MISSING_IDEMPOTENCY_KEY
                    : CommonErrorMessages.INVALID_ARGUMENT));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<?> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
    // 405 是客户端调用方法错误，**非 server bug**。打印请求行 + 支持的方法即可，
    // 不打 stack trace（避免 console.log 噪音 + 让运维一眼看出问题）。
    log.warn(
        "console method not supported: {} {} (supported: {})",
        request.getMethod(),
        request.getRequestURI(),
        exception.getSupportedHttpMethods());
    return ResponseEntity.status(405)
        .body(responseFactory.failure(ResultCode.INVALID_ARGUMENT, exception.getMessage()));
  }

  @ExceptionHandler({AuthorizationDeniedException.class, AccessDeniedException.class})
  public ResponseEntity<?> handleAccessDenied(Exception exception, HttpServletRequest request) {
    // 403 同样是 client / config 问题非 server bug，打印请求行 + 异常 message。
    log.warn(
        "console access denied: {} {} - {}",
        request.getMethod(),
        request.getRequestURI(),
        exception.getMessage());
    return ResponseEntity.status(ResultCode.FORBIDDEN.httpStatus())
        .body(responseFactory.failure(ResultCode.FORBIDDEN, CommonErrorMessages.ACCESS_DENIED));
  }

  /**
   * Console 作为 BFF 调用下游（orchestrator/trigger）时，RestClient 会直接抛出异常。 这里尽量把下游返回的 {@link
   * CommonResponse} 语义透传给前端，避免一律降级成 SYSTEM_ERROR。
   */
  @ExceptionHandler(RestClientResponseException.class)
  public ResponseEntity<?> handleDownstreamRestError(RestClientResponseException exception) {
    String body = exception.getResponseBodyAsString();
    log.warn(
        "console downstream rest error: status={}, body={}",
        exception.getStatusCode().value(),
        body);
    try {
      CommonResponse<?> downstream = JsonUtils.fromJson(body, CommonResponse.class);
      if (downstream != null && downstream.code() != null) {
        // 以业务 code 为准，HTTP status 使用 code.httpStatus()（更稳定、跨服务一致）
        return ResponseEntity.status(downstream.code().httpStatus())
            .body(
                responseFactory.failure(
                    downstream.code(),
                    downstream.message() == null || downstream.message().isBlank()
                        ? downstream.code().defaultMessage()
                        : downstream.message()));
      }
    } catch (RuntimeException ignored) {
      SwallowedExceptionLogger.warn(
          ConsoleApiExceptionHandler.class, "catch:RuntimeException", ignored);

      // 继续执行
    }
    // 无法解析下游 body 时，至少保留真实 HTTP status（例如 409/404），避免前端只看到 500
    return ResponseEntity.status(exception.getStatusCode())
        .body(
            responseFactory.failure(
                ResultCode.SYSTEM_ERROR,
                body == null || body.isBlank() ? exception.getMessage() : body));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<?> handleNoResourceFound(NoResourceFoundException exception) {
    log.warn("console resource not found: {}", exception.getMessage());
    return ResponseEntity.status(404)
        .body(responseFactory.failure(ResultCode.NOT_FOUND, exception.getMessage()));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<?> handleMissingParam(
      MissingServletRequestParameterException exception, HttpServletRequest request) {
    // 带上请求 URI + method 让排查能定位前端调用点(原日志只有 param name 排查不了)
    log.warn(
        "console missing request param: {} {} — {}",
        request.getMethod(),
        request.getRequestURI(),
        exception.getMessage());
    return ResponseEntity.badRequest()
        .body(responseFactory.failure(ResultCode.INVALID_ARGUMENT, exception.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<?> handleMessageNotReadable(HttpMessageNotReadableException exception) {
    log.warn("console message not readable: {}", exception.getMessage());
    return ResponseEntity.badRequest()
        .body(responseFactory.failure(ResultCode.INVALID_ARGUMENT, exception.getMessage()));
  }

  // Multipart upload 缺 part / 错 content-type 应 400 而非 500。
  // 触发场景:
  //   - MissingServletRequestPartException:Content-Type 是 multipart 但没带 file part
  //   - MultipartException:multipart 解析失败
  //   - HttpMediaTypeNotSupportedException:Content-Type 完全不匹配
  //     (例:application/json 调 multipart/form-data 接口)
  @ExceptionHandler({
    MissingServletRequestPartException.class,
    MultipartException.class,
    HttpMediaTypeNotSupportedException.class,
  })
  public ResponseEntity<?> handleMultipart(Exception exception) {
    log.warn("console multipart/media-type error: {}", exception.getMessage());
    return ResponseEntity.badRequest()
        .body(responseFactory.failure(ResultCode.INVALID_ARGUMENT, exception.getMessage()));
  }

  /**
   * SSE / 异步响应在客户端断开后再次写入会抛 {@link AsyncRequestNotUsableException}。 此时 response Content-Type 已锁为
   * {@code text/event-stream}，再回写 {@code CommonResponse} 反而触发 {@code
   * HttpMessageNotWritableException}。
   *
   * <p>返回 {@code void} —— 这是 Spring MVC 公开契约里"handler 已自行处理完毕"的语义， 不依赖 "@ExceptionHandler 返回 null
   * 跳过写入" 这种内部行为（在 Spring 升级时可能变化）。 Spring 看到 void return 不会再调 MessageConverter 写 body，也不会再触发后续
   * resolver。
   */
  @ExceptionHandler(AsyncRequestNotUsableException.class)
  public void handleAsyncResponseUnusable(AsyncRequestNotUsableException exception) {
    log.debug("console async response unusable (client disconnected): {}", exception.getMessage());
    // 不写任何 response 内容：客户端早已断开，response 也被锁住，任何写入都会再次失败。
  }

  /**
   * DB 约束违反统一转 400 + 人话提示,避免暴露成 500 SYSTEM_ERROR。 覆盖: check constraint / not-null / unique /
   * foreign key 四类常见违反。
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<?> handleDataIntegrityViolation(DataIntegrityViolationException exception) {
    log.warn("console data integrity violation: {}", exception.getMostSpecificCause().getMessage());
    Throwable root = exception.getMostSpecificCause();
    String rawMsg = root == null ? null : root.getMessage();
    String message = PgConstraintViolation.translate(rawMsg);
    return ResponseEntity.badRequest()
        .body(responseFactory.failure(ResultCode.VALIDATION_ERROR, message));
  }

  /**
   * PG DataIntegrityViolation 子串路由表（CLAUDE.md §分支消除规则 row 1）。 每个分支按 substring 命中并产出客户端可读的中文
   * message。
   */
  private enum PgConstraintViolation {
    CHECK("violates check constraint") {
      @Override
      String render(String msg) {
        int idx = msg.indexOf("\"", msg.indexOf("constraint"));
        if (idx > 0) {
          int end = msg.indexOf("\"", idx + 1);
          if (end > idx) {
            return "字段值不合法,违反约束: " + msg.substring(idx + 1, end);
          }
        }
        return DEFAULT_MESSAGE;
      }
    },
    UNIQUE("violates unique constraint") {
      @Override
      String render(String msg) {
        return "记录已存在(唯一键冲突)";
      }
    },
    FOREIGN_KEY("violates foreign key constraint") {
      @Override
      String render(String msg) {
        return "关联数据缺失或无法删除(外键约束)";
      }
    },
    NOT_NULL("violates not-null constraint") {
      @Override
      String render(String msg) {
        int colStart = msg.indexOf("\"");
        int colEnd = msg.indexOf("\"", colStart + 1);
        if (colStart >= 0 && colEnd > colStart) {
          return "必填字段缺失: " + msg.substring(colStart + 1, colEnd);
        }
        return "必填字段缺失";
      }
    };

    private static final String DEFAULT_MESSAGE = "数据约束错误";

    private final String marker;

    PgConstraintViolation(String marker) {
      this.marker = marker;
    }

    abstract String render(String msg);

    static String translate(String rawMsg) {
      if (rawMsg == null) {
        return DEFAULT_MESSAGE;
      }
      for (PgConstraintViolation kind : values()) {
        if (rawMsg.contains(kind.marker)) {
          return kind.render(rawMsg);
        }
      }
      return DEFAULT_MESSAGE;
    }
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> handleException(Exception exception) {
    log.error("console unexpected exception", exception);
    return ResponseEntity.internalServerError()
        .body(responseFactory.failure(ResultCode.SYSTEM_ERROR, CommonErrorMessages.SYSTEM_ERROR));
  }
}

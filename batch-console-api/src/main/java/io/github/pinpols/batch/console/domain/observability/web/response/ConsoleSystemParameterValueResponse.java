package io.github.pinpols.batch.console.domain.observability.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 系统参数取值响应。
 *
 * <p>历史实现：命中返回 {@code {key, value}}，未命中仅返回 {@code {key}}（不含 value 键）。故 {@code value} 用 {@code
 * NON_NULL} 省略，保持键集与历史 wire 一致。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleSystemParameterValueResponse(String key, String value) {

  public static ConsoleSystemParameterValueResponse of(String key, String value) {
    return new ConsoleSystemParameterValueResponse(key, value);
  }
}

package io.github.pinpols.batch.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

@DisplayName("请求语言头的显式值与上下文传播")
class AcceptLanguageRequestInterceptorTest {

  private final AcceptLanguageRequestInterceptor interceptor =
      new AcceptLanguageRequestInterceptor();

  @DisplayName("缺少语言头时传播当前语言环境")
  @Test
  void shouldPropagateCurrentLocaleWhenAcceptLanguageIsMissing() throws IOException {
    HttpRequest request = mock(HttpRequest.class);
    HttpHeaders headers = new HttpHeaders();
    when(request.getHeaders()).thenReturn(headers);
    ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
    ClientHttpResponse expectedResponse = mock(ClientHttpResponse.class);
    byte[] body = new byte[0];
    when(execution.execute(request, body)).thenReturn(expectedResponse);

    LocaleContext previousContext = LocaleContextHolder.getLocaleContext();
    LocaleContextHolder.setLocale(Locale.JAPAN);
    try {
      ClientHttpResponse response = interceptor.intercept(request, body, execution);

      assertThat(headers.getAcceptLanguageAsLocales()).contains(Locale.JAPAN);
      assertThat(response).isSameAs(expectedResponse);
    } finally {
      LocaleContextHolder.setLocaleContext(previousContext);
    }
  }

  @DisplayName("保留调用方显式指定的语言头")
  @Test
  void shouldPreserveExplicitAcceptLanguageHeader() throws IOException {
    HttpRequest request = mock(HttpRequest.class);
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en-US");
    when(request.getHeaders()).thenReturn(headers);
    ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
    ClientHttpResponse expectedResponse = mock(ClientHttpResponse.class);
    byte[] body = new byte[0];
    when(execution.execute(request, body)).thenReturn(expectedResponse);

    LocaleContext previousContext = LocaleContextHolder.getLocaleContext();
    LocaleContextHolder.setLocale(Locale.JAPAN);
    try {
      ClientHttpResponse response = interceptor.intercept(request, body, execution);

      assertThat(headers.getFirst(HttpHeaders.ACCEPT_LANGUAGE)).isEqualTo("en-US");
      assertThat(response).isSameAs(expectedResponse);
    } finally {
      LocaleContextHolder.setLocaleContext(previousContext);
    }
  }
}

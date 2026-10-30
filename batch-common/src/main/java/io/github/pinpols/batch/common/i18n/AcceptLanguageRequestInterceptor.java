package io.github.pinpols.batch.common.i18n;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.io.IOException;
import java.util.List;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** 将当前请求协商后的语言传递给内部 HTTP 服务。 */
public class AcceptLanguageRequestInterceptor implements ClientHttpRequestInterceptor {

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    if (EmptyChecks.isNull(request.getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE))
        && EmptyChecks.isNotNull(LocaleContextHolder.getLocaleContext())) {
      request.getHeaders().setAcceptLanguageAsLocales(List.of(LocaleContextHolder.getLocale()));
    }
    return execution.execute(request, body);
  }
}

package com.example.batch.console.support.web;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
@RequiredArgsConstructor
public class ConsoleRequestMetadataResolver {

  private final HttpServletRequest request;

  public ConsoleRequestMetadata current() {
    Object metadata = request.getAttribute(ConsoleRequestContextFilter.REQUEST_METADATA_ATTRIBUTE);
    if (metadata instanceof ConsoleRequestMetadata consoleRequestMetadata) {
      return consoleRequestMetadata;
    }
    return new ConsoleRequestMetadata(null, null, null, null, null, request.getRemoteAddr());
  }

  public ResponseMeta responseMeta() {
    ConsoleRequestMetadata metadata = current();
    return new ResponseMeta(
        metadata.requestId(), metadata.traceId(), BatchDateTimeSupport.utcNow());
  }
}

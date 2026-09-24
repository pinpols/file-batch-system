package io.github.pinpols.batch.common.http;

import java.io.IOException;

/** 平台 Java 外部 HTTP 的窄传输契约；业务模块不直接依赖具体客户端类型。 */
@FunctionalInterface
public interface OutboundHttpTransport {

  OutboundHttpResponse execute(OutboundHttpRequest request) throws IOException;
}

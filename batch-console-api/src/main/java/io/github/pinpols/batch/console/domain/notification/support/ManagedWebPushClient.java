package io.github.pinpols.batch.console.domain.notification.support;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.HttpRequest;
import nl.martijndwars.webpush.Notification;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Response;
import org.jose4j.lang.JoseException;

/** 管理传输生命周期；加密和 VAPID 签名仍由固定版本的依赖库负责。 */
public class ManagedWebPushClient extends AbstractPushService<ManagedWebPushClient>
    implements Closeable {
  private final AsyncHttpClient httpClient;

  public ManagedWebPushClient(String publicKey, String privateKey, Duration timeout)
      throws GeneralSecurityException {
    super(publicKey, privateKey);
    httpClient = asyncHttpClient(config()
        .setIoThreadsCount(2)
        .setMaxRequestRetry(0)
        .setFollowRedirect(false)
        .setConnectTimeout(timeout)
        .setReadTimeout(timeout)
        .setRequestTimeout(timeout));
  }

  public CompletableFuture<Response> send(Notification notification)
      throws GeneralSecurityException, IOException, JoseException {
    // web-push 5.1.1 会从 getOrigin() 中移除所有端口，包括非默认端口。
    Notification originalOrigin =
        new Notification(
            notification.getEndpoint(),
            notification.getUserPublicKey(),
            notification.getUserAuth(),
            notification.getPayload(),
            notification.getTTL(),
            notification.getUrgency(),
            notification.getTopic()) {
          @Override
          public String getOrigin() throws MalformedURLException {
            return origin(getEndpoint());
          }
        };
    HttpRequest request = prepareRequest(originalOrigin, Encoding.AES128GCM);
    BoundRequestBuilder post = httpClient.preparePost(request.getUrl());
    request.getHeaders().forEach(post::addHeader);
    if (EmptyChecks.isNotNull(request.getBody())) post.setBody(request.getBody());
    return post.execute().toCompletableFuture();
  }

  static String origin(String endpoint) throws MalformedURLException {
    URI endpointUri = URI.create(endpoint);
    URL url = endpointUri.toURL();
    int port = url.getPort();
    return url.getProtocol().toLowerCase(Locale.ROOT) + "://"
        + url.getHost().toLowerCase(Locale.ROOT)
        + (port < 0 || port == url.getDefaultPort() ? "" : ":" + port);
  }

  @Override
  public void close() throws IOException {
    httpClient.close();
  }
}

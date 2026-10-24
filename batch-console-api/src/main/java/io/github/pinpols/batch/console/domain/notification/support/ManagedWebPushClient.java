package io.github.pinpols.batch.console.domain.notification.support;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.MalformedURLException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.jose4j.lang.JoseException;

/** Owns transport lifetime; the pinned library continues to own encryption and VAPID signing. */
public class ManagedWebPushClient extends AbstractPushService<ManagedWebPushClient> implements Closeable {
  private final AsyncHttpClient httpClient;

  public ManagedWebPushClient(String publicKey, String privateKey, Duration timeout) throws GeneralSecurityException {
    super(publicKey, privateKey);
    httpClient = asyncHttpClient(config().setIoThreadsCount(2)
        .setConnectTimeout(timeout).setReadTimeout(timeout).setRequestTimeout(timeout));
  }

  public CompletableFuture<Response> send(Notification notification)
      throws GeneralSecurityException, IOException, JoseException {
    // web-push 5.1.1 drops every port from getOrigin(), including non-default ports.
    var originalOrigin = new Notification(notification.getEndpoint(), notification.getUserPublicKey(),
        notification.getUserAuth(), notification.getPayload(), notification.getTTL(),
        notification.getUrgency(), notification.getTopic()) {
      @Override public String getOrigin() throws MalformedURLException {
        return origin(getEndpoint());
      }
    };
    var request = prepareRequest(originalOrigin, Encoding.AES128GCM);
    var post = httpClient.preparePost(request.getUrl());
    request.getHeaders().forEach(post::addHeader);
    if (EmptyChecks.isNotNull(request.getBody())) post.setBody(request.getBody());
    return post.execute().toCompletableFuture();
  }

  static String origin(String endpoint) throws MalformedURLException {
    var url = URI.create(endpoint).toURL();
    int port = url.getPort();
    return url.getProtocol().toLowerCase(Locale.ROOT) + "://" + url.getHost().toLowerCase(Locale.ROOT)
        + (port < 0 || port == url.getDefaultPort() ? "" : ":" + port);
  }

  @Override public void close() throws IOException { httpClient.close(); }
}

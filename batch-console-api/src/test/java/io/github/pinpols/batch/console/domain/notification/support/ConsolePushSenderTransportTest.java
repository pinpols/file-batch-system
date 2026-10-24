package io.github.pinpols.batch.console.domain.notification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.DeliveryOutcome;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.PushPayload;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import nl.martijndwars.webpush.Utils;
import org.asynchttpclient.AsyncHttpClient;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

/** 读取控制面租户登记表；设备队列仍受 FORCE RLS 隔离。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Web Push 真实签名、加密传输与停机资源释放")
class ConsolePushSenderTransportTest {
  @Mock
  private ConsolePushSubscriptionMapper subscriptions;

  private HttpServer endpoint;
  private AnnotationConfigApplicationContext context;
  private AsyncHttpClient client;
  private CountDownLatch responseGate = new CountDownLatch(0);
  private boolean dropResponse;
  private final AtomicInteger requests = new AtomicInteger();
  private final CompletableFuture<Received> received = new CompletableFuture<>();

  private record Received(String authorization, String encoding, String ttl, byte[] body) {}

  @AfterEach
  void cleanup() throws Exception {
    responseGate.countDown();
    if (context != null) context.close();
    // 使用真实回环 HTTP 和加密；不连接外部推送服务或浏览器。
    if (client != null && !client.isClosed()) client.close();
    if (endpoint != null) endpoint.stop(0);
  }

  @Test
  @DisplayName("真实 HTTP 201 携带有效 VAPID 签名及可由原设备解密的业务载荷")
  void shouldSendDecryptableOriginalPayload_whenProviderAccepts() throws Exception {
    var vapid = PushDeviceFixtures.keyPair();
    var sender = start(vapid, 201);
    var deviceKeys = PushDeviceFixtures.keyPair();
    var device = PushDeviceFixtures.device("ta", "alice", origin() + "/device", deviceKeys);
    device.setId(42L);
    var payload =
        new PushPayload("作业完成", "2026-10-20", "job-terminal-42", "/m/jobs/42?tenantId=ta");

    assertThat(sender.sendToSubscription(device, payload).outcome())
        .isEqualTo(DeliveryOutcome.ACCEPTED);
    var request = received.get(5, TimeUnit.SECONDS);
    assertThat(request.encoding()).isEqualTo("aes128gcm");
    assertThat(request.ttl()).isEqualTo("43200");
    assertThat(request.authorization()).startsWith("vapid t=");
    String token = request.authorization().substring("vapid t=".length()).split(", k=")[0];
    var signature = new JsonWebSignature();
    signature.setCompactSerialization(token);
    signature.setKey(vapid.getPublic());
    assertThat(signature.verifySignature()).isTrue();
    var claims = JwtClaims.parse(signature.getPayload());
    assertThat(claims.getAudience()).containsExactly(origin());
    assertThat(claims.getSubject()).isEqualTo("mailto:push-test@example.test");
    assertThat(request.authorization())
        .endsWith(", k=" + encode(Utils.encode((ECPublicKey) vapid.getPublic())));

    // 旧依赖库没有公开的关闭 API。失败的生命周期测试也必须避免泄漏资源。
    byte[] plaintext =
        decrypt(request.body(), deviceKeys, Base64.getUrlDecoder().decode(device.getAuthSecret()));
    var json = JsonUtils.newDefaultMapper().readTree(plaintext);
    assertThat(json.path("title").asText()).isEqualTo("作业完成");
    assertThat(json.path("body").asText()).isEqualTo("2026-10-20");
    assertThat(json.path("tag").asText()).isEqualTo("job-terminal-42");
    assertThat(json.path("url").asText()).isEqualTo("/m/jobs/42?tenantId=ta");
    verify(subscriptions).touchIfUnchanged(any(), any());
  }

  @Test
  @DisplayName("真实 HTTP 410 只清理原设备快照")
  void shouldRemoveOriginalDevice_whenProviderReturnsGone() throws Exception {
    var sender = start(PushDeviceFixtures.keyPair(), 410);
    var device = PushDeviceFixtures.device("ta", "alice", origin() + "/device");
    assertThat(sender
            .sendToSubscription(device, new PushPayload("t", "b", "tag", "/m/jobs/42"))
            .outcome())
        .isEqualTo(DeliveryOutcome.GONE);
    received.get(5, TimeUnit.SECONDS);
    verify(subscriptions).deleteIfUnchanged(device);
    verify(subscriptions, never()).touchIfUnchanged(any(), any());
  }

  @Test
  @DisplayName("Spring 关闭后释放 HTTP 客户端且不再发出新请求")
  void shouldReleaseTransport_whenApplicationStops() throws Exception {
    var sender = start(PushDeviceFixtures.keyPair(), 201);
    context.close();
    assertThat(client.isClosed()).isTrue();
    var device = PushDeviceFixtures.device("ta", "alice", origin() + "/device");
    assertThat(sender
            .sendToSubscription(device, new PushPayload("t", "b", "tag", "/m/jobs/42"))
            .outcome())
        .isEqualTo(DeliveryOutcome.NOT_READY);
    assertThat(received).isNotDone();
  }

  @ParameterizedTest
  @CsvSource({"429,RETRYABLE_FAILURE", "503,RETRYABLE_FAILURE", "400,PERMANENT_FAILURE"})
  @DisplayName("真实拒绝响应区分暂时和永久失败，不误记服务接纳")
  void shouldPreserveRejectionClass_whenProviderRejects(int status, DeliveryOutcome outcome)
      throws Exception {
    var sender = start(PushDeviceFixtures.keyPair(), status);
    var device = PushDeviceFixtures.device("ta", "alice", origin() + "/device");
    var result = sender.sendToSubscription(device, new PushPayload("t", "b", "tag", "/m/jobs/42"));
    assertThat(result.outcome()).isEqualTo(outcome);
    assertThat(result.httpStatus()).isEqualTo(status);
    received.get(5, TimeUnit.SECONDS);
    verifyNoInteractions(subscriptions);
  }

  @Test
  @DisplayName("停机关闭真实在途连接时保留结果不明，不记接纳或自动删除设备")
  void shouldKeepUnknownOutcome_whenApplicationClosesInFlightTransport() throws Exception {
    responseGate = new CountDownLatch(1);
    var sender = start(PushDeviceFixtures.keyPair(), 201);
    var device = PushDeviceFixtures.device("ta", "alice", origin() + "/device");
    try (var executor = Executors.newSingleThreadExecutor()) {
      var attempt = executor.submit(
          () -> sender.sendToSubscription(device, new PushPayload("t", "b", "tag", "/m/jobs/42")));
      received.get(5, TimeUnit.SECONDS);
      context.close();
      assertThat(attempt.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(DeliveryOutcome.UNKNOWN);
      assertThat(client.isClosed()).isTrue();
      verifyNoInteractions(subscriptions);
    } finally {
      responseGate.countDown();
    }
  }

  @Test
  @DisplayName("接收请求后断连只发送一次，禁止 HTTP 客户端隐藏重试")
  void shouldNotReplayAcceptedRequest_whenConnectionClosesWithoutResponse() throws Exception {
    dropResponse = true;
    var sender = start(PushDeviceFixtures.keyPair(), 201);
    var device = PushDeviceFixtures.device("ta", "alice", origin() + "/device");
    var result = sender.sendToSubscription(device, new PushPayload("t", "b", "tag", "/m/jobs/42"));
    assertThat(result.outcome()).isEqualTo(DeliveryOutcome.UNKNOWN);
    assertThat(requests.get())
        .as("one durable attempt must not hide automatic HTTP replays")
        .isEqualTo(1);
    verifyNoInteractions(subscriptions);
  }

  @ParameterizedTest
  @CsvSource({
    "https://push.example/path,https://push.example",
    "https://push.example:443/path,https://push.example",
    "https://push.example:8443/path,https://push.example:8443",
    "http://127.0.0.1:8080/device,http://127.0.0.1:8080",
    "https://[::1]:8443/device,https://[::1]:8443"
  })
  @DisplayName("VAPID 目标源地址保留非默认端口并规范默认端口")
  void shouldPreserveOrigin_whenEndpointHasExplicitPort(String target, String expected)
      throws Exception {
    assertThat(ManagedWebPushClient.origin(target)).isEqualTo(expected);
  }

  @SuppressWarnings("unchecked")
  private ConsolePushSender start(KeyPair keys, int responseStatus) throws Exception {
    endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    endpoint.createContext("/device", exchange -> {
      try {
        requests.incrementAndGet();
        var headers = exchange.getRequestHeaders();
        received.complete(new Received(
            headers.getFirst("Authorization"),
            headers.getFirst("Content-Encoding"),
            headers.getFirst("TTL"),
            exchange.getRequestBody().readAllBytes()));
        if (!responseGate.await(10, TimeUnit.SECONDS))
          throw new IOException("response gate was not released");
        if (dropResponse) return;
        exchange.sendResponseHeaders(responseStatus, -1);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    endpoint.start();
    var props = new ConsolePushProperties();
    props.setEnabled(true);
    props.setPublicKey(encode(Utils.encode((ECPublicKey) keys.getPublic())));
    props.setPrivateKey(encode(Utils.encode((ECPrivateKey) keys.getPrivate())));
    props.setSubject("mailto:push-test@example.test");
    context = new AnnotationConfigApplicationContext();
    context.registerBean(
        ConsolePushSender.class,
        () -> new ConsolePushSender(props, subscriptions, JsonUtils.newDefaultMapper()));
    context.refresh();
    var sender = context.getBean(ConsolePushSender.class);
    Object service =
        ((AtomicReference<Object>) ReflectionTestUtils.getField(sender, "pushService")).get();
    client = (AsyncHttpClient) ReflectionTestUtils.getField(service, "httpClient");
    return sender;
  }

  private String origin() {
    return "http://127.0.0.1:" + endpoint.getAddress().getPort();
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static byte[] decrypt(byte[] wire, KeyPair device, byte[] auth) throws Exception {
    byte[] salt = Arrays.copyOfRange(wire, 0, 16);
    int keyLength = Byte.toUnsignedInt(wire[20]);
    byte[] senderKey = Arrays.copyOfRange(wire, 21, 21 + keyLength);
    var agreement = KeyAgreement.getInstance("ECDH", "BC");
    agreement.init(device.getPrivate());
    agreement.doPhase(Utils.loadPublicKey(senderKey), true);
    byte[] info = concat(
        "WebPush: info\0".getBytes(StandardCharsets.US_ASCII),
        Utils.encode((ECPublicKey) device.getPublic()),
        senderKey);
    byte[] ikm = expand(hmac(auth, agreement.generateSecret()), info, 32);
    byte[] prk = hmac(salt, ikm);
    byte[] key =
        expand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
    byte[] nonce = expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
    byte[] record = cipher.doFinal(Arrays.copyOfRange(wire, 21 + keyLength, wire.length));
    assertThat(record[record.length - 1]).isEqualTo((byte) 2);
    return Arrays.copyOf(record, record.length - 1);
  }

  private static byte[] expand(byte[] key, byte[] info, int length) throws Exception {
    return Arrays.copyOf(hmac(key, concat(info, new byte[] {1})), length);
  }

  private static byte[] hmac(byte[] key, byte[] bytes) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(bytes);
  }

  private static byte[] concat(byte[]... pieces) {
    var output = new ByteArrayOutputStream();
    for (var piece : pieces) output.writeBytes(piece);
    return output.toByteArray();
  }
}

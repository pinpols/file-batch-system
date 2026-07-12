package io.github.pinpols.batch.worker.atomic.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpTaskExecutor#isBlockedAddress(InetAddress)} 单测 — 纯 IP literal 判定,不联网 / 不起 server。
 * {@link InetAddress#getByName(String)} 对 IP 字面量不做 DNS 解析,因此离线可跑。
 */
class HttpTaskExecutorIpBlockTest {

  private static InetAddress addr(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new AssertionError("IP literal 解析意外失败: " + literal, e);
    }
  }

  private static boolean blocked(String literal) {
    return HttpTaskExecutor.isBlockedAddress(addr(literal));
  }

  @Test
  void rejects_loopback_ipv4() {
    assertThat(blocked("127.0.0.1")).isTrue();
  }

  @Test
  void rejects_loopback_ipv6() {
    assertThat(blocked("::1")).isTrue();
  }

  @Test
  void rejects_link_local_metadata_ipv4() {
    assertThat(blocked("169.254.169.254")).isTrue();
  }

  @Test
  void rejects_link_local_metadata_ipv4_mapped_ipv6() {
    assertThat(blocked("::ffff:169.254.169.254")).isTrue();
  }

  @Test
  void rejects_private_class_a() {
    assertThat(blocked("10.0.0.5")).isTrue();
  }

  @Test
  void rejects_private_class_c() {
    assertThat(blocked("192.168.1.1")).isTrue();
  }

  @Test
  void rejects_any_local() {
    assertThat(blocked("0.0.0.0")).isTrue();
  }

  @Test
  void accepts_public_literal() {
    assertThat(blocked("8.8.8.8")).isFalse();
  }

  @Test
  void accepts_public_literal_ipv6() {
    assertThat(blocked("2001:4860:4860::8888")).isFalse();
  }

  /**
   * 回归:原本地私有副本用 {@code isSiteLocalAddress()}(仅匹配废弃的 fec0::/10),放行了 fc00::/7 ULA —— 包括 AWS IPv6
   * metadata {@code fd00:ec2::254}。收敛到 canonical {@link
   * io.github.pinpols.batch.common.security.DnsResolveGuard} 后必须拦截。
   */
  @Test
  void rejects_ipv6_ula_aws_metadata() {
    assertThat(blocked("fd00:ec2::254")).isTrue();
  }

  @Test
  void rejects_ipv6_ula_fc00_range() {
    assertThat(blocked("fc00::1")).isTrue();
  }

  @Test
  void rejects_ipv6_ula_fd_range() {
    assertThat(blocked("fd12:3456:789a::1")).isTrue();
  }

  @Test
  void rejects_ipv6_link_local() {
    assertThat(blocked("fe80::1")).isTrue();
  }
}

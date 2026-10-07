package io.github.pinpols.batch.worker.atomic.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpTaskExecutor#isBlockedAddress(InetAddress)} 单测 — 纯 IP literal 判定,不联网 / 不起 server。
 * {@link InetAddress#getByName(String)} 对 IP 字面量不做 DNS 解析,因此离线可跑。
 */
@DisplayName("HTTP 任务执行器内网地址拦截: 回环, 私有与链路本地网段判定")
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
  @DisplayName("回环地址应判定为禁止访问, 避免打到本机服务")
  void rejects_loopback_ipv4() {
    assertThat(blocked("127.0.0.1")).isTrue();
  }

  @Test
  @DisplayName("IPv6 回环地址同样应被拦截, 不留协议族盲区")
  void rejects_loopback_ipv6() {
    assertThat(blocked("::1")).isTrue();
  }

  @Test
  @DisplayName("IPv4 链路本地地址应被拦截, 阻断云元数据服务访问")
  void rejects_link_local_metadata_ipv4() {
    assertThat(blocked("169.254.169.254")).isTrue();
  }

  @Test
  @DisplayName("IPv4 映射形态的链路本地地址也应被识别并拦截")
  void rejects_link_local_metadata_ipv4_mapped_ipv6() {
    assertThat(blocked("::ffff:169.254.169.254")).isTrue();
  }

  @Test
  @DisplayName("A 类私有网段地址应判定为内网并拒绝")
  void rejects_private_class_a() {
    assertThat(blocked("10.0.0.5")).isTrue();
  }

  @Test
  @DisplayName("C 类私有网段地址应判定为内网并拒绝")
  void rejects_private_class_c() {
    assertThat(blocked("192.168.1.1")).isTrue();
  }

  @Test
  @DisplayName("未指定地址应视为本机, 一并纳入拦截范围")
  void rejects_any_local() {
    assertThat(blocked("0.0.0.0")).isTrue();
  }

  @Test
  @DisplayName("公网 IPv4 字面量应放行, 不误伤正常外呼")
  void accepts_public_literal() {
    assertThat(blocked("8.8.8.8")).isFalse();
  }

  @Test
  @DisplayName("公网 IPv6 字面量应放行, 保证双栈出口可用")
  void accepts_public_literal_ipv6() {
    assertThat(blocked("2001:4860:4860::8888")).isFalse();
  }

  /**
   * 回归:原本地私有副本用 {@code isSiteLocalAddress()}(仅匹配废弃的 fec0::/10),放行了 fc00::/7 ULA —— 包括 AWS IPv6
   * metadata {@code fd00:ec2::254}。收敛到 canonical {@link
   * io.github.pinpols.batch.common.security.DnsResolveGuard} 后必须拦截。
   */
  @Test
  @DisplayName("云厂商 IPv6 元数据地址应被拦截, 修正旧实现放行唯一本地地址的回归")
  void rejects_ipv6_ula_aws_metadata() {
    assertThat(blocked("fd00:ec2::254")).isTrue();
  }

  @Test
  @DisplayName("唯一本地地址低位区间应判定为内网并拒绝")
  void rejects_ipv6_ula_fc00_range() {
    assertThat(blocked("fc00::1")).isTrue();
  }

  @Test
  @DisplayName("唯一本地地址高位区间应判定为内网并拒绝")
  void rejects_ipv6_ula_fd_range() {
    assertThat(blocked("fd12:3456:789a::1")).isTrue();
  }

  @Test
  @DisplayName("IPv6 链路本地地址应被拦截, 防止触达同链路内部服务")
  void rejects_ipv6_link_local() {
    assertThat(blocked("fe80::1")).isTrue();
  }
}

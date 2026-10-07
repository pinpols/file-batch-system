package io.github.pinpols.batch.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DnsResolveGuard: 内网与特殊用途地址判定及解析结果整体校验")
class DnsResolveGuardTest {

  @Test
  @DisplayName("IPv6 回环、唯一本地与链路本地地址都判定为阻断")
  void shouldBlockIpv6LoopbackAndUniqueLocal_whenChecked() throws Exception {
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("::1"))).isTrue();
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("fc00::20"))).isTrue();
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("fe80::20"))).isTrue();
  }

  @Test
  @DisplayName("IPv4 映射的 IPv6 回环与云元数据地址都判定为阻断")
  void shouldBlockIpv4MappedIpv6_whenChecked() throws Exception {
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("::ffff:127.0.0.1")))
        .isTrue();
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("::ffff:169.254.169.254")))
        .isTrue();
  }

  @Test
  @DisplayName("文档示例用全球单播 IPv6 地址放行")
  void shouldAllow_whenDocumentationGlobalIpv6() throws Exception {
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("2001:db8::20"))).isFalse();
  }

  @Test
  @DisplayName("通配本地地址与组播地址都判定为阻断")
  void shouldBlockAnyLocalAndMulticast_whenChecked() throws Exception {
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("0.0.0.0"))).isTrue();
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("::"))).isTrue();
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("224.0.0.1"))).isTrue();
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("ff02::1"))).isTrue();
  }

  @Test
  @DisplayName("特殊用途 IPv4 网段逐项判定为阻断")
  void shouldBlockSpecialPurposeIpv4_whenChecked() throws Exception {
    for (String address : List.of(
        "0.0.0.1",
        "100.64.0.1",
        "192.0.0.1",
        "192.0.2.1",
        "198.18.0.1",
        "198.51.100.1",
        "203.0.113.1",
        "240.0.0.1")) {
      assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName(address)))
          .as("address %s", address)
          .isTrue();
    }
  }

  @Test
  @DisplayName("IPv4 映射形态的特殊用途网段同样判定为阻断")
  void shouldBlockSpecialPurposeIpv4MappedIpv6_whenChecked() throws Exception {
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("::ffff:100.64.0.1")))
        .isTrue();
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("::ffff:198.18.0.1")))
        .isTrue();
  }

  @Test
  @DisplayName("多个解析结果全部通过校验时按原顺序返回且不可变")
  void shouldValidateAllAddressesAndPreserveOrder_whenResolved() throws Exception {
    InetAddress ipv6 = InetAddress.getByName("2001:db8::20");
    InetAddress ipv4 = InetAddress.getByName("93.184.216.34");

    List<InetAddress> resolved = DnsResolveGuard.validateResolvedAddresses(
        "dual-stack.example", new InetAddress[] {ipv6, ipv4, ipv6});

    assertThat(resolved).containsExactly(ipv6, ipv4).isUnmodifiable();
  }

  @Test
  @DisplayName("任一地址被阻断时整个解析结果拒绝,并指出命中地址")
  void shouldRejectEntireResolution_whenAnyAddressBlocked() throws Exception {
    InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
    InetAddress privateAddress = InetAddress.getByName("127.0.0.1");

    assertThatThrownBy(() -> DnsResolveGuard.validateResolvedAddresses(
            "mixed.example", new InetAddress[] {publicAddress, privateAddress}))
        .isInstanceOf(BlockedAddressException.class)
        .hasMessageContaining("127.0.0.1");
  }

  @Test
  @DisplayName("解析结果为空时按未知主机处理")
  void shouldReject_whenResolvedAddressesEmpty() {
    assertThatThrownBy(
            () -> DnsResolveGuard.validateResolvedAddresses("empty.example", new InetAddress[0]))
        .isInstanceOf(UnknownHostException.class);
  }
}

package io.github.pinpols.batch.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

class DnsResolveGuardTest {

  @Test
  void blocksIpv6LoopbackAndUniqueLocalAddresses() throws Exception {
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("::1"))).isTrue();
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("fc00::20"))).isTrue();
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("fe80::20"))).isTrue();
  }

  @Test
  void blocksIpv4MappedIpv6Addresses() throws Exception {
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("::ffff:127.0.0.1")))
        .isTrue();
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("::ffff:169.254.169.254")))
        .isTrue();
  }

  @Test
  void allowsDocumentationGlobalIpv6Address() throws Exception {
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("2001:db8::20"))).isFalse();
  }

  @Test
  void blocksAnyLocalAndMulticastAddresses() throws Exception {
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("0.0.0.0"))).isTrue();
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("::"))).isTrue();
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("224.0.0.1"))).isTrue();
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("ff02::1"))).isTrue();
  }

  @Test
  void blocksSpecialPurposeIpv4Ranges() throws Exception {
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
  void blocksSpecialPurposeIpv4MappedIpv6Ranges() throws Exception {
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("::ffff:100.64.0.1")))
        .isTrue();
    assertThat(DnsResolveGuard.isBlocked(InetAddress.getByName("::ffff:198.18.0.1")))
        .isTrue();
  }

  @Test
  void validatesAllAddressesAndPreservesResolverOrder() throws Exception {
    InetAddress ipv6 = InetAddress.getByName("2001:db8::20");
    InetAddress ipv4 = InetAddress.getByName("93.184.216.34");

    List<InetAddress> resolved = DnsResolveGuard.validateResolvedAddresses(
        "dual-stack.example", new InetAddress[] {ipv6, ipv4, ipv6});

    assertThat(resolved).containsExactly(ipv6, ipv4).isUnmodifiable();
  }

  @Test
  void rejectsEntireResolutionWhenAnyAddressIsBlocked() throws Exception {
    InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
    InetAddress privateAddress = InetAddress.getByName("127.0.0.1");

    assertThatThrownBy(() -> DnsResolveGuard.validateResolvedAddresses(
            "mixed.example", new InetAddress[] {publicAddress, privateAddress}))
        .isInstanceOf(BlockedAddressException.class)
        .hasMessageContaining("127.0.0.1");
  }

  @Test
  void rejectsEmptyResolution() {
    assertThatThrownBy(
            () -> DnsResolveGuard.validateResolvedAddresses("empty.example", new InetAddress[0]))
        .isInstanceOf(UnknownHostException.class);
  }
}

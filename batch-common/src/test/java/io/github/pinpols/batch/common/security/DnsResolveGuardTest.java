package io.github.pinpols.batch.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
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
}

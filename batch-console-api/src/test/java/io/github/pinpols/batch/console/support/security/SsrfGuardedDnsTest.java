package io.github.pinpols.batch.console.support.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.security.BlockedAddressException;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SsrfGuardedDns} 单测:验证 OkHttp 建连回调层做真 per-request pin —— 返回的正是 {@code DnsResolveGuard}
 * 校验过的**那一个**具体 IP(而非整组 A 记录),这样"校验的 IP == 连接的 IP"确定成立;内网/受限 IP 被拦;bypass 模式放行。
 */
@DisplayName("受限地址防护下的域名解析: 单地址锁定与放行开关")
class SsrfGuardedDnsTest {

  private final BatchSecurityProperties securityProperties = mock(BatchSecurityProperties.class);
  private final SsrfGuardedDns dns = new SsrfGuardedDns(securityProperties);

  @Test
  @DisplayName("校验通过后只返回被校验的那一个公网地址")
  void shouldReturnExactlyTheSingleValidatedPublicIp() throws Exception {
    when(securityProperties.isBypassMode()).thenReturn(false);

    // 用公网 IP 字面量,避免单测依赖真实 DNS;校验通过后应原样返回该地址。
    List<InetAddress> resolved = dns.lookup("93.184.216.34");

    // I2 修复:只返回一个 IP(guard 校验的那个),客户端无从连到组内另一个(公网+内网混合)地址。
    assertThat(resolved).hasSize(1);
    assertThat(resolved.get(0).getHostAddress()).isEqualTo("93.184.216.34");
  }

  @Test
  @DisplayName("解析回环地址时抛出受限地址异常")
  void shouldBlockInternalAddress() {
    when(securityProperties.isBypassMode()).thenReturn(false);

    assertThatThrownBy(() -> dns.lookup("127.0.0.1")).isInstanceOf(BlockedAddressException.class);
  }

  @Test
  @DisplayName("放行开关打开时不做校验, 回环地址也原样返回")
  void shouldDelegateToSystemResolverWhenBypassMode() throws Exception {
    when(securityProperties.isBypassMode()).thenReturn(true);

    // bypass(非 prod 联调):不校验,回环也放行。
    List<InetAddress> resolved = dns.lookup("127.0.0.1");

    assertThat(resolved).isNotEmpty();
    assertThat(resolved.get(0).getHostAddress()).isEqualTo("127.0.0.1");
  }
}

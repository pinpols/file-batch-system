package io.github.pinpols.batch.console.support.security;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.security.DnsResolveGuard;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.Dns;
import org.springframework.stereotype.Component;

/**
 * OkHttp {@link Dns} 实现：在客户端真正建连前校验全部解析结果，并把同一份安全地址快照交给连接层。
 *
 * <p><b>为什么这样能根治 rebinding。</b>OkHttp 用本接口返回的地址直接建连，不会再对主机名二次解析，因此“校验的 IP == 连接的
 * IP”在同一次调用里成立。任一 A/AAAA 结果受限时整体拒绝；全部安全时保留完整列表供双栈连接回退。
 *
 * <p><b>不碰 TLS 红线。</b>本接口只改地址解析;OkHttp 仍以原始 hostname 做 SNI 与证书校验(HostnameVerifier 按 hostname 验),
 * TLS 语义不变。
 *
 * <p>与 worker-dispatch 的 {@code HttpDispatchChannelAdapter} / {@code DispatchReceiptPollScheduler}
 * 同款做法。 {@code bypassMode}(非 prod 联调)直接走系统解析,放行回环 / 私网。
 */
@Component
public class SsrfGuardedDns implements Dns {

  private final BatchSecurityProperties securityProperties;

  public SsrfGuardedDns(BatchSecurityProperties securityProperties) {
    this.securityProperties = securityProperties;
  }

  @Override
  public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    if (securityProperties.isBypassMode()) {
      return SYSTEM.lookup(hostname);
    }
    // resolve-then-connect：解析与受限网段校验在此完成，OkHttp 直接使用这份地址快照。
    return DnsResolveGuard.resolveAllAndValidate(hostname);
  }
}

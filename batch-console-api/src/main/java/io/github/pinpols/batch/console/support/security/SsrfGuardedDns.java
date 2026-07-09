package io.github.pinpols.batch.console.support.security;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.security.DnsResolveGuard;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.Dns;
import org.springframework.stereotype.Component;

/**
 * OkHttp {@link Dns} 实现:在客户端**真正建连前**的解析回调里做 SSRF 校验,并只交回 {@code DnsResolveGuard} 校验过的**那一个** 具体
 * IP。
 *
 * <p><b>为什么这样能根治 rebinding。</b>OkHttp 用本接口返回的地址直接建连,不会再对主机名二次解析——于是"校验的 IP == 连接的 IP"
 * 在同一次调用里确定成立,关闭了"校验时合法 → 建连时 DNS 已指向内网"的 TOCTOU 窗口。返回单个 IP(而非整组 A 记录)同时堵住"多 A 记录
 * (公网+内网)客户端连到组内另一个"的绕过。
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
    // resolve-then-connect:解析 + 受限网段校验合并在此,OkHttp 建连即用这个已校验 IP。
    return List.of(DnsResolveGuard.resolveAndValidate(hostname));
  }
}

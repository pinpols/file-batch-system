package io.github.pinpols.batch.common.security;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * DNS-rebinding 防护：先解析主机名，再校验解析后的 IP 是否落在受限网段。
 *
 * <p>单地址协议在建立连接前调用 {@link #resolveAndValidate(String)}；支持多地址尝试的 HTTP 客户端调用 {@link
 * #resolveAllAndValidate(String)}，并直接使用返回的同一份地址快照建连，避免“验证时合法 → 连接时 DNS 已指向内网”的 TOCTOU
 * 窗口。
 */
public final class DnsResolveGuard {

  /**
   * A3 fix(2026-05-29):dev / local 场景需要回环(MockServer / SFTP 容器)+ 私网(LAN)的 SSRF guard 开关。生产严禁开,默认
   * false。通过 system property 或 env 注入,避免触改 caller 签名。
   *
   * <p>system property: {@code batch.security.ssrf-guard.allow-private}
   *
   * <p>env: {@code BATCH_SECURITY_SSRF_GUARD_ALLOW_PRIVATE}
   */
  private static final String PROP_ALLOW_PRIVATE = "batch.security.ssrf-guard.allow-private";

  private static final String ENV_ALLOW_PRIVATE = "BATCH_SECURITY_SSRF_GUARD_ALLOW_PRIVATE";

  private DnsResolveGuard() {}

  private static boolean isAllowPrivateOverride() {
    String prop = System.getProperty(PROP_ALLOW_PRIVATE);
    if (prop != null) {
      return Boolean.parseBoolean(prop);
    }
    String env = System.getenv(ENV_ALLOW_PRIVATE);
    return env != null && Boolean.parseBoolean(env);
  }

  /**
   * 解析主机名并校验解析后的 IP 地址不在受限网段。
   *
   * @param host 待解析的主机名或 IP 字符串
   * @return 通过校验的 {@link InetAddress}
   * @throws BlockedAddressException 解析后的 IP 落在回环 / 私有 / 链路本地等受限网段
   * @throws UnknownHostException DNS 解析失败
   */
  public static InetAddress resolveAndValidate(String host) throws UnknownHostException {
    InetAddress addr = InetAddress.getByName(host);
    if (isBlocked(addr)) {
      throw new BlockedAddressException("Resolved address "
          + addr.getHostAddress()
          + " for host '"
          + host
          + "' is in a restricted network range");
    }
    return addr;
  }

  /**
   * 解析并校验主机名对应的全部地址。
   *
   * <p>任一地址落在受限网段时整体拒绝，不能过滤危险地址后继续连接；否则攻击者可以通过混合公网/私网 DNS
   * 记录控制客户端最终选择的目标。返回结果去重但保持解析器顺序，供支持 Happy Eyeballs 的客户端直接建连。
   */
  public static List<InetAddress> resolveAllAndValidate(String host) throws UnknownHostException {
    return validateResolvedAddresses(host, InetAddress.getAllByName(host));
  }

  static List<InetAddress> validateResolvedAddresses(String host, InetAddress[] resolved)
      throws UnknownHostException {
    if (EmptyChecks.isEmpty(resolved)) {
      throw new UnknownHostException("No addresses resolved for host '" + host + "'");
    }
    LinkedHashSet<InetAddress> validated = new LinkedHashSet<>();
    for (InetAddress address : resolved) {
      if (isBlocked(address)) {
        throw new BlockedAddressException("Resolved address "
            + address.getHostAddress()
            + " for host '"
            + host
            + "' is in a restricted network range");
      }
      validated.add(address);
    }
    return List.copyOf(validated);
  }

  /** 判断已解析的 IP 是否落在受限网段（回环 / 私有 / 链路本地 / IPv4-mapped IPv6 / ULA）。 */
  public static boolean isBlocked(InetAddress addr) {
    if (addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
      return true;
    }
    // A3 fix:dev / local 场景的 allow-private 开关,生产严禁开。详见 PROP_ALLOW_PRIVATE 注释。
    if (isAllowPrivateOverride()) {
      return false;
    }
    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
      return true;
    }
    byte[] bytes = addr.getAddress();
    if (bytes.length == 4) {
      int[] octets = {bytes[0] & 0xFF, bytes[1] & 0xFF, bytes[2] & 0xFF, bytes[3] & 0xFF};
      return isBlockedIpv4(octets);
    }
    if (addr instanceof Inet6Address) {
      // ::ffff:0:0/96 — IPv4-mapped IPv6: 提取内嵌 IPv4 再检查
      if (isIpv4MappedIpv6(bytes)) {
        int[] octets = {bytes[12] & 0xFF, bytes[13] & 0xFF, bytes[14] & 0xFF, bytes[15] & 0xFF};
        return isBlockedIpv4(octets);
      }
      // fc00::/7 (unique local, covers fd00::/8)
      if ((bytes[0] & 0xFE) == 0xFC) {
        return true;
      }
      // fe80::/10 (link-local)
      if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xC0) == 0x80) {
        return true;
      }
    }
    return false;
  }

  private static boolean isBlockedIpv4(int[] octets) {
    // 0.0.0.0/8（当前网络）与 10.0.0.0/8（私网）
    if (octets[0] == 0 || octets[0] == 10) {
      return true;
    }
    // 100.64.0.0/10（运营商共享地址，常被容器/VPN 内部网络使用）
    if (octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127) {
      return true;
    }
    // 127.0.0.0/8（回环）
    if (octets[0] == 127) {
      return true;
    }
    // 172.16.0.0/12
    if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) {
      return true;
    }
    // 192.168.0.0/16
    if (octets[0] == 192 && octets[1] == 168) {
      return true;
    }
    // 169.254.0.0/16 link-local
    if (octets[0] == 169 && octets[1] == 254) {
      return true;
    }
    // IETF 协议、文档和 6to4 relay 特殊地址，不应成为租户出站目标。
    if (octets[0] == 192
        && ((octets[1] == 0 && octets[2] == 0)
            || (octets[1] == 0 && octets[2] == 2)
            || (octets[1] == 88 && octets[2] == 99))) {
      return true;
    }
    // 198.18.0.0/15（基准测试网络）和 RFC 文档地址。
    if (octets[0] == 198
        && ((octets[1] == 18 || octets[1] == 19) || (octets[1] == 51 && octets[2] == 100))) {
      return true;
    }
    if (octets[0] == 203 && octets[1] == 0 && octets[2] == 113) {
      return true;
    }
    // 224.0.0.0/4 组播与 240.0.0.0/4 保留地址；mapped IPv6 也必须覆盖。
    return octets[0] >= 224;
  }

  private static boolean isIpv4MappedIpv6(byte[] bytes) {
    if (bytes.length != 16) {
      return false;
    }
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
  }
}

package com.example.batch.common.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * DNS-rebinding 防护：先解析主机名，再校验解析后的 IP 是否落在受限网段。
 *
 * <p>所有出站连接（SFTP、HTTP、Webhook、健康探针）必须在建立连接前调用 {@link #resolveAndValidate(String)}， 用返回的 {@link
 * InetAddress} 建连，避免"验证时合法 → 连接时 DNS 已指向内网"的 TOCTOU 窗口。
 */
public final class DnsResolveGuard {

  private DnsResolveGuard() {}

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
      throw new BlockedAddressException(
          "Resolved address "
              + addr.getHostAddress()
              + " for host '"
              + host
              + "' is in a restricted network range");
    }
    return addr;
  }

  /** 判断已解析的 IP 是否落在受限网段（回环 / 私有 / 链路本地 / IPv4-mapped IPv6 / ULA）。 */
  public static boolean isBlocked(InetAddress addr) {
    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
      return true;
    }
    byte[] bytes = addr.getAddress();
    // IPv4: 额外检查 169.254.0.0/16 link-local（InetAddress.isLinkLocalAddress 仅覆盖 IPv6）
    if (bytes.length == 4 && (bytes[0] & 0xFF) == 169 && (bytes[1] & 0xFF) == 254) {
      return true;
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
    // 127.0.0.0/8
    if (octets[0] == 127) {
      return true;
    }
    // 10.0.0.0/8
    if (octets[0] == 10) {
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
    return false;
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

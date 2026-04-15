package com.example.batch.console.support;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Webhook callbackUrl 安全校验器。
 *
 * <p>阻止 SSRF：拒绝回环地址（localhost/127.x/::1）、RFC 1918 私有网段（10.x、172.16-31.x、192.168.x）
 * 和链路本地地址（169.254.x）。生产模式下同时强制要求 HTTPS。
 *
 * <p>{@code batch.security.testing-open=true} 时跳过全部校验，保持本地联调体验。
 */
@Component
@RequiredArgsConstructor
public class CallbackUrlValidator {

  private final BatchSecurityProperties securityProperties;

  public void validate(String callbackUrl) {
    if (securityProperties.isTestingOpen()) {
      return;
    }

    URI uri;
    try {
      uri = new URI(callbackUrl);
    } catch (URISyntaxException e) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "callbackUrl is not a valid URL");
    }

    String scheme = uri.getScheme();
    if (!"https".equalsIgnoreCase(scheme)) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT, "callbackUrl must use HTTPS in production");
    }

    String host = uri.getHost();
    if (host == null) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "callbackUrl has no host");
    }

    if (isBlockedHost(host)) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT, "callbackUrl points to a restricted network address");
    }
  }

  private boolean isBlockedHost(String host) {
    String lower = host.toLowerCase();
    // 回环
    if (lower.equals("localhost") || lower.equals("::1")) {
      return true;
    }
    // IPv4 解析
    int[] octets = parseIpv4(lower);
    if (octets != null) {
      return isBlockedIpv4(octets);
    }
    // IPv6 地址检测（含方括号剥离）
    return isBlockedIpv6(lower);
  }

  private boolean isBlockedIpv4(int[] octets) {
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
    // 169.254.0.0/16 (link-local)
    if (octets[0] == 169 && octets[1] == 254) {
      return true;
    }
    return false;
  }

  private boolean isBlockedIpv6(String host) {
    String normalized =
        host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    InetAddress addr;
    try {
      addr = InetAddress.getByName(normalized);
    } catch (UnknownHostException e) {
      return false;
    }
    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
      return true;
    }
    if (addr instanceof Inet6Address) {
      byte[] bytes = addr.getAddress();
      // ::ffff:0:0/96 — IPv4-mapped IPv6: extract embedded IPv4 and re-check
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

  private boolean isIpv4MappedIpv6(byte[] bytes) {
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

  private int[] parseIpv4(String host) {
    String[] parts = host.split("\\.", -1);
    if (parts.length != 4) {
      return null;
    }
    int[] octets = new int[4];
    for (int i = 0; i < 4; i++) {
      try {
        octets[i] = Integer.parseInt(parts[i]);
        if (octets[i] < 0 || octets[i] > 255) {
          return null;
        }
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return octets;
  }
}

package com.example.batch.console.support;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.net.URI;
import java.net.URISyntaxException;
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
    if (octets == null) {
      return false;
    }
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

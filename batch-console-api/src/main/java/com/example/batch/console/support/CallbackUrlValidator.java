package com.example.batch.console.support;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.security.BlockedAddressException;
import com.example.batch.common.security.DnsResolveGuard;
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

    // S-2.6: DNS 解析后校验真实 IP，消除 DNS rebinding 窗口
    try {
      DnsResolveGuard.resolveAndValidate(host);
    } catch (BlockedAddressException e) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT, "callbackUrl points to a restricted network address");
    } catch (UnknownHostException e) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "callbackUrl host cannot be resolved");
    }
  }
}

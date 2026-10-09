package io.github.pinpols.batch.console.config;

import io.github.pinpols.batch.common.config.BatchProfileSupport;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 防止仅供本机/测试 IdP 使用的 HTTP issuer 例外进入生产 profile。 */
@Component
@RequiredArgsConstructor
public class ConsoleOidcProfileGuard {

  private final ConsoleOidcProperties properties;
  private final Environment environment;

  @PostConstruct
  void validate() {
    if (!properties.isAllowLoopbackHttp()) {
      return;
    }
    String[] activeProfiles = environment.getActiveProfiles();
    boolean localOrTest = Arrays.stream(activeProfiles)
        .map(profile -> profile.toLowerCase(Locale.ROOT))
        .anyMatch(profile -> profile.equals("local") || profile.equals("test"));
    if (!localOrTest || BatchProfileSupport.isProductionProfile(environment)) {
      throw new IllegalStateException(
          "batch.console.sso.oidc.allow-loopback-http is allowed only in local/test profiles");
    }
  }
}

package com.example.batch.worker.atomic.runtime;

import com.example.batch.worker.atomic.http.HttpExecutorProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Prod profile 下,把 {@link HttpExecutorProperties#isEnforceAllowlist()} 的 effective 默认从 false 翻成
 * true —— 当且仅当用户未在 yaml/env 显式配置过 {@code batch.worker.executors.http.enforce-allowlist} 时生效。
 *
 * <p>背景:Lane B 已在 {@link AtomicExecutorProductionGuard} 加 fail-fast(prod + http enabled + 空白名单 +
 * enforceAllowlist=false → 启动失败)。本组件把"必须由 ops 显式打开"降级为"隐式默认开",降低部署负担,同时保留 ops 主动显式置 false
 * 的覆盖权(显式配置不会被覆盖,只是会被 production guard 在白名单也为空时拒绝)。
 *
 * <p>dev/local/test profile 完全不触发,保持默认 false 的开发友好语义。
 *
 * <p>为什么用 {@link Environment#containsProperty(String)} 判定"是否显式配":Spring binder 已经把
 * yaml/env/系统属性都拍平到 Environment;此 key 出现 = 用户(或某个 PropertySource)真有给值。当用户显式置 false 时本组件不动手,把控制权交回
 * Guard。
 *
 * <p>切换在 {@link PostConstruct} 阶段进行 —— Properties bean 已被 binder 填好,且早于 {@link
 * AtomicExecutorProductionGuard} 的 {@code ApplicationReadyEvent} 校验,确保 Guard 看到的是 effective 默认 true
 * 的状态。
 */
@Slf4j
@Configuration
@Profile("prod")
@RequiredArgsConstructor
public class HttpExecutorProdDefaults {

  static final String PROP_ENFORCE_ALLOWLIST = "batch.worker.executors.http.enforce-allowlist";

  private final Environment environment;
  private final ObjectProvider<HttpExecutorProperties> httpProps;

  @PostConstruct
  public void applyProdDefaults() {
    HttpExecutorProperties props = httpProps.getIfAvailable();
    if (props == null) {
      log.debug("http executor properties not present, prod default skip");
      return;
    }
    if (environment.containsProperty(PROP_ENFORCE_ALLOWLIST)) {
      log.debug(
          "http enforceAllowlist explicitly configured ({}), prod default override skipped",
          props.isEnforceAllowlist());
      return;
    }
    if (!props.isEnforceAllowlist()) {
      props.setEnforceAllowlist(true);
      log.info(
          "prod profile: http enforceAllowlist defaulted to true "
              + "(no explicit {} in environment) — empty allowedHostPatterns now means deny-all",
          PROP_ENFORCE_ALLOWLIST);
    }
  }
}

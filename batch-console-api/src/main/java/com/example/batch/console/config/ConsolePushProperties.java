package com.example.batch.console.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web Push (VAPID) 配置。
 *
 * <p>VAPID 公私钥对生成命令(本地一次性):
 *
 * <pre>
 *   # 用 nl.martijndwars:web-push 自带 CLI:
 *   java -jar web-push.jar generate-key-pair
 *
 *   # 或 npx(等价输出):
 *   npx web-push generate-vapid-keys
 * </pre>
 *
 * <p>公私钥都是 base64-url-safe 编码。{@code publicKey} 通过 {@code GET /api/console/push/vapid-public-key}
 * 暴露给前端;{@code privateKey} 服务端保管,**不要进代码仓** — 用 env / 配置中心 / Vault 注入。
 *
 * <p>{@code subject} 是 mailto 或 https URL,作为 VAPID JWT 的 sub claim,出问题时推送服务 知道找谁(如 {@code
 * mailto:ops@example.com})。
 */
@ConfigurationProperties(prefix = "batch.console.push")
public class ConsolePushProperties {

  /** 是否启用 push 模块;关闭时 controller 返回 404,sender 直接 no-op。 */
  private boolean enabled = false;

  /** VAPID 公钥(base64-url),也通过 /api/console/push/vapid-public-key 暴露给前端。 */
  private String publicKey;

  /** VAPID 私钥(base64-url),服务端签名 JWT 用,不要 leak。 */
  private String privateKey;

  /** VAPID subject:mailto:xxx 或 https://yourdomain。 */
  private String subject = "mailto:ops@example.com";

  /** 单条推送 TTL(秒),浏览器推送服务超时未送达则丢弃。 */
  private int ttlSeconds = 12 * 3600;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getPublicKey() {
    return publicKey;
  }

  public void setPublicKey(String publicKey) {
    this.publicKey = publicKey;
  }

  public String getPrivateKey() {
    return privateKey;
  }

  public void setPrivateKey(String privateKey) {
    this.privateKey = privateKey;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public int getTtlSeconds() {
    return ttlSeconds;
  }

  public void setTtlSeconds(int ttlSeconds) {
    this.ttlSeconds = ttlSeconds;
  }
}

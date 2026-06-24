package io.github.pinpols.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可插拔验证码配置。登录风控只依赖 {@code CaptchaVerifier} 抽象接口,<b>换厂商只改本配置</b>(provider + 对应密钥块),不改代码。
 *
 * <ul>
 *   <li>{@code none} —— 默认,不做验证码(仅失败退避层生效)
 *   <li>{@code selfhosted} —— 自建滑块 + 后端时序/位置校验(不外联,保底)
 *   <li>{@code cloudflare} —— Cloudflare Turnstile(裸 HTTP siteverify,无云签名)
 *   <li>{@code tencent} —— 腾讯天御(TC3-HMAC-SHA256 验票,需外联)
 *   <li>{@code aliyun} —— 阿里云验证码 2.0(ACS3-HMAC-SHA256 验票,需外联)
 * </ul>
 *
 * <p><b>注</b>:tencent/aliyun 的云 API 签名为手写(零依赖),签名串结构已对官方文档校验, 但端到端签名值需上线前打一次真 API 冒烟验证(无官方 golden
 * 向量可离线比对)。
 *
 * <p>各实现以 {@code @ConditionalOnProperty(name="batch.console.captcha.provider", havingValue=...)}
 * 选装, 任一时刻只装一个;前端经 {@code GET /api/console/captcha/config} 拿 provider + siteKey 动态渲染对应 widget。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.captcha")
public class CaptchaProperties {

  /** 验证码 provider:none|selfhosted|tencent|aliyun。默认 none。 */
  private String provider = "none";

  /** 前端公开标识(站点 key / appId),经 /captcha/config 下发给 FE。selfhosted 可空。 */
  private String siteKey = "";

  /** 服务端密钥(secret / appSecretKey),仅后端校验用,<b>绝不下发 FE</b>。 */
  private String secretKey = "";

  /** 自建滑块:缺口命中容差(像素),提交位置与目标位置差 ≤ 该值算通过。默认 5。 */
  private int selfhostedTolerancePx = 5;

  /** 自建滑块:挑战有效期(秒),超时作废。默认 120。 */
  private int selfhostedChallengeTtlSeconds = 120;

  /** 自建滑块:最短人类滑动耗时(毫秒),低于此判为脚本秒过。默认 300。 */
  private long selfhostedMinElapsedMillis = 300L;

  /** 腾讯天御:CaptchaAppId(数字);siteKey 复用为前端 CaptchaAppId 字符串。 */
  private long tencentAppId = 0L;

  /** 腾讯天御校验接口地址,默认官方公网端点。私有化/代理时覆盖。 */
  private String tencentEndpoint = "https://captcha.tencentcloudapi.com";

  /** 腾讯云 API 凭证 SecretId(TC3 签名用),非 CaptchaAppId。 */
  private String tencentSecretId = "";

  /** 腾讯云 API 凭证 SecretKey(TC3 签名用),仅后端,绝不下发 FE。 */
  private String tencentSecretKey = "";

  /** 腾讯天御 CaptchaAppId 对应的 AppSecretKey(验票入参),仅后端。 */
  private String tencentAppSecretKey = "";

  // ── Cloudflare Turnstile ──────────────────────────────────────────────────
  /** Turnstile 服务端校验端点,默认官方 siteverify。 */
  private String cloudflareVerifyEndpoint =
      "https://challenges.cloudflare.com/turnstile/v0/siteverify";

  // ── 阿里云验证码 2.0 ──────────────────────────────────────────────────────
  /** 阿里云 AccessKeyId(ACS3 签名用),仅后端。 */
  private String aliyunAccessKeyId = "";

  /** 阿里云 AccessKeySecret(ACS3 签名用),仅后端,绝不下发 FE。 */
  private String aliyunAccessKeySecret = "";

  /** 阿里云验证码 OpenAPI 端点(区域相关),默认新加坡公网端点;按区域覆盖。 */
  private String aliyunEndpoint = "captcha.ap-southeast-1.aliyuncs.com";

  /** 阿里云验证码场景 SceneId(控制台创建场景后获得),校验入参。 */
  private String aliyunSceneId = "";
}

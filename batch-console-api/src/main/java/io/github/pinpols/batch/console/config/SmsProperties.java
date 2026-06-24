package io.github.pinpols.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 短信通知发送器配置（阿里云短信 SendSms）。与验证码的 {@link CaptchaProperties} 独立，<b>互不复用 AK/SK</b>：验证码 AK/SK
 * 服务范围/权限不同，短信发送必须用专属凭证。
 *
 * <p>凭证仅后端 {@code AliyunSmsNotificationSender} 调用阿里云 OpenAPI 时使用，<b>绝不下发 FE、绝不入日志</b>。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.sms")
public class SmsProperties {

  /** 短信 provider:none(默认,不发)|aliyun|tencent|twilio。决定装配哪个 {@code SmsProvider} 实现。 */
  private String provider = "none";

  // ── 阿里云短信 ──────────────────────────────────────────────────────────────
  /** 阿里云 AccessKeyId（短信专用 RAM 账号），仅后端持有。 */
  private String aliyunAccessKeyId = "";

  /** 阿里云 AccessKeySecret（短信专用），仅后端持有，绝不下发 / 入日志。 */
  private String aliyunAccessKeySecret = "";

  /** 阿里云短信 OpenAPI endpoint host，默认官方公网端点；私有化 / 代理时覆盖。 */
  private String aliyunEndpoint = "dysmsapi.aliyuncs.com";

  // ── 腾讯云短信 ──────────────────────────────────────────────────────────────
  /** 腾讯云 API SecretId（TC3 签名），仅后端持有。 */
  private String tencentSecretId = "";

  /** 腾讯云 API SecretKey（TC3 签名），仅后端持有，绝不下发 / 入日志。 */
  private String tencentSecretKey = "";

  /** 腾讯云短信 endpoint host，默认官方公网端点。 */
  private String tencentEndpoint = "sms.tencentcloudapi.com";

  /** 腾讯云短信地域（SendSms 需要），如 ap-guangzhou。 */
  private String tencentRegion = "ap-guangzhou";

  // ── Twilio ─────────────────────────────────────────────────────────────────
  /** Twilio Account SID，仅后端持有。 */
  private String twilioAccountSid = "";

  /** Twilio Auth Token，仅后端持有，绝不下发 / 入日志。 */
  private String twilioAuthToken = "";

  /** Twilio 发信号码（E.164，如 +15005550006）。 */
  private String twilioFromNumber = "";

  /** Twilio API base，默认官方;可指向代理。 */
  private String twilioApiBase = "https://api.twilio.com";
}

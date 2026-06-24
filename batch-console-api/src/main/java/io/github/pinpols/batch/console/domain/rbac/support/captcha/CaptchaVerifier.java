package io.github.pinpols.batch.console.domain.rbac.support.captcha;

/**
 * 可插拔验证码校验 SPI。登录风控只依赖本接口,具体 provider(none/selfhosted/tencent/aliyun)由配置选装。
 *
 * <p>开闭原则:<b>切换已接入 provider = 改一行配置</b>(provider=x);<b>接入全新 provider = 新增一个本接口实现类</b>,不动既有代码。
 *
 * <p>实现要求:无状态、线程安全(单例 bean,并发登录共享)。
 */
public interface CaptchaVerifier {

  /**
   * 校验前端提交的验证码凭据。
   *
   * @param token 前端过验证码后拿到的凭据(self-hosted 为 {@code challengeId:position:elapsedMillis};第三方为其
   *     ticket/randstr 等);可能为 null/空(视为未提交,直接失败)
   * @param clientIp 客户端 IP,部分 provider 校验时需带上做风控关联
   * @return 校验结果
   */
  CaptchaResult verify(String token, String clientIp);

  /** provider 标识(none/selfhosted/tencent/aliyun),用于 /captcha/config 下发与日志。 */
  String provider();
}

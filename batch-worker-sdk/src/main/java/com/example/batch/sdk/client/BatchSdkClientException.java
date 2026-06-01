package com.example.batch.sdk.client;

/**
 * SDK 客户端运行期异常 — 用于替代 {@code throw new RuntimeException(...)},给租户接入方一个**可识别、可分类**的异常类型。
 *
 * <p>设计:轻量 RuntimeException 子类,带 {@link Stage} 枚举区分发生阶段。租户 catch 时可按 stage 决定是否重启 worker (如
 * REGISTER 失败 = 配置问题,通常重试无意义;START 阶段后失败 = 运行期异常,可重试)。
 *
 * <p>不引复杂错误码体系(SDK 是纯库,避免引依赖)— 留 message + cause + stage 三要素即可。
 */
public class BatchSdkClientException extends RuntimeException {

  /** 异常发生的生命周期阶段,用于租户区分处理策略。 */
  public enum Stage {
    /** 启动期 — {@code BatchPlatformClient.start()} 调 register / dispatcher 装配 时失败。 */
    START,
    /** Worker 向平台注册时失败(HTTP / 鉴权 / 网络)。 */
    REGISTER,
    /** 配置校验失败(builder / config 不合法)。 */
    CONFIG,
    /** Shutdown 阶段失败(drain 超时 / 资源关闭异常)。 */
    SHUTDOWN
  }

  private final Stage stage;

  public BatchSdkClientException(Stage stage, String message) {
    super(message);
    this.stage = stage;
  }

  public BatchSdkClientException(Stage stage, String message, Throwable cause) {
    super(message, cause);
    this.stage = stage;
  }

  public Stage stage() {
    return stage;
  }
}

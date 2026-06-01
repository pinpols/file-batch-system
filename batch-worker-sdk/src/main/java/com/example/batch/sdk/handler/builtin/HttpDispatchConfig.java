package com.example.batch.sdk.handler.builtin;

/**
 * {@link JdbcToHttpDispatchHandler} 的开箱即用配置。
 *
 * @param taskType 注册的 taskType
 * @param selectQuery 选出待推送行的查询(固定在配置里,勿从任务参数拼接)
 * @param endpoint 推送目标 URL(每行作为一个 JSON body POST 过去)
 * @param timeoutSeconds 单次 HTTP 超时秒数(默认 30)
 * @param blockPrivateIps 是否拦截私网 / 环回 / 链路本地 / 站点本地地址(SSRF 防护,默认 true)
 * @param failFast 单条失败是否立即整体失败:true=遇错即 fail;false=单条计 failed 不中断(ADR-036 dispatch 语义,默认 false)
 */
public record HttpDispatchConfig(
    String taskType,
    String selectQuery,
    String endpoint,
    int timeoutSeconds,
    boolean blockPrivateIps,
    boolean failFast) {

  /** 默认:timeout=30s、拦私网、不 failFast(单条失败不中断整批)。 */
  public static HttpDispatchConfig defaults(String taskType, String selectQuery, String endpoint) {
    return new HttpDispatchConfig(taskType, selectQuery, endpoint, 30, true, false);
  }
}

package io.github.pinpols.batch.console.domain.ops.dto;

import java.util.List;

/**
 * 平台内置原子任务类型(sql / shell / stored_proc / http)的参数 schema + 安全闸说明,供 console FE 渲染节点配置表单。
 *
 * <p>静态元数据:console-api 不依赖 {@code batch-worker-atomic} 模块,字段定义与各 {@code *TaskExecutor} 的 {@code
 * PARAM_*} 常量 + {@code *ExecutorProperties} 安全字段对齐(单一权威源在 worker 侧,本目录为只读镜像;worker 改字段须同步本文件)。
 *
 * @param taskType 派单 taskType 标识(如 {@code sql})
 * @param displayName 给人看的名字
 * @param enabledByDefault 该执行器平台侧默认是否启用(shell 默认 false,需平台开启)
 * @param parameters 参数字段(填到 workflow_node.parameters)
 * @param securityGates 平台安全闸(只读展示,租户改不了,需找平台管理员)
 */
public record AtomicTaskTypeSchema(
    String taskType,
    String displayName,
    boolean enabledByDefault,
    List<ParamSpec> parameters,
    List<SecurityGate> securityGates) {

  /**
   * 单个参数字段定义。
   *
   * @param name parameters map 的 key
   * @param type 值类型(string / number / boolean / list / map)
   * @param required 是否必填
   * @param description 用途说明(FE 表单提示)
   */
  public record ParamSpec(String name, String type, boolean required, String description) {}

  /**
   * 单个安全闸说明(只读)。
   *
   * @param field 后端属性名(如 {@code commandWhitelist})
   * @param meaning 闸的作用
   */
  public record SecurityGate(String field, String meaning) {}
}

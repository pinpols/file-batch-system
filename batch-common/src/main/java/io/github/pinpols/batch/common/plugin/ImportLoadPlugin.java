package io.github.pinpols.batch.common.plugin;

import java.util.List;
import java.util.Map;

/** 导入 LOAD 插件：将校验后的逻辑行（map）持久化到上游系统。新实现注册为 Spring bean，通过 {@link #id()} 标识。 */
public interface ImportLoadPlugin {

  /** 稳定标识符，如 {@link WorkerPluginIds#IMPORT_LOAD_CUSTOMER_ACCOUNT}。 */
  String id();

  /** 持久化一批行数据，行结构对应 PARSE/VALIDATE 阶段产出的 NDJSON 行（通常为 camelCase 键），返回实际写入行数。 */
  int loadChunk(ImportLoadContext context, List<Map<String, Object>> records) throws Exception;

  /** 续跑场景下 chunk 重复处理的数据安全契约。enabled 续跑前由 LoadStep 校验。 */
  default IdempotencyCapability idempotencyCapability() {
    return IdempotencyCapability.UNKNOWN;
  }
}

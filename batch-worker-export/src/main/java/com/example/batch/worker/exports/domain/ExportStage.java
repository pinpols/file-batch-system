package com.example.batch.worker.exports.domain;

/**
 * 导出 Pipeline 各执行阶段枚举。
 */
public enum ExportStage {
    /** 准备阶段：解析 payload、加载模板配置、确定文件命名。 */
    PREPARE,
    /** 生成阶段：查询数据并按格式写入临时文件。 */
    GENERATE,
    /** 存储阶段：将临时文件上传至对象存储并完成校验。 */
    STORE,
    /** 注册阶段：在平台创建 file_record 并触发插件回调。 */
    REGISTER,
    /** 完成阶段：更新文件状态并写入审计日志。 */
    COMPLETE
}

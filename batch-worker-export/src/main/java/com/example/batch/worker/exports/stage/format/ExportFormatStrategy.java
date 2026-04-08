package com.example.batch.worker.exports.stage.format;

/**
 * 导出文件生成策略接口，每种格式对应一个实现。
 *
 * <p>实现类注册为 Spring Bean 后由 {@link ExportFormatStrategyRegistry} 收集，
 * 并在运行时按 {@code fileFormatType} 选择。新增格式只需增加新实现，
 * 无需修改 {@link com.example.batch.worker.exports.stage.GenerateStep}。
 */
public interface ExportFormatStrategy {

    /**
     * 返回本策略处理的格式类型标识（如 {@code "JSON"}、{@code "DELIMITED"}、
     * {@code "EXCEL"}、{@code "FIXED_WIDTH"}），注册表按大小写不敏感方式匹配。
     */
    String formatType();

    /**
     * 按 {@code ctx} 中的描述生成导出文件，并返回写入的数据记录行数。
     *
     * @param ctx 导出格式上下文
     * @return 写入的数据记录数
     * @throws Exception 生成过程中发生任何错误时抛出
     */
    long generate(ExportFormatContext ctx) throws Exception;
}

package com.example.batch.sdk.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * 自定义 taskType 描述符 — 租户 worker 通过 {@link SdkTaskHandler#descriptor()} 声明,register 时随 {@code POST
 * /internal/workers/register} 上报平台(SDK Phase 3 M3.1,见 {@code docs/plans/sdk-roadmap-2026-h2.md}
 * §5)。
 *
 * <p>平台 upsert 到 {@code batch.custom_task_type_registry},console 据 {@link #inputSchema()} 渲染表单、 据
 * {@link #defaults()} 预填,派单时合并 {@code defaults + node.parameters + 模板替换}。
 *
 * <p>对齐平台 {@code com.example.batch.common.dto.WorkerTaskTypeDescriptorDto}(字段名 1:1,跨端 jackson
 * 反序列化,见 orchestrator {@code SdkWireContractTest})。
 *
 * <p>典型用法:
 *
 * <pre>{@code
 * @Override public SdkTaskTypeDescriptor descriptor() {
 *   return SdkTaskTypeDescriptor.builder()
 *       .displayName("每日对账导入")
 *       .version("v1")
 *       .defaults(Map.of("batchSize", 1000))
 *       .inputSchema(Map.of("type", "object", "required", List.of("sourcePath")))
 *       .templateVariables(List.of("bizDate"))
 *       .build();
 * }
 * }</pre>
 *
 * <p><b>纪律</b>:敏感凭据(DB 密码 / OAuth secret)禁止走 {@link #defaults()} —— 用环境变量,见 SDK README。
 *
 * @param code taskType code;留空则 register 时框架自动填 {@link SdkTaskHandler#taskType()}(推荐留空,避免不一致)
 * @param displayName console 展示名(可选)
 * @param version descriptor 语义版本(可选,用于变更检测 + 不兼容升级灰度)
 * @param defaults 默认参数底座(可选)
 * @param inputSchema JSON Schema(可选,console 渲染表单 + 必填校验)
 * @param templateVariables 声明支持的模板变量,如 {@code bizDate}(可选)
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SdkTaskTypeDescriptor(
    String code,
    String displayName,
    String version,
    Map<String, Object> defaults,
    Map<String, Object> inputSchema,
    List<String> templateVariables) {

  /** 返回 code 已绑定的副本 —— register 装配时框架用 {@link SdkTaskHandler#taskType()} 作为权威 code。 */
  public SdkTaskTypeDescriptor withCode(String resolvedCode) {
    return new SdkTaskTypeDescriptor(
        resolvedCode, displayName, version, defaults, inputSchema, templateVariables);
  }
}

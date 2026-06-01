package com.example.batch.common.dto;

import java.util.List;
import java.util.Map;

/**
 * 平台侧 — worker register 上报的单个自定义 taskType 描述符(SDK Phase 3 M3.1)。
 *
 * <p>对齐 SDK 端 {@code com.example.batch.sdk.task.SdkTaskTypeDescriptor}(字段名必须 1:1,jackson 跨端 反序列化,见
 * orchestrator {@code SdkWireContractTest})。orchestrator register handler 据此 upsert 到 {@code
 * batch.custom_task_type_registry}(ORCH-P3-2):{@code descriptor} 全文存 JSONB,{@code code /
 * displayName / version} 提为顶层列。
 *
 * @param code taskType code(命名 {@code tenant_<tenantId>_<verb>});平台 registry 主键之一
 * @param displayName console 展示名(可选)
 * @param version descriptor 语义版本,用于变更检测 + 不兼容升级灰度(可选)
 * @param defaults 默认参数 —— 派单时与 node.parameters 合并的底座(可选)
 * @param inputSchema JSON Schema —— console 据此渲染表单 + 必填校验(可选)
 * @param templateVariables descriptor 声明支持的模板变量(如 {@code ${bizDate}});可选
 */
public record WorkerTaskTypeDescriptorDto(
    String code,
    String displayName,
    String version,
    Map<String, Object> defaults,
    Map<String, Object> inputSchema,
    List<String> templateVariables) {}

/**
 * 强类型 handler 模板族(租户公开 API)—— 框架在调用前把 {@code SdkTaskContext.parameters()} 经 Jackson 反序列化成强类型入参,
 * handler 直接拿强类型字段,省去 {@code Map<String,Object>} 瞎转型。
 *
 * <ul>
 *   <li>{@link SdkTypedTaskHandler} —— 单方法 typed 入口(入参 {@code I} → 业务结果 {@code O})。
 *   <li>{@code SdkAbstractTyped{Import,Export,Process,Dispatch}Handler} —— 在 typed 入参之上叠加 ADR-036
 *       行流 / 分批模板。
 * </ul>
 *
 * <p>入参反序列化由包内 {@code SdkTypedParameters} 统一承载(单一权威实现,各基类组合复用)。{@code
 * com.example.batch.sdk.handler} 下的「裸 Map 入参」基类是本族 {@code <Map<String,Object>, …>} 的特例。
 */
package com.example.batch.sdk.handler.typed;

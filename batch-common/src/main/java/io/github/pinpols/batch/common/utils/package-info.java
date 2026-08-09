/**
 * 通用工具类。JSpecify @NullMarked 试点包 — 包内所有 public API 默认非空,可空类型必须用 {@link
 * javax.annotation.Nullable @Nullable}(JSR-305)显式标注。SonarJava S4449 不识别 TYPE_USE 型的
 * JSpecify {@code @Nullable},为保持全仓一致统一使用 JSR-305 声明级注解;nullaway profile
 * (见根 pom.xml)默认识别该注解,编译期 null-safety 检查不受影响。
 *
 * <p>新增类必须遵循此 @NullMarked 契约。逐步扩展到 batch-common 其他子包后,迁移 batch-orchestrator / batch-trigger 等下游模块。
 */
@NullMarked
package io.github.pinpols.batch.common.utils;

import org.jspecify.annotations.NullMarked;

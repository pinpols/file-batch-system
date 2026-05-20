/**
 * 通用工具类。JSpecify @NullMarked 试点包 — 包内所有 public API 默认非空,可空类型必须用 {@link
 * org.jspecify.annotations.Nullable @Nullable} 显式标注。配合 nullaway profile (见根 pom.xml)在编译期捕获
 * null-safety 违反。
 *
 * <p>新增类必须遵循此 @NullMarked 契约。逐步扩展到 batch-common 其他子包后,迁移 batch-orchestrator / batch-trigger 等下游模块。
 */
@NullMarked
package com.example.batch.common.utils;

import org.jspecify.annotations.NullMarked;

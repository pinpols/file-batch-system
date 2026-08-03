package io.github.pinpols.batch.console.shared.view;

/** 通用下拉选项 (code, label) 投影,用于 queue / calendar / window / workerGroup / bizType 等 meta 查询。 */
public record SimpleOptionView(String code, String label) {}

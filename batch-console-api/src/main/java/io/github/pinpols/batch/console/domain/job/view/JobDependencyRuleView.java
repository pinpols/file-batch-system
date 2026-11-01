package io.github.pinpols.batch.console.domain.job.view;

/** 作业依赖的上游编码与业务日对齐方式。 */
public record JobDependencyRuleView(String upstreamJobCode, String bizDateAlignment) {}

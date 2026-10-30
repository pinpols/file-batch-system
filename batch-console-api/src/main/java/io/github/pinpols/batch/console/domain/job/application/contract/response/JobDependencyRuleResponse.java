package io.github.pinpols.batch.console.domain.job.application.contract.response;

public record JobDependencyRuleResponse(String upstreamJobCode, String bizDateAlignment) {}

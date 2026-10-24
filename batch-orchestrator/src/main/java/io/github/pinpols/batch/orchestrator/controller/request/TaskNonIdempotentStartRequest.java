package io.github.pinpols.batch.orchestrator.controller.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Worker 声明非幂等执行器即将进入产生副作用的调用。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskNonIdempotentStartRequest(
    String tenantId, String workerId, String partitionInvocationId) {}

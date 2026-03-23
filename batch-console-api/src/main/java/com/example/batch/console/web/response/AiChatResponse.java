package com.example.batch.console.web.response;

import lombok.Data;

@Data
public class AiChatResponse {

    private String requestId;
    private String traceId;
    private String sessionId;
    private String promptCategory;
    private String promptDecision;
    private String modelName;
    private String answer;
    private String refusalReason;
}

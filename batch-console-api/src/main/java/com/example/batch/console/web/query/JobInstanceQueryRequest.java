package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class JobInstanceQueryRequest extends PageQueryRequest {

    private String tenantId;
    private String jobCode;
    private String instanceNo;
    private String instanceStatus;
    private String bizDate;
    private String traceId;
    private String startDate;
    private String endDate;

    /** 排序方式：id（默认）、duration（按运行时长降序，用于慢任务诊断）。 */
    private String sortBy;

    /** 最小运行时长过滤（秒）：仅返回运行时长 ≥ 该值的实例，用于慢任务诊断。 */
    private Integer minDurationSeconds;
}

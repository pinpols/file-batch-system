package com.example.batch.common.model;

import lombok.Data;

import java.util.Set;

@Data
public class WorkerRouteModel {

    /**
     * 用于路由或分配的目标 worker 编码。
     *
     * <p>该字段与运行时日志、心跳里使用的 worker 实例 id 分开保存。 现有调用方仍可通过兼容访问器 {@code workerId} 读取或设置。
     */
    private String workerCode;

    private String workerType;
    private Set<String> capabilityTags;
    private String resourceProfile;
    private Integer priority;
    private Boolean available;

    public String getWorkerId() {
        return workerCode;
    }

    public void setWorkerId(String workerId) {
        this.workerCode = workerId;
    }
}

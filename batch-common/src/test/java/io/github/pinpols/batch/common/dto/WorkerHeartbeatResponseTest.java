package io.github.pinpols.batch.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;

@DisplayName("心跳平台指令: 注册状态到 platformStatus/shouldDrain 的映射")
class WorkerHeartbeatResponseTest {

    @Test
    @DisplayName("在线节点下发 NORMAL 且不要求排空")
    void shouldMapOnlineToNormal() {
        WorkerHeartbeatResponse response =
                WorkerHeartbeatResponse.fromWorkerState(WorkerRegistryStatus.ONLINE.code(), 8);

        assertThat(response.platformStatus()).isEqualTo(WorkerHeartbeatResponse.STATUS_NORMAL);
        assertThat(response.shouldDrain()).isFalse();
        assertThat(response.desiredMaxConcurrent()).isEqualTo(8);
    }

    @Test
    @DisplayName("排空节点下发 DRAINING 并要求停止认领")
    void shouldMapDrainingToDrainingDirective() {
        WorkerHeartbeatResponse response =
                WorkerHeartbeatResponse.fromWorkerState(WorkerRegistryStatus.DRAINING.code(), 8);

        assertThat(response.platformStatus()).isEqualTo(WorkerHeartbeatResponse.STATUS_DRAINING);
        assertThat(response.shouldDrain()).isTrue();
    }

    @Test
    @DisplayName("退役节点下发 DECOMMISSIONED，与 DRAINING 区分但同样停止认领")
    void shouldMapDecommissionedToOwnDirective() {
        WorkerHeartbeatResponse response =
                WorkerHeartbeatResponse.fromWorkerState(WorkerRegistryStatus.DECOMMISSIONED.code(), 8);

        // 若这里退化成 DRAINING，worker 会把上报状态改写为 DRAINING，进程重启后无法重新上线。
        assertThat(response.platformStatus()).isEqualTo(WorkerHeartbeatResponse.STATUS_DECOMMISSIONED);
        assertThat(response.shouldDrain()).isTrue();
    }

    @Test
    @DisplayName("未知状态按正常下发，不误触发排空")
    void shouldFallbackToNormalForUnknownStatus() {
        WorkerHeartbeatResponse response = WorkerHeartbeatResponse.fromWorkerState("RUNNING", 8);

        assertThat(response.platformStatus()).isEqualTo(WorkerHeartbeatResponse.STATUS_NORMAL);
        assertThat(response.shouldDrain()).isFalse();
    }
}

package io.github.pinpols.batch.worker.processes.cleanup;

import java.time.Instant;

/** 只要仍有派发中的/兄弟节点活动，共用收尾器就不执行；当本次跳过关闭最后一个待处理节点后， */
public record ProcessStagingGenerationObservation(long overdueRows, Instant oldestRowAt) {}

/** 验证重复派发不覆盖仍在执行的租约，也不越过未完成的 Kafka offset。 */
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  FakeConsumer,
  type ConsumerRecord,
  type MessageDisposition,
} from "../src/client/consumer.ts";
import { WorkerLifecycle } from "../src/client/lifecycle.ts";
import { FakePlatform } from "../src/client/testkit.ts";
import { taskSuccess, type TaskContext } from "../src/client/handler.ts";
import { NotFoundTransportError, RevokedTransportError } from "../src/client/transport.ts";

class ControlledConsumer extends FakeConsumer {
  #onMessage?: (record: ConsumerRecord) => Promise<MessageDisposition>;

  override async start(
    onMessage: (record: ConsumerRecord) => Promise<MessageDisposition>,
  ): Promise<void> {
    this.#onMessage = onMessage;
  }

  deliver(taskId = "task-1"): Promise<MessageDisposition> {
    assert.ok(this.#onMessage);
    return this.#onMessage({
      value: JSON.stringify({ taskId, tenantId: "tenant-A", schemaVersion: "v1" }),
    });
  }
}

const config = { tenantId: "tenant-A", workerCode: "w1", maxConcurrent: 4 };
const logger = { info: () => {}, warn: () => {}, error: () => {} };

async function within<T>(promise: Promise<T>): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      promise,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error("等待超时")), 1000);
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

test("重复派发不覆盖原执行的租约和取消信号，重复 offset 暂不提交", async () => {
  const platform = new FakePlatform({
    claim: { partitionInvocationId: "inv-current", effectiveConfig: {} },
  });
  const consumer = new ControlledConsumer();
  let starts = 0;
  let markRenewed = () => {};
  const renewed = new Promise<void>((resolve) => {
    markRenewed = resolve;
  });
  platform.transport.renew = async (_taskId, body) => {
    const details = body.details as { executionStarted?: boolean } | undefined;
    if (details?.executionStarted) {
      starts += 1;
      if (starts > 1) throw new RevokedTransportError("duplicate attempt");
      return { cancelRequested: false };
    }
    markRenewed();
    return { cancelRequested: true };
  };
  let release = () => {};
  const blocked = new Promise<void>((resolve) => {
    release = resolve;
  });
  let markStarted: (context: TaskContext) => void = () => {};
  const started = new Promise<TaskContext>((resolve) => {
    markStarted = resolve;
  });
  const lifecycle = new WorkerLifecycle({
    config,
    transport: platform.transport,
    consumer,
    logger,
    installSignalHandlers: false,
    leaseRenewIntervalMs: 20,
    handler: {
      execute: async (context) => {
        markStarted(context);
        await blocked;
        return taskSuccess();
      },
    },
  });
  try {
    await lifecycle.start();
    assert.equal(await consumer.deliver(), "commit");
    const context = await within(started);
    const duplicate = await consumer.deliver();
    await new Promise<void>((resolve) => setImmediate(resolve));
    assert.equal(lifecycle.inFlightCount, 1, "重复消息不能删除原执行的登记");
    assert.equal(duplicate, "withhold");
    await within(renewed);
    assert.equal(context.cancellation.isCancellationRequested, true);
  } finally {
    release();
    await lifecycle.stop(1000);
  }
  assert.equal(lifecycle.inFlightCount, 0, "原执行结束后必须清理登记");
});

test("认领尚未完成时重复派发及认领失败都不得提交 offset", async () => {
  const platform = new FakePlatform();
  const consumer = new ControlledConsumer();
  let releaseClaim = () => {};
  let markClaimStarted = () => {};
  const claimStarted = new Promise<void>((resolve) => {
    markClaimStarted = resolve;
  });
  const waitClaim = new Promise<void>((resolve) => {
    releaseClaim = resolve;
  });
  platform.transport.claim = async () => {
    markClaimStarted();
    await waitClaim;
    throw new Error("claim unavailable");
  };
  const lifecycle = new WorkerLifecycle({
    config,
    transport: platform.transport,
    consumer,
    logger,
    installSignalHandlers: false,
    handler: {
      execute: async () => {
        throw new Error("未认领不能执行业务");
      },
    },
  });
  try {
    await lifecycle.start();
    const first = consumer.deliver();
    await within(claimStarted);
    const duplicate = await consumer.deliver();
    releaseClaim();
    assert.equal(await first, "withhold");
    assert.equal(duplicate, "withhold");
  } finally {
    releaseClaim();
    await lifecycle.stop(1000);
  }
});

test("任务不存在时保留认领 404 的跳过提交语义", async () => {
  const platform = new FakePlatform();
  const consumer = new ControlledConsumer();
  platform.transport.claim = async () => {
    throw new NotFoundTransportError("task missing");
  };
  const lifecycle = new WorkerLifecycle({
    config,
    transport: platform.transport,
    consumer,
    logger,
    installSignalHandlers: false,
    handler: {
      execute: async () => {
        throw new Error("任务不存在不能执行业务");
      },
    },
  });
  try {
    await lifecycle.start();
    assert.equal(await consumer.deliver(), "commit");
  } finally {
    await lifecycle.stop(1000);
  }
});

test("停机开始后认领才成功时不启动业务并保留 offset", async () => {
  const platform = new FakePlatform();
  const consumer = new ControlledConsumer();
  let markStarted = () => {};
  const started = new Promise<void>((resolve) => {
    markStarted = resolve;
  });
  let release = () => {};
  const claimWait = new Promise<void>((resolve) => {
    release = resolve;
  });
  platform.transport.claim = async () => {
    markStarted();
    await claimWait;
    return { partitionInvocationId: "inv-current", effectiveConfig: {} };
  };
  let executions = 0;
  const lifecycle = new WorkerLifecycle({
    config,
    transport: platform.transport,
    consumer,
    logger,
    installSignalHandlers: false,
    handler: {
      execute: async () => {
        executions += 1;
        return taskSuccess();
      },
    },
  });
  try {
    await lifecycle.start();
    const delivery = consumer.deliver();
    await within(started);
    const stopping = lifecycle.stop(1000);
    assert.equal(lifecycle.isDraining, true);
    release();
    assert.equal(await within(delivery), "withhold");
    await within(stopping);
    assert.equal(executions, 0);
    assert.equal(lifecycle.inFlightCount, 0);
  } finally {
    release();
    await lifecycle.stop(1000);
  }
});

test("旧任务迟到的续租拒绝不影响同 task 后续执行", async () => {
  const platform = new FakePlatform({
    claim: { partitionInvocationId: "inv-current", effectiveConfig: {} },
  });
  const consumer = new ControlledConsumer();
  let markFirstRenew = () => {};
  const firstRenew = new Promise<void>((resolve) => {
    markFirstRenew = resolve;
  });
  let rejectOldRenew: (reason: Error) => void = () => {};
  const oldRenew = new Promise<never>((_, reject) => {
    rejectOldRenew = reject;
  });
  let markNewRenew = () => {};
  const newRenew = new Promise<void>((resolve) => {
    markNewRenew = resolve;
  });
  let renews = 0;
  platform.transport.renew = async (_taskId, body) => {
    if ((body.details as { executionStarted?: boolean } | undefined)?.executionStarted) {
      return { cancelRequested: false };
    }
    renews += 1;
    if (renews === 1) {
      markFirstRenew();
      return await oldRenew;
    }
    markNewRenew();
    return { cancelRequested: false };
  };
  let releaseFirst = () => {};
  let releaseSecond = () => {};
  const firstBlocked = new Promise<void>((resolve) => {
    releaseFirst = resolve;
  });
  const secondBlocked = new Promise<void>((resolve) => {
    releaseSecond = resolve;
  });
  let firstStarted = () => {};
  let secondStarted: (context: TaskContext) => void = () => {};
  const first = new Promise<void>((resolve) => {
    firstStarted = resolve;
  });
  const second = new Promise<TaskContext>((resolve) => {
    secondStarted = resolve;
  });
  let firstReported = () => {};
  const reported = new Promise<void>((resolve) => {
    firstReported = resolve;
  });
  const report = platform.transport.report.bind(platform.transport);
  platform.transport.report = async (...args) => {
    await report(...args);
    firstReported();
  };
  let executions = 0;
  const lifecycle = new WorkerLifecycle({
    config,
    transport: platform.transport,
    consumer,
    logger,
    installSignalHandlers: false,
    leaseRenewIntervalMs: 20,
    handler: {
      execute: async (context) => {
        executions += 1;
        if (executions === 1) {
          firstStarted();
          await firstBlocked;
        } else {
          secondStarted(context);
          await secondBlocked;
        }
        return taskSuccess();
      },
    },
  });
  try {
    await lifecycle.start();
    assert.equal(await consumer.deliver(), "commit");
    await within(first);
    await within(firstRenew);
    releaseFirst();
    await within(reported);
    await new Promise<void>((resolve) => setImmediate(resolve));
    assert.equal(await consumer.deliver(), "commit");
    const context = await within(second);
    rejectOldRenew(new RevokedTransportError("old lease revoked"));
    await within(newRenew);
    assert.equal(lifecycle.inFlightCount, 1);
    assert.equal(context.cancellation.isCancellationRequested, false);
  } finally {
    releaseFirst();
    releaseSecond();
    rejectOldRenew(new Error("测试结束"));
    await lifecycle.stop(1000);
  }
});

for (const customBody of [false, true]) {
  test(`续租快照过期时不误用新执行身份（自定义请求体：${customBody}）`, async () => {
    const platform = new FakePlatform();
    const consumer = new ControlledConsumer();
    let currentB = "";
    let bClaims = 0;
    platform.transport.claim = async (taskId) => {
      const invocation = taskId === "task-B" ? `inv-B-${++bClaims}` : "inv-A";
      if (taskId === "task-B") currentB = invocation;
      return { partitionInvocationId: invocation, effectiveConfig: {} };
    };
    let releaseAFirst = () => {};
    let releaseASecond = () => {};
    let releaseBOld = () => {};
    let releaseBNew = () => {};
    const waitAFirst = new Promise<void>((resolve) => {
      releaseAFirst = resolve;
    });
    const waitASecond = new Promise<void>((resolve) => {
      releaseASecond = resolve;
    });
    const waitBOld = new Promise<void>((resolve) => {
      releaseBOld = resolve;
    });
    const waitBNew = new Promise<void>((resolve) => {
      releaseBNew = resolve;
    });
    let markAFirst = () => {};
    let markASecond = () => {};
    let markBOldReported = () => {};
    let markBNewStarted: (context: TaskContext) => void = () => {};
    let markBNewRenewed = () => {};
    const aFirst = new Promise<void>((resolve) => {
      markAFirst = resolve;
    });
    const aSecond = new Promise<void>((resolve) => {
      markASecond = resolve;
    });
    const bOldReported = new Promise<void>((resolve) => {
      markBOldReported = resolve;
    });
    const bNewStarted = new Promise<TaskContext>((resolve) => {
      markBNewStarted = resolve;
    });
    const bNewRenewed = new Promise<void>((resolve) => {
      markBNewRenewed = resolve;
    });
    const renewB: unknown[] = [];
    let aRenewals = 0;
    platform.transport.renew = async (taskId, body) => {
      if ((body.details as { executionStarted?: boolean } | undefined)?.executionStarted) {
        return { cancelRequested: false };
      }
      if (taskId === "task-A") {
        aRenewals += 1;
        if (aRenewals === 1) {
          markAFirst();
          await waitAFirst;
        }
        if (aRenewals === 2) {
          markASecond();
          await waitASecond;
        }
      } else {
        renewB.push(body.partitionInvocationId);
        markBNewRenewed();
        return { cancelRequested: true };
      }
      return { cancelRequested: false };
    };
    const report = platform.transport.report.bind(platform.transport);
    platform.transport.report = async (...args) => {
      await report(...args);
      if (args[0] === "task-B" && bClaims === 1) markBOldReported();
    };
    let bExecutions = 0;
    const lifecycle = new WorkerLifecycle({
      config: {
        ...config,
        ...(customBody
          ? {
              buildRenewBody: (taskId: string) => ({
                tenantId: "tenant-A",
                workerId: "w1",
                partitionInvocationId: taskId === "task-B" ? currentB : "inv-A",
              }),
            }
          : {}),
      },
      transport: platform.transport,
      consumer,
      logger,
      installSignalHandlers: false,
      leaseRenewIntervalMs: 20,
      handler: {
        execute: async (context) => {
          if (context.taskId === "task-B") {
            bExecutions += 1;
            if (bExecutions === 1) await waitBOld;
            else {
              markBNewStarted(context);
              await waitBNew;
            }
          } else await waitBNew;
          return taskSuccess();
        },
      },
    });
    try {
      await lifecycle.start();
      assert.equal(await consumer.deliver("task-A"), "commit");
      assert.equal(await consumer.deliver("task-B"), "commit");
      await within(aFirst);
      releaseBOld();
      await within(bOldReported);
      await new Promise<void>((resolve) => setImmediate(resolve));
      assert.equal(await consumer.deliver("task-B"), "commit");
      const newContext = await within(bNewStarted);
      releaseAFirst();
      await within(aSecond);
      assert.deepEqual(renewB, [], "过期快照不能替新执行发送续租请求");
      releaseASecond();
      await within(bNewRenewed);
      await new Promise<void>((resolve) => setImmediate(resolve));
      assert.deepEqual(renewB, ["inv-B-2"]);
      assert.equal(newContext.cancellation.isCancellationRequested, true);
    } finally {
      releaseAFirst();
      releaseASecond();
      releaseBOld();
      releaseBNew();
      await lifecycle.stop(1000);
    }
  });
}

test("停机超时注销后收到执行开始确认不能启动业务", async () => {
  const platform = new FakePlatform();
  const consumer = new ControlledConsumer();
  let markRenewStarted = () => {};
  const renewStarted = new Promise<void>((resolve) => {
    markRenewStarted = resolve;
  });
  let releaseRenew = () => {};
  const waitRenew = new Promise<void>((resolve) => {
    releaseRenew = resolve;
  });
  let deactivated = false;
  const deactivate = platform.transport.deactivate.bind(platform.transport);
  platform.transport.deactivate = async (workerCode) => {
    await deactivate(workerCode);
    deactivated = true;
  };
  platform.transport.renew = async (_taskId, body) => {
    if ((body.details as { executionStarted?: boolean } | undefined)?.executionStarted) {
      markRenewStarted();
      await waitRenew;
    }
    return { cancelRequested: false };
  };
  let executions = 0;
  const lifecycle = new WorkerLifecycle({
    config,
    transport: platform.transport,
    consumer,
    logger,
    installSignalHandlers: false,
    handler: {
      execute: async () => {
        executions += 1;
        return taskSuccess();
      },
    },
  });
  try {
    await lifecycle.start();
    assert.equal(await consumer.deliver(), "commit");
    await within(renewStarted);
    await lifecycle.stop(30);
    assert.equal(deactivated, true);
    releaseRenew();
    await new Promise<void>((resolve) => setImmediate(resolve));
    assert.equal(executions, 0, "注销后的迟到确认不能启动业务");
  } finally {
    releaseRenew();
    await lifecycle.stop(1000);
  }
});

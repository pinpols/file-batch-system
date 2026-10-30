/** 执行入口确认必须先于业务，拒绝或响应未知不能伪造业务失败回报。 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { WorkerLifecycle } from "../src/client/lifecycle.ts";
import { FakePlatform } from "../src/client/testkit.ts";
import { taskSuccess } from "../src/client/handler.ts";
import { RevokedTransportError } from "../src/client/transport.ts";

const config = { tenantId: "tenant-A", workerCode: "w1", maxConcurrent: 2 };
const logger = { info: () => {}, warn: () => {}, error: () => {} };

for (const scenario of ["confirmed", "revoked", "unknown", "cancelled"] as const) {
  test(`执行入口 ${scenario} 保留身份和业务边界`, async () => {
    const platform = new FakePlatform({
      claim: { partitionInvocationId: "inv-current", effectiveConfig: {} },
      renew: () => {
        if (scenario === "revoked") throw new RevokedTransportError("revoked");
        if (scenario === "unknown") throw new Error("injected transport failure");
        return { cancelRequested: scenario === "cancelled" };
      },
    });
    platform.feedMessages({
      taskId: "task-1",
      tenantId: "tenant-A",
      schemaVersion: "v1",
      runtimeAttributes: { partitionInvocationId: "inv-stale" },
    });
    let executions = 0;
    const lifecycle = new WorkerLifecycle({
      config,
      transport: platform.transport,
      consumer: platform.consumer,
      logger,
      installSignalHandlers: false,
      handler: {
        execute: async () => {
          const start = platform.transport.calls.find((call) => call.op === "renew");
          assert.ok(start, "业务执行前必须确认开始");
          executions += 1;
          return taskSuccess({}, "ok");
        },
      },
    });
    try {
      await lifecycle.start();
      await lifecycle.stop(1000);
      const starts = platform.transport.calls.filter((call) => call.op === "renew");
      assert.equal(starts.length, 1);
      assert.deepEqual(starts[0].args, [
        "task-1",
        {
          tenantId: "tenant-A",
          workerId: "w1",
          partitionInvocationId: "inv-current",
          details: { executionStarted: true },
        },
      ]);
      assert.equal(executions, scenario === "confirmed" ? 1 : 0);
      const reports = platform.transport.calls.filter((call) => call.op === "report");
      assert.equal(reports.length, scenario === "confirmed" || scenario === "cancelled" ? 1 : 0);
      if (scenario === "cancelled")
        assert.equal((reports[0].args[1] as { errorCode: string }).errorCode, "CANCELLED");
    } finally {
      await lifecycle.stop(1000);
    }
  });
}

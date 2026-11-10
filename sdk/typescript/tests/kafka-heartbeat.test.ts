/** 验证认领等待期间的 Kafka 心跳与位点屏障。 */
import { test, mock } from "node:test";
import assert from "node:assert/strict";
import { Kafka } from "kafkajs";
import { KafkaConsumerAdapter } from "../src/kafka/kafkaConsumer.ts";

test("认领尚未完成时持续发送 Kafka 心跳，但不提交消息位点", async (context) => {
  context.mock.timers.enable({ apis: ["setTimeout"] });
  const commits: string[] = [];
  let eachMessage: (payload: unknown) => Promise<void> = async () => {};
  const fake = {
    events: { GROUP_JOIN: "group.join" },
    on: () => {},
    connect: async () => {},
    subscribe: async () => {},
    disconnect: async () => {},
    run: async (options: { eachMessage: (payload: unknown) => Promise<void> }) => {
      eachMessage = options.eachMessage;
    },
    commitOffsets: async (offsets: { offset: string }[]) => {
      commits.push(offsets[0].offset);
    },
  };
  const stub = mock.method(Kafka.prototype, "consumer", () => fake as never);
  const adapter = new KafkaConsumerAdapter(
    { brokers: ["unused:9092"], tenantId: "test", workerCode: "test" },
    { info: () => {}, warn: () => {}, error: () => {} },
  );
  let releaseClaim: () => void = () => {};
  let markStarted: () => void = () => {};
  const claimStarted = new Promise<void>((resolve) => {
    markStarted = resolve;
  });
  const claimReleased = new Promise<void>((resolve) => {
    releaseClaim = resolve;
  });
  let heartbeats = 0;
  try {
    await adapter.start(async () => {
      markStarted();
      await claimReleased;
      return "commit";
    });
    const handling = eachMessage({
      topic: "topic",
      partition: 0,
      message: { offset: "7", value: Buffer.from("record") },
      heartbeat: async () => {
        heartbeats++;
      },
    });
    await claimStarted;
    context.mock.timers.tick(3_000);
    await Promise.resolve();
    assert.equal(heartbeats, 1, "等待认领期间必须维持 Kafka 消费组心跳");
    assert.deepEqual(commits, ["7"], "认领完成前不得提交当前消息");
    context.mock.timers.tick(3_000);
    await Promise.resolve();
    assert.equal(heartbeats, 2, "持续等待期间应按间隔继续发送心跳");
    releaseClaim();
    await handling;
    assert.deepEqual(commits, ["7", "8"]);
    context.mock.timers.tick(6_000);
    assert.equal(heartbeats, 2, "认领完成后不得继续发送心跳");
  } finally {
    releaseClaim();
    await adapter.disconnect();
    stub.mock.restore();
    context.mock.timers.reset();
  }
});

test("停止期间暂扣消息不会新建分区重试定时器", async () => {
  const delays: number[] = [];
  const originalSetTimeout = globalThis.setTimeout;
  const timerStub = mock.method(globalThis, "setTimeout", (callback, delay, ...args) => {
    delays.push(Number(delay));
    return originalSetTimeout(callback, delay, ...args);
  });
  let eachMessage: (payload: unknown) => Promise<void> = async () => {};
  let processing: Promise<void> = Promise.resolve();
  let markStop: () => void = () => {};
  const stopStarted = new Promise<void>((resolve) => {
    markStop = resolve;
  });
  const fake = {
    events: { GROUP_JOIN: "group.join" },
    on: () => {},
    connect: async () => {},
    subscribe: async () => {},
    disconnect: async () => {},
    run: async (options: { eachMessage: (payload: unknown) => Promise<void> }) => {
      eachMessage = options.eachMessage;
    },
    commitOffsets: async () => {},
    pause: () => {},
    seek: () => {},
    stop: async () => {
      markStop();
      await processing;
    },
  };
  const stub = mock.method(Kafka.prototype, "consumer", () => fake as never);
  const adapter = new KafkaConsumerAdapter(
    { brokers: ["unused:9092"], tenantId: "test", workerCode: "test" },
    { info: () => {}, warn: () => {}, error: () => {} },
  );
  let releaseClaim: () => void = () => {};
  let markStarted: () => void = () => {};
  const claimStarted = new Promise<void>((resolve) => {
    markStarted = resolve;
  });
  const claimReleased = new Promise<void>((resolve) => {
    releaseClaim = resolve;
  });
  try {
    await adapter.start(async () => {
      markStarted();
      await claimReleased;
      return "withhold";
    });
    processing = eachMessage({
      topic: "topic",
      partition: 0,
      message: { offset: "7", value: Buffer.from("record") },
      heartbeat: async () => {},
    });
    await claimStarted;
    const stopping = adapter.wakeup();
    await stopStarted;
    releaseClaim();
    await stopping;
    assert.equal(delays.filter((delay) => delay === 2_000).length, 0);
  } finally {
    releaseClaim();
    await adapter.disconnect();
    stub.mock.restore();
    timerStub.mock.restore();
  }
});

test("Kafka 心跳失败时不提交仍在认领的消息", async (context) => {
  context.mock.timers.enable({ apis: ["setTimeout"] });
  const commits: string[] = [];
  let eachMessage: (payload: unknown) => Promise<void> = async () => {};
  const fake = {
    events: { GROUP_JOIN: "group.join" },
    on: () => {},
    connect: async () => {},
    subscribe: async () => {},
    disconnect: async () => {},
    run: async (options: { eachMessage: (payload: unknown) => Promise<void> }) => {
      eachMessage = options.eachMessage;
    },
    commitOffsets: async (offsets: { offset: string }[]) => {
      commits.push(offsets[0].offset);
    },
  };
  const stub = mock.method(Kafka.prototype, "consumer", () => fake as never);
  const adapter = new KafkaConsumerAdapter(
    { brokers: ["unused:9092"], tenantId: "test", workerCode: "test" },
    { info: () => {}, warn: () => {}, error: () => {} },
  );
  let releaseClaim: () => void = () => {};
  let markStarted: () => void = () => {};
  const claimStarted = new Promise<void>((resolve) => {
    markStarted = resolve;
  });
  const claimReleased = new Promise<void>((resolve) => {
    releaseClaim = resolve;
  });
  let heartbeats = 0;
  try {
    await adapter.start(async () => {
      markStarted();
      await claimReleased;
      return "commit";
    });
    const handling = eachMessage({
      topic: "topic",
      partition: 0,
      message: { offset: "7", value: Buffer.from("record") },
      heartbeat: async () => {
        heartbeats++;
        throw new Error("heartbeat failed");
      },
    });
    const rejected = assert.rejects(handling, /heartbeat failed/);
    await claimStarted;
    context.mock.timers.tick(3_000);
    await rejected;
    releaseClaim();
    await Promise.resolve();
    context.mock.timers.tick(9_000);
    assert.equal(heartbeats, 1, "心跳失败后不再调度心跳");
    assert.deepEqual(commits, ["7"], "心跳失败后不得提交消息位点");
  } finally {
    releaseClaim();
    await adapter.disconnect();
    stub.mock.restore();
    context.mock.timers.reset();
  }
});

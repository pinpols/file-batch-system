/** 验证新分配的读取位置在执行消息处理前已经持久化。 */
import { test, mock } from "node:test";
import assert from "node:assert/strict";
import { Kafka } from "kafkajs";
import { createRequire } from "node:module";
import { KafkaConsumerAdapter } from "../src/kafka/kafkaConsumer.ts";

test("首次处理前固定当前位点，避免处理中转交时按 latest 跳过", async () => {
  const commits: string[] = [];
  let eachMessage: (payload: unknown) => Promise<void> = async () => {};
  const fake = {
    events: { GROUP_JOIN: "group.join" },
    on: () => {},
    connect: async () => {},
    subscribe: async () => {},
    run: async (options: { eachMessage: (payload: unknown) => Promise<void> }) => {
      eachMessage = options.eachMessage;
    },
    commitOffsets: async (offsets: { offset: string }[]) => {
      commits.push(offsets[0].offset);
    },
    pause: () => {},
    seek: () => {},
    disconnect: async () => {},
  };
  const stub = mock.method(Kafka.prototype, "consumer", () => fake as never);
  const adapter = new KafkaConsumerAdapter(
    { brokers: ["unused:9092"], tenantId: "test", workerCode: "test" },
    { info: () => {}, warn: () => {}, error: () => {} },
  );
  try {
    await adapter.start(async () => {
      assert.deepEqual(commits, ["7"], "处理开始前游标应指向当前消息");
      return "withhold";
    });
    await eachMessage({
      topic: "topic",
      partition: 0,
      message: { offset: "7", value: Buffer.from("record") },
    });
  } finally {
    await adapter.disconnect();
    stub.mock.restore();
  }
});

test("固定读取位置期间发生重分配时不处理旧代消息", async () => {
  let eachMessage: (payload: unknown) => Promise<void> = async () => {};
  let onJoin: (event: unknown) => void = () => {};
  let finishCommit = () => {};
  let commitStarted = () => {};
  const started = new Promise<void>((resolve) => {
    commitStarted = resolve;
  });
  const blocked = new Promise<void>((resolve) => {
    finishCommit = resolve;
  });
  let calls = 0;
  const fake = {
    events: { GROUP_JOIN: "group.join" },
    on: (_event: string, callback: (event: unknown) => void) => {
      onJoin = callback;
    },
    connect: async () => {},
    subscribe: async () => {},
    disconnect: async () => {},
    run: async (options: { eachMessage: (payload: unknown) => Promise<void> }) => {
      eachMessage = options.eachMessage;
    },
    commitOffsets: async () => {
      commitStarted();
      await blocked;
    },
  };
  const stub = mock.method(Kafka.prototype, "consumer", () => fake as never);
  const adapter = new KafkaConsumerAdapter(
    { brokers: ["unused:9092"], tenantId: "test", workerCode: "test" },
    { info: () => {}, warn: () => {}, error: () => {} },
  );
  try {
    await adapter.start(async () => {
      calls++;
      return "commit";
    });
    const handling = eachMessage({
      topic: "topic",
      partition: 0,
      message: { offset: "7", value: Buffer.from("record") },
    });
    await started;
    onJoin({ payload: { memberAssignment: { topic: [0] } } });
    finishCommit();
    await handling;
    assert.equal(calls, 0, "旧分配的消息不能进入业务处理");
  } finally {
    finishCommit();
    await adapter.disconnect();
    stub.mock.restore();
  }
});

test("首次位置保存失败时不执行业务，后续消息仍需重新保存", async () => {
  let eachMessage: (payload: unknown) => Promise<void> = async () => {};
  let attempts = 0;
  let handled = 0;
  const fake = {
    events: { GROUP_JOIN: "group.join" },
    on: () => {},
    connect: async () => {},
    subscribe: async () => {},
    disconnect: async () => {},
    run: async (options: { eachMessage: (payload: unknown) => Promise<void> }) => {
      eachMessage = options.eachMessage;
    },
    commitOffsets: async () => {
      if (++attempts === 1) throw new Error("broker unavailable");
    },
  };
  const stub = mock.method(Kafka.prototype, "consumer", () => fake as never);
  const adapter = new KafkaConsumerAdapter(
    { brokers: ["unused:9092"], tenantId: "test", workerCode: "test" },
    { info: () => {}, warn: () => {}, error: () => {} },
  );
  try {
    await adapter.start(async () => {
      handled++;
      return "commit";
    });
    const payload = {
      topic: "topic",
      partition: 0,
      message: { offset: "7", value: Buffer.from("record") },
    };
    await assert.rejects(eachMessage(payload), /broker unavailable/);
    assert.equal(handled, 0);
    await eachMessage(payload);
    assert.equal(handled, 1);
    assert.equal(attempts, 3, "应重试起点并在业务完成后提交下一位点");
  } finally {
    await adapter.disconnect();
    stub.mock.restore();
  }
});

test("全局暂停期间暂扣分区不会恢复其他分区", async () => {
  const require = createRequire(import.meta.url);
  const SubscriptionState = require("kafkajs/src/consumer/subscriptionState");
  const state = new SubscriptionState();
  state.assign([{ topic: "topic", partitions: [0, 1] }]);
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
    commitOffsets: async () => {},
    seek: () => {},
    pause: (partitions: unknown) => state.pause(partitions),
    resume: (partitions: unknown) => state.resume(partitions),
    paused: () => state.paused(),
  };
  const stub = mock.method(Kafka.prototype, "consumer", () => fake as never);
  const adapter = new KafkaConsumerAdapter(
    { brokers: ["unused:9092"], tenantId: "test", workerCode: "test" },
    { info: () => {}, warn: () => {}, error: () => {} },
  );
  try {
    await adapter.start(async () => {
      adapter.pause();
      return "withhold";
    });
    await eachMessage({
      topic: "topic",
      partition: 0,
      message: { offset: "0", value: Buffer.from("record") },
    });
    assert.equal(state.isPaused("topic", 1), true, "全局暂停不应被分区暂扣覆盖");
    adapter.resume();
    assert.equal(state.isPaused("topic", 1), false);
    assert.equal(state.isPaused("topic", 0), true, "暂扣分区应等待重试时间");
    await new Promise((resolve) => setTimeout(resolve, 2100));
    assert.equal(state.isPaused("topic", 0), false);
  } finally {
    await adapter.disconnect();
    stub.mock.restore();
  }
});

test("容量暂停单个分区时，另一暂扣分区仍须暂停", async () => {
  const require = createRequire(import.meta.url);
  const SubscriptionState = require("kafkajs/src/consumer/subscriptionState");
  const state = new SubscriptionState();
  state.assign([{ topic: "topic", partitions: [0, 1] }]);
  let eachMessage: (payload: unknown) => Promise<void> = async () => {};
  let seen = 0;
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
    seek: () => {},
    pause: (partitions: unknown) => state.pause(partitions),
    resume: (partitions: unknown) => state.resume(partitions),
  };
  const stub = mock.method(Kafka.prototype, "consumer", () => fake as never);
  const adapter = new KafkaConsumerAdapter(
    { brokers: ["unused:9092"], tenantId: "test", workerCode: "test" },
    { info: () => {}, warn: () => {}, error: () => {} },
  );
  try {
    await adapter.start(async () => (++seen === 1 ? "backpressure" : "withhold"));
    await eachMessage({
      topic: "topic",
      partition: 0,
      message: { offset: "0", value: Buffer.from("record") },
    });
    await eachMessage({
      topic: "topic",
      partition: 1,
      message: { offset: "0", value: Buffer.from("record") },
    });
    assert.equal(state.isPaused("topic", 0), true);
    assert.equal(state.isPaused("topic", 1), true, "暂扣的另一分区也应暂停");
  } finally {
    await adapter.disconnect();
    stub.mock.restore();
  }
});

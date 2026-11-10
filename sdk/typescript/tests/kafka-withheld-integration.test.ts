/** 独立 Kafka 集群验证重平衡后逐条解除 withheld，保留其他未解决 offset。 */
import { test, mock } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

const broker = process.env.BATCH_SDK_KAFKA_BOOTSTRAP;
const logger = { info: () => {}, warn: () => {}, error: () => {} };

async function waitFor<T>(promise: Promise<T>, timeout: number, message: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      promise,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error(message)), timeout);
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

test("显式最新位置下首次未确认消息转交后仍可重读", { skip: !broker, timeout: 90000 }, async () => {
  const { Kafka, logLevel } = await import("kafkajs");
  const { KafkaConsumerAdapter } = await import("../src/kafka/kafkaConsumer.ts");
  const id = randomUUID();
  const topic = `sdk-withheld-latest-${id}`;
  const groupId = `sdk-withheld-latest-${id}`;
  const kafka = new Kafka({ brokers: [broker!], clientId: groupId, logLevel: logLevel.NOTHING });
  const admin = kafka.admin();
  const producer = kafka.producer();
  const next = kafka.consumer({ groupId });
  let heldResolve = () => {};
  let heldOffset = "";
  let heldSeen = false;
  const held = new Promise<void>((resolve) => {
    heldResolve = resolve;
  });
  const adapter = new KafkaConsumerAdapter(
    {
      brokers: [broker!],
      tenantId: "test",
      workerCode: id,
      groupId,
      topicRegex: new RegExp(`^${topic}$`),
      fromBeginning: false,
    },
    {
      ...logger,
      warn: (message: string) => {
        if (message === "offset withheld; partition paused for retry") {
          heldSeen = true;
          heldResolve();
        }
      },
    },
  );
  try {
    await admin.connect();
    await admin.createTopics({
      topics: [{ topic, numPartitions: 1, replicationFactor: 1 }],
      waitForLeaders: true,
    });
    await producer.connect();
    await adapter.start(async (record) => {
      heldOffset = record.meta!.offset!;
      return "withhold";
    });
    // latest 起点可能在 start 返回后才解析；持续发送直到观察到首条实际消费。
    for (let attempt = 0; attempt < 20 && !heldSeen; attempt++) {
      await producer.send({ topic, messages: [{ value: "unsupported" }] });
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    await waitFor(held, 30000, "首条消息未被暂扣");
    const before = (await admin.fetchOffsets({ groupId, topics: [topic] }))[0].partitions[0].offset;
    assert.equal(before, heldOffset, "提交位置须停在未确认消息之前");
    await adapter.disconnect();
    await next.connect();
    await next.subscribe({ topic, fromBeginning: false });
    let receivedResolve = () => {};
    const received = new Promise<void>((resolve) => {
      receivedResolve = resolve;
    });
    await next.run({
      autoCommit: false,
      eachMessage: async ({ message }) => {
        if (message.offset === heldOffset) receivedResolve();
      },
    });
    await waitFor(received, 15000, "转交后未重读未确认消息");
  } finally {
    await adapter.disconnect();
    await next.disconnect();
    await producer.disconnect();
    await admin.disconnect();
  }
});

test("首次读取位置保存失败后转交仍能重读", { skip: !broker, timeout: 90000 }, async () => {
  const { Kafka, logLevel } = await import("kafkajs");
  const { KafkaConsumerAdapter } = await import("../src/kafka/kafkaConsumer.ts");
  const id = randomUUID();
  const topic = `sdk-anchor-failure-${id}`;
  const groupId = `sdk-anchor-failure-${id}`;
  const kafka = new Kafka({ brokers: [broker!], clientId: groupId, logLevel: logLevel.NOTHING });
  const admin = kafka.admin();
  const producer = kafka.producer();
  let failedOffset = "";
  let commitStartedResolve = () => {};
  const commitStarted = new Promise<void>((resolve) => {
    commitStartedResolve = resolve;
  });
  let failCommitResolve = () => {};
  const failCommit = new Promise<void>((resolve) => {
    failCommitResolve = resolve;
  });
  const originalConsumer = Kafka.prototype.consumer;
  const stub = mock.method(Kafka.prototype, "consumer", function (...args) {
    const consumer = originalConsumer.apply(this, args);
    const commit = consumer.commitOffsets.bind(consumer);
    let first = true;
    consumer.commitOffsets = async (offsets) => {
      if (first) {
        first = false;
        failedOffset = offsets[0].offset;
        commitStartedResolve();
        await failCommit;
        throw new Error("注入首次位置保存失败");
      }
      await commit(offsets);
    };
    return consumer;
  });
  const config = {
    brokers: [broker!],
    tenantId: "test",
    workerCode: id,
    groupId,
    topicRegex: new RegExp(`^${topic}$`),
  };
  const first = new KafkaConsumerAdapter(config, logger);
  stub.mock.restore();
  const second = new KafkaConsumerAdapter(config, logger);
  const seen: string[] = [];
  let markerSeen = false;
  let entered = false;
  try {
    await admin.connect();
    await admin.createTopics({
      topics: [{ topic, numPartitions: 1, replicationFactor: 1 }],
      waitForLeaders: true,
    });
    await producer.connect();
    await first.start(async () => {
      throw new Error("首次保存失败时不应进入业务");
    });
    void commitStarted.then(() => {
      entered = true;
    });
    for (let attempt = 0; attempt < 20 && !entered; attempt++) {
      await producer.send({ topic, messages: [{ value: "first" }] });
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    await waitFor(commitStarted, 20000, "首条消息未进入位点保存");
    assert.equal(
      (await admin.fetchOffsets({ groupId, topics: [topic] }))[0].partitions[0].offset,
      "-1",
    );
    const stopping = first.wakeup();
    failCommitResolve();
    await stopping;
    await first.disconnect();
    await second.start(async (record) => {
      seen.push(record.meta!.offset!);
      if (record.value === "marker") markerSeen = true;
      return "commit";
    });
    for (let attempt = 0; attempt < 20 && !markerSeen; attempt++) {
      await producer.send({ topic, messages: [{ value: "marker" }] });
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    assert.ok(markerSeen, "新成员未消费后续消息");
    assert.ok(seen.includes(failedOffset), `未处理位点 ${failedOffset} 被跳过, 实际消费=${seen}`);
  } finally {
    failCommitResolve();
    await first.disconnect();
    await second.disconnect();
    await producer.disconnect();
    await admin.disconnect();
  }
});

test("未确认分区不阻塞其他分区提交", { skip: !broker, timeout: 90000 }, async () => {
  const { Kafka, logLevel } = await import("kafkajs");
  const { KafkaConsumerAdapter } = await import("../src/kafka/kafkaConsumer.ts");
  const id = randomUUID();
  const topic = `sdk-withheld-isolation-${id}`;
  const groupId = `sdk-withheld-isolation-${id}`;
  const kafka = new Kafka({ brokers: [broker!], clientId: groupId, logLevel: logLevel.NOTHING });
  const admin = kafka.admin();
  const producer = kafka.producer();
  const adapter = new KafkaConsumerAdapter(
    {
      brokers: [broker!],
      tenantId: "test",
      workerCode: id,
      groupId,
      topicRegex: new RegExp(`^${topic}$`),
      fromBeginning: true,
    },
    logger,
  );
  let withheldResolve = () => {};
  const withheld = new Promise<void>((resolve) => {
    withheldResolve = resolve;
  });
  let acceptedResolve = () => {};
  const accepted = new Promise<void>((resolve) => {
    acceptedResolve = resolve;
  });
  try {
    await admin.connect();
    await admin.createTopics({
      topics: [{ topic, numPartitions: 2, replicationFactor: 1 }],
      waitForLeaders: true,
    });
    await producer.connect();
    await producer.send({ topic, messages: [{ partition: 0, value: "unsupported" }] });
    await adapter.start(async (record) => {
      if (record.meta!.partition === 0) {
        withheldResolve();
        return "withhold";
      }
      acceptedResolve();
      return "commit";
    });
    await waitFor(withheld, 30000, "未收到受阻分区的消息");
    await producer.send({ topic, messages: [{ partition: 1, value: "valid" }] });
    await waitFor(accepted, 15000, "其他分区未继续消费");
    const deadline = Date.now() + 10000;
    let offsets: { partition: number; offset: string }[] = [];
    while (Date.now() < deadline) {
      offsets = (await admin.fetchOffsets({ groupId, topics: [topic] }))[0].partitions;
      if (offsets.find((item) => item.partition === 1)?.offset === "1") break;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    assert.equal(offsets.find((item) => item.partition === 0)?.offset, "0");
    assert.equal(offsets.find((item) => item.partition === 1)?.offset, "1");
  } finally {
    await adapter.disconnect();
    await producer.disconnect();
    await admin.disconnect();
  }
});

test("持续未确认时限制消费，恢复后重读并推进提交", { skip: !broker, timeout: 90000 }, async () => {
  const { Kafka, logLevel } = await import("kafkajs");
  const { KafkaConsumerAdapter } = await import("../src/kafka/kafkaConsumer.ts");
  const id = randomUUID();
  const topic = `sdk-withheld-bounded-${id}`;
  const groupId = `sdk-withheld-bounded-${id}`;
  const kafka = new Kafka({
    brokers: [broker!],
    clientId: `bounded-${id}`,
    logLevel: logLevel.NOTHING,
  });
  const admin = kafka.admin();
  const producer = kafka.producer();
  const adapter = new KafkaConsumerAdapter(
    {
      brokers: [broker!],
      tenantId: "test",
      workerCode: id,
      groupId,
      topicRegex: new RegExp(`^${topic}$`),
      fromBeginning: true,
    },
    logger,
  );
  const seen: string[] = [];
  let pausedResolve = () => {};
  const paused = new Promise<void>((resolve) => {
    pausedResolve = resolve;
  });
  let finishedResolve = () => {};
  const finished = new Promise<void>((resolve) => {
    finishedResolve = resolve;
  });
  try {
    await admin.connect();
    await admin.createTopics({
      topics: [{ topic, numPartitions: 1, replicationFactor: 1 }],
      waitForLeaders: true,
    });
    await producer.connect();
    await producer.send({ topic, messages: [{ value: "first" }, { value: "second" }] });
    await adapter.start(async (record) => {
      const offset = record.meta!.offset!;
      seen.push(offset);
      if (seen.length === 1) {
        adapter.pause();
        pausedResolve();
      }
      if (offset === "1") finishedResolve();
      return offset === "0" && seen.length <= 3 ? "withhold" : "commit";
    });
    await waitFor(paused, 20000, "等待首条消息超时");
    await new Promise((resolve) => setTimeout(resolve, 2500));
    assert.deepEqual(seen, ["0"], "重读定时器不得解除外部暂停");
    const blockedOffset = (await admin.fetchOffsets({ groupId, topics: [topic] }))[0].partitions[0]
      .offset;
    assert.equal(blockedOffset, "0", "提交位置应停在未确认消息之前");
    adapter.resume();
    await waitFor(finished, 30000, "等待未确认消息重读并恢复超时");
    assert.deepEqual(seen.slice(0, 5), ["0", "0", "0", "0", "1"]);
    const deadline = Date.now() + 10_000;
    let committed = "";
    while (Date.now() < deadline && committed !== "2") {
      committed = (await admin.fetchOffsets({ groupId, topics: [topic] }))[0].partitions[0].offset;
      if (committed !== "2") await new Promise((resolve) => setTimeout(resolve, 100));
    }
    assert.equal(committed, "2", "恢复后提交位置未推进");
  } finally {
    await adapter.disconnect();
    await producer.disconnect();
    await admin.disconnect();
  }
});

test(
  "另一成员处理 withheld 后原成员重新获得分区可继续提交",
  { skip: !broker, timeout: 90000 },
  async () => {
    const { Kafka, logLevel } = await import("kafkajs");
    const { KafkaConsumerAdapter } = await import("../src/kafka/kafkaConsumer.ts");
    const id = randomUUID();
    const topic = `sdk-withheld-transfer-${id}`;
    const groupId = `sdk-withheld-transfer-${id}`;
    const kafka = new Kafka({
      brokers: [broker!],
      clientId: `test-transfer-${id}`,
      logLevel: logLevel.NOTHING,
    });
    const admin = kafka.admin();
    const producer = kafka.producer();
    const other = kafka.consumer({ groupId });
    let transferred: number | undefined;
    let nextOffset: string | undefined;
    let retainedAfterTransfer: unknown;
    let nextResolve = () => {};
    const next = new Promise<void>((resolve) => {
      nextResolve = resolve;
    });
    const adapter = new KafkaConsumerAdapter(
      {
        brokers: [broker!],
        tenantId: "test",
        workerCode: id,
        groupId,
        topicRegex: new RegExp(`^${topic}$`),
        fromBeginning: true,
      },
      {
        ...logger,
        warn: (_message, context) => {
          if (context?.partition === transferred && context.offset === nextOffset) {
            retainedAfterTransfer = context.pendingOffsets;
            nextResolve();
          }
        },
      },
    );
    const firstPartitions = new Set<number>();
    let initialResolve = () => {};
    const initial = new Promise<void>((resolve) => {
      initialResolve = resolve;
    });
    let otherResolve: (partition: number) => void = () => {};
    const otherProcessed = new Promise<number>((resolve) => {
      otherResolve = resolve;
    });
    let retried = false;
    try {
      await admin.connect();
      await admin.createTopics({
        topics: [{ topic, numPartitions: 6, replicationFactor: 1 }],
        waitForLeaders: true,
      });
      await producer.connect();
      await producer.send({
        topic,
        messages: Array.from({ length: 6 }, (_, partition) => ({ partition, value: "first" })),
      });
      await adapter.start(async (record) => {
        const partition = record.meta!.partition!;
        if (record.meta!.offset === "0") {
          firstPartitions.add(partition);
          if (firstPartitions.size === 6) initialResolve();
          return "withhold";
        }
        if (
          partition === transferred &&
          nextOffset !== undefined &&
          BigInt(record.meta!.offset!) > BigInt(nextOffset)
        ) {
          return "withhold";
        }
        if (partition === transferred && record.meta!.offset === nextOffset && !retried) {
          retried = true;
          return "withhold";
        }
        return "commit";
      });
      await waitFor(initial, 20000, "首轮分区未完成");
      await other.connect();
      await other.subscribe({ topic, fromBeginning: true });
      await other.run({
        autoCommit: false,
        eachMessage: async ({ partition, message }) => {
          await other.commitOffsets([
            { topic, partition, offset: (BigInt(message.offset) + 1n).toString() },
          ]);
          otherResolve(partition);
        },
      });
      const peer = await waitFor(otherProcessed, 20000, "另一成员未获得分区");
      transferred = peer;
      await other.disconnect();
      // 生产者可能因网络超时重试；使用发送前的真实末尾位置定位新增消息。
      const topicOffsets = await admin.fetchTopicOffsets(topic);
      nextOffset = topicOffsets.find((item) => item.partition === peer)?.high;
      assert.ok(nextOffset, "未找到转交分区的末尾位置");
      await producer.send({ topic, messages: [{ partition: peer, value: "next" }] });
      await waitFor(next, 30000, "原成员未重新获得分区");
      assert.equal(retainedAfterTransfer, 1, "分区转交后只保留当前未确认位置");
      let offset: string | undefined;
      const deadline = Date.now() + 10_000;
      while (Date.now() < deadline) {
        const offsets = await admin.fetchOffsets({ groupId, topics: [topic] });
        offset = offsets[0].partitions.find((item) => item.partition === peer)?.offset;
        if (offset !== undefined && BigInt(offset) >= BigInt(nextOffset) + 1n) break;
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
      assert.equal(
        offset,
        (BigInt(nextOffset) + 1n).toString(),
        "已由其他成员提交的 withheld 不应阻挡当前分区继续提交",
      );
    } finally {
      await adapter.disconnect();
      await other.disconnect().catch(() => {});
      await producer.disconnect();
      await admin.disconnect();
    }
  },
);

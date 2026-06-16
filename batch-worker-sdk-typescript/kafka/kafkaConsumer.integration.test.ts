/**
 * Integration test for the real kafkajs adapter against a LIVE broker.
 *
 * GATED: only runs when KAFKA_BOOTSTRAP is set. Without it the whole suite is
 * skipped so the normal `node --test` unit run stays broker-free / green.
 *
 *   KAFKA_BOOTSTRAP=localhost:19092 \
 *     node --test --experimental-strip-types 'kafka/*.integration.test.ts'
 *
 * Coverage (byo-sdk-guide §1.2 / §1.9, wire-protocol §A):
 *   - admin creates `batch.task.dispatch.acme.<chan>` (unique suffix)
 *   - adapter (tenantId 'acme') subscribes via the wildcard regex + manual commit
 *   - a valid v1 TaskDispatchMessage is RECEIVED + ACCEPTED (committed)
 *   - a foreign-tenant message is DROPPED (not committed)
 *   - a schemaVersion "v3" message is REJECTED (not committed)
 *
 * Cleanup: unique topic/group per run; topics deleted in a finally block.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import {
  MessagePipeline,
  assignmentOf,
  type DispatchMessage,
  type PipelineOutcome,
} from "../src/consumer.ts";
import {
  KafkaConsumerAdapter,
  consumerGroupId,
  dispatchTopicRegex,
} from "./kafkaConsumer.ts";

const BOOTSTRAP = process.env.KAFKA_BOOTSTRAP;

const silentLogger = { info: () => {}, warn: () => {}, error: () => {} };

/** Wait until `predicate()` is true or time runs out. */
async function waitFor(
  predicate: () => boolean,
  timeoutMs = 20_000,
  stepMs = 100,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((r) => setTimeout(r, stepMs));
  }
  throw new Error("waitFor: timed out");
}

test(
  "kafka integration: accept v1, drop foreign tenant, reject v3 over live broker",
  { skip: BOOTSTRAP ? false : "KAFKA_BOOTSTRAP not set" },
  async () => {
    // lazy import so the unit run (no kafkajs needed at module load) is unaffected
    const { Kafka, logLevel } = await import("kafkajs");

    const TENANT = "acme";
    const suffix = `it${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`;
    const channel = `http-${suffix}`;
    const topic = `batch.task.dispatch.${TENANT}.${channel}`;
    const groupId = `${consumerGroupId(TENANT, "w-it")}-${suffix}`;

    const kafka = new Kafka({
      clientId: `it-producer-${suffix}`,
      brokers: [BOOTSTRAP!],
      logLevel: logLevel.NOTHING,
    });
    const admin = kafka.admin();
    const producer = kafka.producer();

    // collected pipeline outcomes, keyed by taskId for assertions
    const outcomes = new Map<string, PipelineOutcome>();
    const accepted: DispatchMessage[] = [];

    const adapter = new KafkaConsumerAdapter(
      {
        brokers: [BOOTSTRAP!],
        tenantId: TENANT,
        workerCode: "w-it",
        // PLAINTEXT locally; SASL/SCRAM-SHA-512 is config-supported (sasl:{...})
        groupId,
        fromBeginning: true,
      },
      silentLogger,
    );

    // sanity: the adapter targets exactly this tenant's wildcard + group
    assert.equal(adapter.groupId, groupId);
    assert.equal(adapter.topicRegex.source, dispatchTopicRegex(TENANT).source);
    assert.ok(adapter.topicRegex.test(topic), "regex should match the topic");

    const pipeline = new MessagePipeline({
      tenantId: TENANT,
      inFlight: () => 0,
      maxConcurrent: 8,
      assignment: assignmentOf(adapter),
      logger: silentLogger,
      onAccepted: async (msg) => {
        accepted.push(msg);
      },
    });

    // wrap pipeline.onMessage so we can record per-task outcomes for asserts,
    // while the adapter's runPipeline still owns the commit/pause policy.
    const recordingPipeline = {
      onMessage: async (r: { value: string }) => {
        const out = await pipeline.onMessage(r);
        try {
          const parsed = JSON.parse(r.value) as { taskId?: string };
          if (parsed.taskId) outcomes.set(parsed.taskId, out);
        } catch {
          /* parse-error path: no taskId to key on */
        }
        return out;
      },
    } as unknown as MessagePipeline;

    try {
      await admin.connect();
      await admin.createTopics({
        topics: [{ topic, numPartitions: 1, replicationFactor: 1 }],
        waitForLeaders: true,
      });
      await producer.connect();

      // start the adapter consuming with manual-commit policy (background)
      const running = adapter.runPipeline(recordingPipeline);

      // produce the three messages
      const validV1 = {
        schemaVersion: "v1",
        taskId: "task-valid-1",
        tenantId: TENANT,
        parameters: { foo: "bar" },
        runtimeAttributes: { traceId: "trace-it-1" },
      };
      const foreign = {
        schemaVersion: "v1",
        taskId: "task-foreign-1",
        tenantId: "other-tenant",
      };
      const v3 = {
        schemaVersion: "v3",
        taskId: "task-v3-1",
        tenantId: TENANT,
      };

      await producer.send({
        topic,
        messages: [
          { value: JSON.stringify(validV1) },
          { value: JSON.stringify(foreign) },
          { value: JSON.stringify(v3) },
        ],
      });

      // wait until all three have flowed through the pipeline
      await waitFor(() => outcomes.size >= 3);

      // 1. valid v1 → accepted + committed + handler saw it
      const acc = outcomes.get("task-valid-1");
      assert.ok(acc, "valid message should have an outcome");
      assert.equal(acc.kind, "accepted");
      assert.equal(acc.committed, true);
      assert.ok(
        accepted.some((m) => m.taskId === "task-valid-1"),
        "onAccepted should have received the valid v1 message",
      );

      // 2. foreign tenant → dropped, NOT committed (§1.9)
      const drop = outcomes.get("task-foreign-1");
      assert.ok(drop, "foreign message should have an outcome");
      assert.equal(drop.kind, "dropped-tenant");
      assert.equal(drop.committed, false);

      // 3. schemaVersion v3 → rejected, NOT committed (§A)
      const rej = outcomes.get("task-v3-1");
      assert.ok(rej, "v3 message should have an outcome");
      assert.equal(rej.kind, "rejected-schema");
      assert.equal(rej.committed, false);

      // foreign tenant must NEVER reach the handler
      assert.ok(
        !accepted.some((m) => m.tenantId !== TENANT),
        "no foreign-tenant message may reach the handler",
      );

      await Promise.race([
        running,
        adapter.wakeup().then(() => running),
      ]);
    } finally {
      await adapter.disconnect().catch(() => {});
      await producer.disconnect().catch(() => {});
      await admin.deleteTopics({ topics: [topic] }).catch(() => {});
      await admin.disconnect().catch(() => {});
    }
  },
);

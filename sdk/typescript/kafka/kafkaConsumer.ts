/**
 * Real kafkajs adapter for the BYO worker SDK (byo-sdk-guide §1.2 / §1.9,
 * wire-protocol §2.1 / §A).
 *
 * This is the ONLY module (besides its integration test) that imports kafkajs.
 * The core `src/*` stays zero-dependency: this adapter IMPLEMENTS the phase-2
 * `Consumer` interface and forwards each record through a `MessagePipeline`.
 *
 * Responsibilities (the platform contract the core decision rules describe):
 *   - wildcard subscribe `batch.task.dispatch.<tenant>.*` via a RegExp topic
 *   - consumer group `g-sdk-<tenantId>-<workerCode>`
 *   - SASL/SCRAM-SHA-512 + SSL config support (PLAINTEXT when no sasl given)
 *   - JSON UTF-8 deserialize, run through MessagePipeline (schema reject /
 *     tenant self-check §1.9 / backpressure)
 *   - manual offset commit (autoCommit:false): commit ONLY when the pipeline
 *     ACCEPTS, so rejected/dropped messages are redelivered by the platform
 *   - partition pause/resume driven by the backpressure decision
 *   - credentials come from config/env, NEVER from the message payload
 */

import {
  Kafka,
  logLevel as kafkaLogLevel,
  type Consumer as KafkaJsConsumer,
  type EachMessagePayload,
  type SASLOptions,
} from "kafkajs";

import {
  MessagePipeline,
  type Consumer,
  type ConsumerRecord,
  type Logger,
  consoleLogger,
} from "../src/client/consumer.ts";

/** SASL config (only SCRAM-SHA-512 is supported per byo-sdk-guide §1.2). */
export interface KafkaSaslConfig {
  mechanism: "scram-sha-512";
  username: string;
  password: string;
}

/**
 * Connection + identity config. Credentials live here (sourced from env/secret
 * by the caller) — never read from a dispatch payload.
 */
export interface KafkaConsumerConfig {
  /** bootstrap brokers, e.g. ["localhost:19092"]. */
  brokers: string[];
  /** tenant this worker serves; drives the topic regex + group + self-check. */
  tenantId: string;
  /** worker code; part of the consumer group id. */
  workerCode: string;
  /** TLS on the wire (SASL_SSL). false/omitted → plaintext transport. */
  ssl?: boolean;
  /** SASL/SCRAM-SHA-512. Omit for PLAINTEXT (local broker). */
  sasl?: KafkaSaslConfig;
  /** override the generated clientId. */
  clientId?: string;
  /** override the generated consumer group id (mainly for test isolation). */
  groupId?: string;
  /** override the wildcard topic regex (mainly for test isolation). */
  topicRegex?: RegExp;
  /** start from earliest on a brand-new group (default true; handy for tests). */
  fromBeginning?: boolean;
}

/**
 * Build the node-direct subscription regex for
 * `batch.task.dispatch.<workerType>.node.<workerCode>` — the topic the platform
 * dispatches THIS worker's tasks to (base-first, aligned with built-in workers'
 * AbstractTaskConsumer.topicPattern()). The old tenant-first
 * `batch.task.dispatch.<tenant>.*` is never published to; cross-tenant safety is
 * enforced by the per-message tenant self-check (§1.9), not the topic name.
 */
export function dispatchTopicRegex(workerCode: string): RegExp {
  const safe = workerCode.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`^batch\\.task\\.dispatch\\..+\\.node\\.${safe}$`);
}

/** Consumer group id per byo-sdk-guide §1.2: g-sdk-<tenantId>-<workerCode>. */
export function consumerGroupId(tenantId: string, workerCode: string): string {
  return `g-sdk-${tenantId}-${workerCode}`;
}

/**
 * kafkajs adapter implementing the phase-2 `Consumer` seam.
 *
 * Usage: wire a `MessagePipeline` (from core), then either drive it yourself by
 * passing the pipeline's `onMessage` into `start`, or use `runPipeline` which
 * adds the manual-commit + pause/resume policy on top of the pipeline outcome.
 */
export class KafkaConsumerAdapter implements Consumer {
  readonly #kafka: Kafka;
  readonly #consumer: KafkaJsConsumer;
  readonly #config: KafkaConsumerConfig;
  readonly #logger: Logger;
  readonly #topicRegex: RegExp;
  readonly #groupId: string;
  #running = false;
  #paused = false;
  /** topics actually matched by the wildcard regex (learned at GROUP_JOIN). */
  readonly #assignedTopics = new Set<string>();

  constructor(config: KafkaConsumerConfig, logger: Logger = consoleLogger) {
    this.#config = config;
    this.#logger = logger;
    this.#topicRegex = config.topicRegex ?? dispatchTopicRegex(config.workerCode);
    this.#groupId =
      config.groupId ?? consumerGroupId(config.tenantId, config.workerCode);

    const sasl: SASLOptions | undefined = config.sasl
      ? {
          // kafkajs mechanism name for SCRAM-SHA-512
          mechanism: "scram-sha-512",
          username: config.sasl.username,
          password: config.sasl.password,
        }
      : undefined;

    this.#kafka = new Kafka({
      clientId:
        config.clientId ?? `sdk-${config.tenantId}-${config.workerCode}`,
      brokers: config.brokers,
      // ssl + sasl → SASL_SSL; sasl only → SASL_PLAINTEXT; neither → PLAINTEXT
      ssl: config.ssl ?? false,
      sasl,
      logLevel: kafkaLogLevel.NOTHING,
    });

    this.#consumer = this.#kafka.consumer({ groupId: this.#groupId });

    // learn the concrete topics the wildcard regex matched, so FSM-level
    // pause()/resume() (PAUSED / DRAINING, §1.5) can act on whole topics.
    this.#consumer.on(this.#consumer.events.GROUP_JOIN, (e) => {
      for (const topic of Object.keys(e.payload.memberAssignment ?? {})) {
        this.#assignedTopics.add(topic);
      }
    });
  }

  /** The resolved consumer group id (for assertions / logging). */
  get groupId(): string {
    return this.#groupId;
  }

  /** The resolved wildcard topic regex. */
  get topicRegex(): RegExp {
    return this.#topicRegex;
  }

  /**
   * Connect, subscribe to the wildcard topic, and run with manual commit.
   *
   * `onMessage` is the seam used by the core `MessagePipeline.onMessage`. The
   * adapter wraps each record so it can apply the commit / pause policy AFTER
   * the handler returns — but the simplest integration is `runPipeline`, which
   * supplies a handler that already returns the pipeline outcome.
   */
  async start(
    onMessage: (r: ConsumerRecord) => Promise<void>,
  ): Promise<void> {
    await this.#consumer.connect();
    await this.#consumer.subscribe({
      topic: this.#topicRegex,
      fromBeginning: this.#config.fromBeginning ?? true,
    });
    this.#running = true;
    this.#logger.info("kafka consumer subscribed", {
      group: this.#groupId,
      topicRegex: this.#topicRegex.source,
    });

    await this.#consumer.run({
      autoCommit: false,
      eachMessage: async (payload: EachMessagePayload) => {
        this.#assignedTopics.add(payload.topic);
        const value = payload.message.value?.toString("utf8") ?? "";
        const record: ConsumerRecord = {
          value,
          meta: {
            topic: payload.topic,
            partition: payload.partition,
            offset: payload.message.offset,
          },
        };
        await onMessage(record);
      },
    });
  }

  /**
   * Run the consumer with a full pipeline + offset/pause policy.
   *
   * Per message:
   *   1. feed the raw record through `pipeline.onMessage`
   *   2. commit the offset ONLY when the outcome is "accepted" (would proceed
   *      to claim). Rejected schema / foreign tenant / parse error / backpressure
   *      are NOT committed, so the platform can redeliver.
   *   3. on a "backpressure" outcome, pause the partition; when capacity frees
   *      (decided by the pipeline's assignment + a resume hook) resume it.
   */
  async runPipeline(pipeline: MessagePipeline): Promise<void> {
    await this.#consumer.connect();
    await this.#consumer.subscribe({
      topic: this.#topicRegex,
      fromBeginning: this.#config.fromBeginning ?? true,
    });
    this.#running = true;
    this.#logger.info("kafka consumer subscribed", {
      group: this.#groupId,
      topicRegex: this.#topicRegex.source,
    });

    await this.#consumer.run({
      autoCommit: false,
      eachMessage: async (payload: EachMessagePayload) => {
        this.#assignedTopics.add(payload.topic);
        const value = payload.message.value?.toString("utf8") ?? "";
        const record: ConsumerRecord = {
          value,
          meta: {
            topic: payload.topic,
            partition: payload.partition,
            offset: payload.message.offset,
          },
        };

        const outcome = await pipeline.onMessage(record);

        if (outcome.kind === "backpressure") {
          // pause this specific partition; resume after the backpressure window
          this.#pausePartition(payload.topic, payload.partition);
          return; // do NOT commit; redeliver after resume
        }

        if (outcome.committed) {
          // manual commit: advance past this offset (offset + 1 per kafkajs)
          await this.#consumer.commitOffsets([
            {
              topic: payload.topic,
              partition: payload.partition,
              offset: (BigInt(payload.message.offset) + 1n).toString(),
            },
          ]);
        }
        // rejected / dropped / parse-error: intentionally NOT committed (§A/§1.9)
      },
    });
  }

  #pausePartition(topic: string, partition: number): void {
    this.#consumer.pause([{ topic, partitions: [partition] }]);
    this.#paused = true;
    this.#logger.warn("paused partition (backpressure)", { topic, partition });
  }

  /** Resume a specific partition (call when a concurrency slot frees up). */
  resumePartition(topic: string, partition: number): void {
    this.#consumer.resume([{ topic, partitions: [partition] }]);
    this.#paused = false;
    this.#logger.info("resumed partition", { topic, partition });
  }

  /** Interrupt the poll loop (byo-sdk-guide §1.6 graceful stop). */
  async wakeup(): Promise<void> {
    this.#running = false;
    await this.#consumer.stop();
  }

  /** Disconnect entirely (cleanup). */
  async disconnect(): Promise<void> {
    this.#running = false;
    await this.#consumer.disconnect();
  }

  /** Pause ALL assigned partitions (FSM PAUSED/DRAINING, §1.5). */
  pause(): void {
    const topics = [...this.#assignedTopics];
    if (topics.length > 0) {
      // topic-only entry pauses every partition of that topic
      this.#consumer.pause(topics.map((topic) => ({ topic })));
    }
    this.#paused = true;
  }

  /** Resume ALL assigned partitions. */
  resume(): void {
    const paused = this.#consumer.paused();
    if (paused.length > 0) {
      this.#consumer.resume(paused);
    }
    this.#paused = false;
  }

  isPaused(): boolean {
    return this.#paused;
  }

  get isRunning(): boolean {
    return this.#running;
  }
}

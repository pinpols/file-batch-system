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
  dispositionOf,
  type Consumer,
  type ConsumerRecord,
  type Logger,
  type MessageDisposition,
  consoleLogger,
} from "../client/consumer.ts";
import { offsetAction, resolveFromBeginning, commitBlockedByWithheld } from "./offsetPolicy.ts";

// 未确认消息短暂等待后按原位置重读，避免持续失败时忙等。
const WITHHOLD_RETRY_DELAY_MS = 2_000;
// KafkaJS 的 eachMessage 等待期间不会自动发送消费组心跳。
const KAFKA_HEARTBEAT_INTERVAL_MS = 3_000;

async function awaitWithKafkaHeartbeat<T>(
  work: Promise<T>,
  heartbeat: () => Promise<void>,
): Promise<T> {
  let active = true;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const heartbeatFailure = new Promise<never>((_resolve, reject) => {
    const sendHeartbeat = async () => {
      try {
        await heartbeat();
        if (active) timer = setTimeout(sendHeartbeat, KAFKA_HEARTBEAT_INTERVAL_MS);
      } catch (error) {
        reject(error);
      }
    };
    timer = setTimeout(sendHeartbeat, KAFKA_HEARTBEAT_INTERVAL_MS);
  });
  try {
    return await Promise.race([work, heartbeatFailure]);
  } finally {
    active = false;
    clearTimeout(timer);
  }
}

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
  #globallyPaused = false;
  /** topics actually matched by the wildcard regex (learned at GROUP_JOIN). */
  readonly #assignedTopics = new Set<string>();
  /**
   * 每个分区保留尚未成功重读的 offset；提交不能跨过其中任何一条。
   */
  readonly #withheld = new Map<string, Set<string>>();
  readonly #withheldRetries = new Map<string, ReturnType<typeof setTimeout>>();
  readonly #anchoredPartitions = new Set<string>();
  #assignmentEpoch = 0;

  constructor(config: KafkaConsumerConfig, logger: Logger = consoleLogger) {
    this.#config = config;
    this.#logger = logger;
    this.#topicRegex = config.topicRegex ?? dispatchTopicRegex(config.workerCode);
    this.#groupId = config.groupId ?? consumerGroupId(config.tenantId, config.workerCode);

    const sasl: SASLOptions | undefined = config.sasl
      ? {
          // kafkajs mechanism name for SCRAM-SHA-512
          mechanism: "scram-sha-512",
          username: config.sasl.username,
          password: config.sasl.password,
        }
      : undefined;

    this.#kafka = new Kafka({
      clientId: config.clientId ?? `sdk-${config.tenantId}-${config.workerCode}`,
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
      // 新分配从 broker 已提交位置开始，旧分配的提交屏障不再属于当前读取轮次。
      this.#assignmentEpoch++;
      this.#withheld.clear();
      this.#anchoredPartitions.clear();
      this.#assignedTopics.clear();
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
   * Connect, subscribe to the wildcard topic, and run with manual commit + the
   * full offset/seek/pause policy.
   *
   * `onMessage` is the production seam (the lifecycle passes
   * `MessagePipeline.onMessage` through it). It returns a {@link MessageDisposition}
   * the adapter acts on AFTER the record is handled:
   *   - "commit"       → advance past this offset (accepted task / poison skip)
   *   - "withhold"     → 保留提交上限，暂停该分区并在等待后重读
   *   - "backpressure" → seek back + pause; resumed when a slot frees (§1.5/§2)
   *
   * This replaces the old `start()` that only forwarded records and never
   * committed (offset never advanced → every restart replayed the partition).
   */
  async start(onMessage: (r: ConsumerRecord) => Promise<MessageDisposition>): Promise<void> {
    await this.#runLoop(onMessage);
  }

  /**
   * Run the consumer with a full `MessagePipeline`. Convenience wrapper over
   * {@link start}: feeds each record through the pipeline and maps the outcome to
   * the offset disposition via the shared {@link dispositionOf}.
   */
  async runPipeline(pipeline: MessagePipeline): Promise<void> {
    await this.#runLoop(async (record) => {
      const outcome = await pipeline.onMessage(record);
      return dispositionOf(outcome);
    });
  }

  /** Shared connect → subscribe → run-with-policy loop for start()/runPipeline(). */
  async #runLoop(onEach: (r: ConsumerRecord) => Promise<MessageDisposition>): Promise<void> {
    await this.#consumer.connect();
    await this.#consumer.subscribe({
      topic: this.#topicRegex,
      // 新消费组默认从最早保留位置读取；显式配置可以选择最新位置。
      fromBeginning: resolveFromBeginning(this.#config.fromBeginning),
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

        const key = `${payload.topic}/${payload.partition}`;
        const epoch = this.#assignmentEpoch;
        if (!this.#anchoredPartitions.has(key)) {
          // 游标指向当前消息，不代表处理成功；持久化失败时不进入业务。
          await this.#consumer.commitOffsets([
            { topic: payload.topic, partition: payload.partition, offset: payload.message.offset },
          ]);
          if (epoch !== this.#assignmentEpoch) return;
          this.#anchoredPartitions.add(key);
        }
        const disposition = await awaitWithKafkaHeartbeat(onEach(record), payload.heartbeat);
        if (epoch !== this.#assignmentEpoch) return;
        const action = offsetAction(
          disposition,
          payload.topic,
          payload.partition,
          payload.message.offset,
        );

        if (action.type === "commit") {
          // never commit past a record withheld earlier on this partition — that
          // would silently skip it (§A/§1.9). Below the ceiling → advance normally.
          const pending = this.#withheld.get(key);
          pending?.delete(payload.message.offset);
          if (pending?.size === 0) this.#withheld.delete(key);
          let withheldCeiling =
            pending &&
            [...pending].reduce<string | undefined>(
              (lowest, offset) =>
                lowest === undefined || BigInt(offset) < BigInt(lowest) ? offset : lowest,
              undefined,
            );
          if (pending && commitBlockedByWithheld(withheldCeiling, payload.message.offset)) {
            // 其他组成员可能已处理并提交该分区；只清理 broker 确认已越过的记录。
            const admin = this.#kafka.admin();
            try {
              await admin.connect();
              const offsets = await admin.fetchOffsets({
                groupId: this.#groupId,
                topics: [payload.topic],
              });
              const committed = offsets[0]?.partitions.find(
                (p) => p.partition === payload.partition,
              )?.offset;
              if (committed !== undefined && BigInt(committed) >= 0n) {
                for (const offset of pending) {
                  if (BigInt(offset) < BigInt(committed)) pending.delete(offset);
                }
                if (pending.size === 0 && this.#withheld.get(key) === pending)
                  this.#withheld.delete(key);
              }
            } catch (error) {
              this.#logger.warn("failed to reconcile withheld offsets", { error: String(error) });
            } finally {
              await admin.disconnect();
            }
            // 查询期间重平衡可能更新登记，应再次读取当前分区的全部未解决记录。
            const current = this.#withheld.get(key);
            withheldCeiling =
              current &&
              [...current].reduce<string | undefined>(
                (lowest, offset) =>
                  lowest === undefined || BigInt(offset) < BigInt(lowest) ? offset : lowest,
                undefined,
              );
          }
          if (commitBlockedByWithheld(withheldCeiling, payload.message.offset)) {
            this.#logger.warn("commit withheld: offset at/past partition ceiling", {
              topic: action.topic,
              partition: action.partition,
              offset: payload.message.offset,
              ceiling: withheldCeiling,
            });
            return;
          }
          // manual commit: advance past this offset (offset + 1 per kafkajs)
          await this.#consumer.commitOffsets([
            { topic: action.topic, partition: action.partition, offset: action.offset },
          ]);
          return;
        }

        if (action.type === "withhold") {
          // 停止后由已保存的读取位置恢复，无需创建新的分区重试定时器。
          if (!this.#running) return;
          // 暂停当前分区并回到未确认位置，其他分区继续消费。
          const pending = this.#withheld.get(key) ?? new Set<string>();
          pending.add(action.offset);
          this.#withheld.set(key, pending);
          if (!this.#globallyPaused) {
            this.#consumer.pause([{ topic: action.topic, partitions: [action.partition] }]);
          }
          this.#consumer.seek({
            topic: action.topic,
            partition: action.partition,
            offset: action.offset,
          });
          const prior = this.#withheldRetries.get(key);
          if (prior) clearTimeout(prior);
          const timer = setTimeout(() => {
            this.#withheldRetries.delete(key);
            if (this.#running && !this.#paused) {
              this.#consumer.resume([{ topic: action.topic, partitions: [action.partition] }]);
            }
          }, WITHHOLD_RETRY_DELAY_MS);
          this.#withheldRetries.set(key, timer);
          this.#logger.warn("offset withheld; partition paused for retry", {
            topic: action.topic,
            partition: action.partition,
            offset: action.offset,
            pendingOffsets: pending.size,
          });
          return;
        }

        // backpressure: seek BACK to this offset then pause the partition, so (a)
        // this message is redelivered on resume and (b) NO later commit crosses it.
        // 容量背压由可用执行槽位触发恢复；未确认消息使用独立的重读等待。
        this.#consumer.seek({
          topic: action.topic,
          partition: action.partition,
          offset: action.offset,
        });
        if (!this.#globallyPaused) {
          this.#consumer.pause([{ topic: action.topic, partitions: [action.partition] }]);
        }
        this.#paused = true;
        this.#logger.warn("seek-back + pause partition (backpressure)", {
          topic: action.topic,
          partition: action.partition,
          offset: action.offset,
          reason: action.reason,
        });
      },
    });
  }

  /** Resume a specific partition (call when a concurrency slot frees up). */
  resumePartition(topic: string, partition: number): void {
    if (this.#globallyPaused || this.#withheldRetries.has(`${topic}/${partition}`)) return;
    this.#consumer.resume([{ topic, partitions: [partition] }]);
    this.#paused = false;
    this.#logger.info("resumed partition", { topic, partition });
  }

  /** Interrupt the poll loop (byo-sdk-guide §1.6 graceful stop). */
  async wakeup(): Promise<void> {
    this.#running = false;
    for (const timer of this.#withheldRetries.values()) clearTimeout(timer);
    this.#withheldRetries.clear();
    await this.#consumer.stop();
  }

  /** Disconnect entirely (cleanup). */
  async disconnect(): Promise<void> {
    this.#running = false;
    for (const timer of this.#withheldRetries.values()) clearTimeout(timer);
    this.#withheldRetries.clear();
    await this.#consumer.disconnect();
  }

  /** Pause ALL assigned partitions (FSM PAUSED/DRAINING, §1.5). */
  pause(): void {
    this.#globallyPaused = true;
    const topics = [...this.#assignedTopics];
    if (topics.length > 0) {
      // topic-only entry pauses every partition of that topic
      this.#consumer.pause(topics.map((topic) => ({ topic })));
    }
    this.#paused = true;
  }

  /** Resume ALL assigned partitions. */
  resume(): void {
    const paused = this.#consumer
      .paused()
      .map(({ topic, partitions }) => ({
        topic,
        partitions: partitions.filter(
          (partition) => !this.#withheldRetries.has(`${topic}/${partition}`),
        ),
      }))
      .filter(({ partitions }) => partitions.length > 0);
    if (paused.length > 0) {
      this.#consumer.resume(paused);
    }
    this.#globallyPaused = false;
    this.#paused = false;
  }

  isPaused(): boolean {
    return this.#paused;
  }

  get isRunning(): boolean {
    return this.#running;
  }
}

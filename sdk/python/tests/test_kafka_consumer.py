"""KafkaTaskConsumer 单元测试(P2)。

底层 ``AIOKafkaConsumer`` 用 ``MagicMock`` 替代,这样不需要真的 broker。
fixture 级 pause/resume 在 contract runner 中做端到端;这里只覆盖单元
分支(缺配置、subscribe 路径、rebalance listener 重置缓存)。
"""

from __future__ import annotations

import asyncio
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

from batch_worker_sdk import BatchPlatformClientConfig, TaskDispatcher, WorkerRuntimeState
from batch_worker_sdk.dispatcher.dispatcher import DispatchDisposition
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.internal._kafka import (
    KafkaTaskConsumer,
    _jaas_field,
    _PauseAwareRebalanceListener,
)


def _cfg(**overrides: object) -> BatchPlatformClientConfig:
    base = {
        "base_url": "http://orch:8081",
        "tenant_id": "acme",
        "worker_code": "w-1",
        "max_concurrent_tasks": 4,
        "kafka_bootstrap": "kafka:9092",
        "kafka_group_id": "g-1",
        "kafka_topic_pattern": "batch.task.dispatch.acme.*",
    }
    base.update(overrides)
    return BatchPlatformClientConfig(**base)  # type: ignore[arg-type]


async def _make_consumer() -> tuple[KafkaTaskConsumer, TaskDispatcher, MagicMock]:
    cfg = _cfg()
    http = PlatformHttpClient(cfg)
    dispatcher = TaskDispatcher(cfg, http)
    mock_consumer = MagicMock()
    mock_consumer.assignment.return_value = {"part-0", "part-1"}
    consumer = KafkaTaskConsumer(cfg, dispatcher, consumer=mock_consumer)
    return consumer, dispatcher, mock_consumer


def test_jaas_field_parses_double_and_single_quotes() -> None:
    jaas = 'org.apache.kafka.common.security.scram.ScramLoginModule required username="u1" password="p1";'
    assert _jaas_field(jaas, "username") == "u1"
    assert _jaas_field(jaas, "password") == "p1"
    single = "ScramLoginModule required username='u2' password='p2';"
    assert _jaas_field(single, "username") == "u2"
    assert _jaas_field(single, "password") == "p2"
    assert _jaas_field("ScramLoginModule required;", "username") is None


async def test_resolve_sasl_credentials_prefers_explicit_then_jaas() -> None:
    http = PlatformHttpClient(_cfg())
    try:
        # 显式字段优先
        explicit = KafkaTaskConsumer(
            _cfg(kafka_sasl_username="exp-u", kafka_sasl_password="exp-p"),
            TaskDispatcher(_cfg(), http),
        )
        assert explicit._resolve_sasl_credentials() == ("exp-u", "exp-p")

        # 退化到解析 Java 风格 JAAS(对齐 Java SDK 同一份配置)
        jaas_cfg = _cfg(
            kafka_sasl_mechanism="SCRAM-SHA-512",
            kafka_sasl_jaas_config=(
                "org.apache.kafka.common.security.scram.ScramLoginModule "
                'required username="jaas-u" password="jaas-p";'
            ),
        )
        jaas = KafkaTaskConsumer(jaas_cfg, TaskDispatcher(_cfg(), http))
        assert jaas._resolve_sasl_credentials() == ("jaas-u", "jaas-p")

        # 三者皆空 → PLAINTEXT,无凭据
        plain = KafkaTaskConsumer(_cfg(), TaskDispatcher(_cfg(), http))
        assert plain._resolve_sasl_credentials() == (None, None)
    finally:
        await http.close()


async def test_build_consumer_requires_kafka_fields() -> None:
    """缺 kafka_* 配置 → _build_consumer 抛 ValueError。"""
    cfg = BatchPlatformClientConfig(
        base_url="http://orch:8081", tenant_id="acme", worker_code="w-1"
    )
    http = PlatformHttpClient(cfg)
    try:
        dispatcher = TaskDispatcher(cfg, http)
        consumer = KafkaTaskConsumer(cfg, dispatcher)
        with pytest.raises(ValueError, match="kafka_bootstrap"):
            consumer._build_consumer()
    finally:
        await http.close()


async def test_apply_backpressure_pauses_at_saturation() -> None:
    """in_flight >= max → 只 pause(*assignment) 一次,带缓存。"""
    consumer, dispatcher, mock = await _make_consumer()
    try:

        async def _idle() -> None:
            await asyncio.sleep(60)

        for tid in range(4):
            dispatcher._in_flight[tid] = asyncio.create_task(_idle())
        try:
            consumer.apply_backpressure()
            assert mock.pause.call_count == 1
            # 第二次 tick 不能重发 pause(缓存)。
            consumer.apply_backpressure()
            assert mock.pause.call_count == 1
            assert consumer.paused is True
        finally:
            for t in list(dispatcher._in_flight.values()):
                t.cancel()
    finally:
        await consumer._dispatcher._http.close()


async def test_apply_backpressure_resumes_when_capacity_returns() -> None:
    """容量降到 max//2 以下 → resume(*assignment) 一次。"""
    consumer, dispatcher, mock = await _make_consumer()
    try:

        async def _idle() -> None:
            await asyncio.sleep(60)

        for tid in range(4):
            dispatcher._in_flight[tid] = asyncio.create_task(_idle())
        try:
            consumer.apply_backpressure()  # pause (in_flight=4 >= max=4)
            # 排干到 in_flight < max//2(=2)才会 resume(hysteresis)。
            while dispatcher.in_flight_count() >= 2:
                t = next(iter(dispatcher._in_flight))
                dispatcher._in_flight[t].cancel()
                dispatcher._in_flight.pop(t)
            consumer.apply_backpressure()  # resume
            assert mock.resume.call_count == 1
            assert consumer.paused is False
        finally:
            for t in list(dispatcher._in_flight.values()):
                t.cancel()
    finally:
        await consumer._dispatcher._http.close()


async def test_apply_backpressure_hysteresis_holds_pause_between_max_and_half() -> None:
    """#8 回归:容量在 (max//2, max) 区间内抖动时不得 resume(防边界颠簸)。

    max=4 → pause 在 in_flight>=4,resume 仅在 in_flight<2。in_flight=3/2 时
    必须保持 paused、不发 resume RPC。
    """
    consumer, dispatcher, mock = await _make_consumer()
    try:

        async def _idle() -> None:
            await asyncio.sleep(60)

        for tid in range(4):
            dispatcher._in_flight[tid] = asyncio.create_task(_idle())
        try:
            consumer.apply_backpressure()  # pause at 4
            assert consumer.paused is True
            # 掉到 3:仍 >= max//2 → 不 resume。
            for target in (3, 2):
                t = next(iter(dispatcher._in_flight))
                dispatcher._in_flight[t].cancel()
                dispatcher._in_flight.pop(t)
                assert dispatcher.in_flight_count() == target
                consumer.apply_backpressure()
                assert mock.resume.call_count == 0, (
                    f"hysteresis violated: resumed at in_flight={target} (>= max//2)"
                )
                assert consumer.paused is True
            # 掉到 1:< max//2 → resume。
            t = next(iter(dispatcher._in_flight))
            dispatcher._in_flight[t].cancel()
            dispatcher._in_flight.pop(t)
            consumer.apply_backpressure()
            assert mock.resume.call_count == 1
            assert consumer.paused is False
        finally:
            for t in list(dispatcher._in_flight.values()):
                t.cancel()
    finally:
        await consumer._dispatcher._http.close()


async def test_apply_backpressure_platform_resume_not_blocked_by_hysteresis() -> None:
    """#8:平台 PAUSED→NORMAL 恢复,容量已满足 hysteresis 时应立即 resume。

    平台态本身不构成抖动源,所以平台恢复后只要 in_flight<max//2 即 resume,
    不被容量 hysteresis 拖延。
    """
    consumer, dispatcher, mock = await _make_consumer()
    try:
        dispatcher.apply_platform_directive({"runtimeState": "PAUSED"})
        consumer.apply_backpressure()  # platform pause(容量空)
        assert mock.pause.call_count == 1
        assert consumer.paused is True
        # 平台恢复 NORMAL,容量本就空(in_flight=0 < max//2)→ 立即 resume。
        dispatcher.apply_platform_directive({"runtimeState": "NORMAL"})
        consumer.apply_backpressure()
        assert mock.resume.call_count == 1
        assert consumer.paused is False
    finally:
        await consumer._dispatcher._http.close()


async def test_apply_backpressure_pauses_on_platform_paused() -> None:
    """即使还有容量,PAUSED/DRAINING directive 也应 pause。"""
    consumer, dispatcher, mock = await _make_consumer()
    try:
        dispatcher.apply_platform_directive({"runtimeState": "PAUSED"})
        assert dispatcher.runtime_state == WorkerRuntimeState.PAUSED
        consumer.apply_backpressure()
        assert mock.pause.call_count == 1
    finally:
        await consumer._dispatcher._http.close()


async def test_apply_backpressure_skips_when_no_assignment() -> None:
    """空 assignment → 不发 pause/resume RPC(避免无效调用)。"""
    consumer, _dispatcher, mock = await _make_consumer()
    try:
        mock.assignment.return_value = set()
        consumer.apply_backpressure()
        assert mock.pause.call_count == 0
        assert mock.resume.call_count == 0
    finally:
        await consumer._dispatcher._http.close()


async def test_rebalance_listener_resets_capacity_ledger() -> None:
    """on_partitions_assigned → 容量账本翻 False。

    withhold 不再 pause 分区(改走 commit 天花板),故 rebalance 只需重置容量
    维度;天花板从不 commit,revoke 后由重投从原位恢复,无需在 rebalance 清。
    """
    consumer, _dispatcher, _mock = await _make_consumer()
    try:
        consumer._capacity_paused = True
        listener = _PauseAwareRebalanceListener(consumer)
        await listener.on_partitions_assigned(set())
        assert consumer._capacity_paused is False
    finally:
        await consumer._dispatcher._http.close()


async def test_capacity_resume_resumes_full_assignment_with_ceilings_set() -> None:
    """容量恢复时 resume 整个 assignment —— withhold 天花板不影响 resume 集合。

    旧实现把 withhold 分区单独 pause 并从 resume 集合里排除;新实现 withhold
    根本不 pause(改走 commit 天花板 + 继续消费),因此即便某分区设了天花板,
    容量恢复也应 resume **全部** assignment(唯一的 pause 来自 backpressure,
    且必须完整配对 resume,否则会漏 resume 冻住分区)。
    """
    consumer, dispatcher, mock = await _make_consumer()
    mock.assignment.return_value = {"part-0", "part-1"}
    try:

        async def _idle() -> None:
            await asyncio.sleep(60)

        for tid in range(4):
            dispatcher._in_flight[tid] = asyncio.create_task(_idle())
        try:
            consumer.apply_backpressure()  # 容量 pause
            assert consumer.paused is True
            # 某分区设了 withhold 天花板(模拟 poll loop 记账),不应影响 resume。
            consumer._withheld_ceilings["part-0"] = 42  # type: ignore[index]
            # 容量恢复(排干到 < max//2)。
            while dispatcher.in_flight_count() >= 2:
                t = next(iter(dispatcher._in_flight))
                dispatcher._in_flight[t].cancel()
                dispatcher._in_flight.pop(t)
            consumer.apply_backpressure()
            assert mock.resume.call_count == 1
            resumed = set(mock.resume.call_args.args)
            assert resumed == {"part-0", "part-1"}, (
                f"capacity resume must cover full assignment, got {resumed}"
            )
            # 天花板不被容量 resume 影响。
            assert consumer.withheld_ceilings == {"part-0": 42}
        finally:
            for t in list(dispatcher._in_flight.values()):
                t.cancel()
    finally:
        await consumer._dispatcher._http.close()


async def test_handle_record_dispatches_to_on_message() -> None:
    """合法 JSON → dispatcher.on_message 收到解析后的 dict。"""
    consumer, dispatcher, _mock = await _make_consumer()
    try:
        received: list[dict[str, object]] = []

        async def _capture(msg: dict[str, object]) -> None:
            received.append(msg)

        dispatcher.on_message = _capture  # type: ignore[method-assign]

        rec = MagicMock()
        rec.value = b'{"schemaVersion": "v2", "tenantId": "acme", "taskId": 1}'
        rec.offset = 7
        tp = MagicMock()
        tp.topic = "batch.task.dispatch.acme.echo"

        await consumer._handle_record(tp, rec)
        assert received == [{"schemaVersion": "v2", "tenantId": "acme", "taskId": 1}]
    finally:
        await consumer._dispatcher._http.close()


async def test_handle_record_skips_invalid_json(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """坏 JSON → ERROR 日志,不分发。"""
    consumer, dispatcher, _mock = await _make_consumer()
    try:
        called = []

        async def _capture(msg: dict[str, object]) -> None:
            called.append(msg)

        dispatcher.on_message = _capture  # type: ignore[method-assign]

        rec = MagicMock()
        rec.value = b"not json"
        rec.offset = 0
        tp = MagicMock()
        tp.topic = "batch.task.dispatch.acme.echo"

        caplog.set_level("ERROR", logger="batch_worker_sdk.internal._kafka")
        await consumer._handle_record(tp, rec)
        assert called == []
        assert any("failed to parse" in r.message for r in caplog.records)
    finally:
        await consumer._dispatcher._http.close()


def _rec(offset: int) -> MagicMock:
    r = MagicMock()
    r.offset = offset
    r.value = b'{"x": 1}'  # 未用(下方 stub 掉 _handle_record),仅占位
    return r


async def _drive_one_batch(
    consumer: KafkaTaskConsumer,
    mock: MagicMock,
    batch: dict[Any, list[Any]],
    dispositions: dict[int, DispatchDisposition],
) -> tuple[list[int], dict[Any, int]]:
    """驱动 poll 循环消费**一个** batch 后退出,返回(处理过的 offset 顺序, commit 的 offsets)。

    ``_handle_record`` 被 stub 成按 offset 查表返回 disposition,以便精确控制
    valid / withhold(RETRY_LATER)/ drop 组合,聚焦验证 offset 天花板与 commit 过滤。
    """
    handled: list[int] = []

    async def _fake_handle(tp: Any, rec: Any) -> DispatchDisposition:
        handled.append(rec.offset)
        return dispositions[rec.offset]

    consumer._handle_record = _fake_handle  # type: ignore[method-assign]

    n = {"i": 0}

    async def _getmany(**_kw: Any) -> dict[Any, list[Any]]:
        n["i"] += 1
        if n["i"] == 1:
            return batch
        consumer._running = False
        return {}

    mock.getmany = _getmany
    commit_mock = AsyncMock()
    mock.commit = commit_mock
    consumer._running = True
    await consumer._poll_loop()

    committed: dict[Any, int] = {}
    if commit_mock.call_args is not None:
        committed = commit_mock.call_args.args[0]
    return handled, committed


async def test_withhold_no_hol_keeps_consuming_and_sets_ceiling() -> None:
    """HOL 消除回归:valid(10)→foreign/withhold(11)→v3/withhold(12) 同分区。

    三条都必须被处理(无 seek/pause/break 冻结分区);foreign/v3 不 commit;
    天花板取最低 withheld offset=11;仅天花板之下的 valid(10)提交(offset+1=11)。
    对齐 TS #826:withhold 记 commit 天花板 + 继续消费,而非 pause 冻结。
    """
    consumer, _dispatcher, mock = await _make_consumer()
    try:
        tp = "p0"
        batch = {tp: [_rec(10), _rec(11), _rec(12)]}
        dispositions = {
            10: DispatchDisposition.ACCEPTED,
            11: DispatchDisposition.RETRY_LATER,  # foreign tenant → withhold
            12: DispatchDisposition.RETRY_LATER,  # unknown schema v3 → withhold
        }
        handled, committed = await _drive_one_batch(consumer, mock, batch, dispositions)

        # 三条全部被投递到 handler —— 首条 withhold 未冻结分区(HOL 消除)。
        assert handled == [10, 11, 12], f"all 3 records must be delivered, got {handled}"
        # 绝不 seek / pause withhold 路径。
        assert mock.seek.call_count == 0
        assert mock.pause.call_count == 0
        # 天花板 = 最低 withheld offset。
        assert consumer.withheld_ceilings == {tp: 11}
        # 只提交天花板之下的 valid(10)→ next offset 11;withheld(11)从原位重投。
        assert committed == {tp: 11}, f"must commit only below-ceiling record, got {committed}"
    finally:
        await consumer._dispatcher._http.close()


async def test_withhold_first_record_commits_nothing_no_clamp() -> None:
    """不丢消息回归:分区首条即 withhold(5),后续 valid(6/7)照常投递但不越天花板。

    天花板=5;5/6/7 都不提交(全部 >= 天花板)——**绝不夹逼到 ceiling-1**
    (那会 commit offset 5,静默跳过 withheld 消息 = 丢消息,TS 踩过的坑)。
    withheld(5)及其后须靠 rebalance/重启从原位重投。valid(6/7)仍被 handler
    投递(HOL 消除),仅不推进 offset。
    """
    consumer, _dispatcher, mock = await _make_consumer()
    try:
        tp = "p0"
        batch = {tp: [_rec(5), _rec(6), _rec(7)]}
        dispositions = {
            5: DispatchDisposition.RETRY_LATER,  # 首条即 withhold
            6: DispatchDisposition.ACCEPTED,
            7: DispatchDisposition.ACCEPTED,
        }
        handled, committed = await _drive_one_batch(consumer, mock, batch, dispositions)

        assert handled == [5, 6, 7], f"withhold must not freeze partition, got {handled}"
        assert consumer.withheld_ceilings == {tp: 5}
        # 该分区无任何可提交 offset:5/6/7 全部 >= 天花板 5 → 不 commit。
        assert tp not in committed, (
            f"nothing below ceiling → partition must not be committed (no clamp to ceiling-1), "
            f"got {committed}"
        )
    finally:
        await consumer._dispatcher._http.close()


async def test_withhold_ceiling_persists_across_polls_blocks_later_commit() -> None:
    """跨 poll 天花板持久:上一 poll 的 withhold(8)必阻塞后续 poll 中 offset>=8 的 commit。

    天花板字典按 tp 跨 poll 维护;第二个 batch 的 valid(9)虽被处理,但 9>=8
    → 不提交,保证 withheld(8)不被越过。
    """
    consumer, _dispatcher, mock = await _make_consumer()
    try:
        tp = "p0"
        # 先手动置天花板(等价于前一 poll 的 withhold)。
        consumer._withheld_ceilings[tp] = 8
        batch = {tp: [_rec(9)]}
        dispositions = {9: DispatchDisposition.ACCEPTED}
        handled, committed = await _drive_one_batch(consumer, mock, batch, dispositions)

        assert handled == [9]
        assert tp not in committed, f"offset 9 >= ceiling 8 must not commit, got {committed}"
        assert consumer.withheld_ceilings == {tp: 8}
    finally:
        await consumer._dispatcher._http.close()


@pytest.mark.asyncio
async def test_poll_loop_crash_sets_crashed_flag() -> None:
    """poll 循环因非取消异常死亡 → crashed=True, running=False(供 client.metrics 判活)。"""
    consumer, _dispatcher, mock = await _make_consumer()
    # getmany 抛非 CancelledError → 命中 _poll_loop 的 `except Exception` 崩溃分支
    mock.getmany.side_effect = RuntimeError("broker gone")
    consumer._running = True
    assert consumer.crashed is False
    await consumer._poll_loop()
    assert consumer.crashed is True
    assert consumer.running is False

"""KafkaTaskConsumer 单元测试(P2)。

底层 ``AIOKafkaConsumer`` 用 ``MagicMock`` 替代,这样不需要真的 broker。
fixture 级 pause/resume 在 contract runner 中做端到端;这里只覆盖单元
分支(缺配置、subscribe 路径、rebalance listener 重置缓存)。
"""

from __future__ import annotations

import asyncio
import json
from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

from batch_worker_sdk import BatchPlatformClientConfig, TaskDispatcher, WorkerRuntimeState
from batch_worker_sdk.dispatcher.dispatcher import DispatchDisposition
from batch_worker_sdk.exceptions import PlatformError
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


def test_new_consumer_group_defaults_to_earliest(monkeypatch: pytest.MonkeyPatch) -> None:
    """无已提交位置时, 默认从最早保留位置重读。"""
    factory = MagicMock()
    monkeypatch.setattr("batch_worker_sdk.internal._kafka.AIOKafkaConsumer", factory)
    consumer = KafkaTaskConsumer(_cfg(), MagicMock())
    consumer._build_consumer()
    assert factory.call_args.kwargs["auto_offset_reset"] == "earliest"


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
    """分配后重置容量暂停状态。"""
    consumer, _dispatcher, _mock = await _make_consumer()
    try:
        consumer._capacity_paused = True
        listener = _PauseAwareRebalanceListener(consumer)
        await listener.on_partitions_assigned(set())
        assert consumer._capacity_paused is False
    finally:
        await consumer._dispatcher._http.close()


async def test_revoke_clears_only_transferred_partition() -> None:
    """转交分区后清理旧上限,重新分配时可使用 broker 的提交位置。"""
    consumer, _dispatcher, _mock = await _make_consumer()
    try:
        consumer._withheld_ceilings.update({"p0": 5, "p1": 9})  # type: ignore[dict-item]
        listener = _PauseAwareRebalanceListener(consumer)
        await listener.on_partitions_revoked({"p0"})  # type: ignore[arg-type]
        assert consumer.withheld_ceilings == {"p1": 9}
    finally:
        await consumer._dispatcher._http.close()


async def test_capacity_resume_excludes_withheld_partition() -> None:
    """容量恢复不提前恢复尚未到期的暂扣分区。"""
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
            # 某分区正处于暂扣等待期。
            consumer._withheld_ceilings["part-0"] = 42  # type: ignore[index]
            consumer._withheld_retry_at["part-0"] = float("inf")  # type: ignore[index]
            # 容量恢复(排干到 < max//2)。
            while dispatcher.in_flight_count() >= 2:
                t = next(iter(dispatcher._in_flight))
                dispatcher._in_flight[t].cancel()
                dispatcher._in_flight.pop(t)
            consumer.apply_backpressure()
            assert mock.resume.call_count == 1
            resumed = set(mock.resume.call_args.args)
            assert resumed == {"part-1"}
            # 上限不被容量 resume 影响。
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
    valid / WITHHOLD / RETRY_LATER / drop 组合,聚焦验证 offset 上限与 commit 过滤。
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


async def test_withhold_only_pauses_affected_partition() -> None:
    """同批次阻止受阻分区后续执行,同时继续提交其他分区。"""
    consumer, _dispatcher, mock = await _make_consumer()
    mock.assignment.return_value = {"p0", "p1"}
    try:
        handled, committed = await _drive_one_batch(
            consumer,
            mock,
            {"p0": [_rec(5), _rec(6)], "p1": [_rec(7)]},
            {
                5: DispatchDisposition.WITHHOLD,
                6: DispatchDisposition.ACCEPTED,
                7: DispatchDisposition.ACCEPTED,
            },
        )
        assert handled == [5, 7]
        assert mock.commit.await_args_list[0].args[0] == {"p0": 5, "p1": 7}
        assert committed == {"p1": 8}
        mock.pause.assert_any_call("p0")
        mock.seek.assert_any_call("p0", 5)
        assert consumer.withheld_ceilings == {"p0": 5}
    finally:
        await consumer._dispatcher._http.close()


async def test_withheld_retry_commits_then_resumes_next_offset() -> None:
    """暂停到期后重读并成功提交,才能继续处理后续消息。"""
    consumer, _dispatcher, mock = await _make_consumer()
    mock.assignment.return_value = {"p0"}
    try:
        await _drive_one_batch(
            consumer, mock, {"p0": [_rec(5), _rec(6)]}, {5: DispatchDisposition.WITHHOLD}
        )
        consumer._resume_due_withheld()
        mock.resume.assert_not_called()
        consumer._withheld_retry_at["p0"] = 0.0  # type: ignore[index]
        consumer._resume_due_withheld()
        mock.resume.assert_called_once_with("p0")
        handled, committed = await _drive_one_batch(
            consumer,
            mock,
            {"p0": [_rec(5), _rec(6)]},
            {5: DispatchDisposition.ACCEPTED, 6: DispatchDisposition.ACCEPTED},
        )
        assert handled == [5]
        assert committed == {"p0": 6}
        mock.seek.assert_any_call("p0", 6)
        assert consumer.withheld_ceilings == {}
    finally:
        await consumer._dispatcher._http.close()


async def test_rebalance_during_processing_does_not_commit_old_batch() -> None:
    """处理期间发生分区转交时,旧批次不得向新代际提交。"""
    consumer, _dispatcher, mock = await _make_consumer()
    try:
        listener = _PauseAwareRebalanceListener(consumer)

        async def handle(_tp: Any, _rec: Any) -> DispatchDisposition:
            await listener.on_partitions_revoked({"p0"})  # type: ignore[arg-type]
            return DispatchDisposition.ACCEPTED

        async def getmany(**_kw: Any) -> dict[str, list[Any]]:
            consumer._running = False
            return {"p0": [_rec(5)]}

        consumer._handle_record = handle  # type: ignore[method-assign]
        mock.getmany = getmany
        mock.commit = AsyncMock()
        consumer._running = True
        await consumer._poll_loop()
        mock.commit.assert_awaited_once_with({"p0": 5})
    finally:
        await consumer._dispatcher._http.close()


async def test_failed_commit_retries_first_uncommitted_message() -> None:
    """首位置提交失败时不执行业务并回退, 防止后续跳过消息。"""
    consumer, _dispatcher, mock = await _make_consumer()
    mock.assignment.return_value = {"p0"}
    mock.commit = AsyncMock(side_effect=RuntimeError("commit failed"))
    try:
        handled: list[int] = []

        async def handle(_tp: Any, rec: Any) -> DispatchDisposition:
            handled.append(rec.offset)
            return DispatchDisposition.ACCEPTED

        consumer._handle_record = handle  # type: ignore[method-assign]
        await consumer._process_batch({"p0": [_rec(5), _rec(6)]})  # type: ignore[dict-item]
        assert handled == []
        assert consumer.withheld_ceilings == {"p0": 5}
        mock.seek.assert_called_once_with("p0", 5)
        mock.pause.assert_called_once_with("p0")
    finally:
        await consumer._dispatcher._http.close()


async def test_checkpoint_precedes_dispatch() -> None:
    """首次消费先保存本批起点, 才允许异步进入业务处理。"""
    consumer, _dispatcher, mock = await _make_consumer()
    mock.commit = AsyncMock()
    try:

        async def handle(_tp: Any, _rec: Any) -> DispatchDisposition:
            mock.commit.assert_awaited_once_with({"p0": 5})
            return DispatchDisposition.ACCEPTED

        consumer._handle_record = handle  # type: ignore[method-assign]
        await consumer._process_batch({"p0": [_rec(5)]})  # type: ignore[dict-item]
        assert mock.commit.await_count == 2
        mock.commit.assert_awaited_with({"p0": 6})
    finally:
        await consumer._dispatcher._http.close()


async def test_rebalance_during_checkpoint_never_dispatches_old_batch() -> None:
    """保存首位置时发生转交, 不能处理旧分配的批次。"""
    consumer, _dispatcher, mock = await _make_consumer()
    listener = _PauseAwareRebalanceListener(consumer)
    try:

        async def commit(_offsets: Any) -> None:
            await listener.on_partitions_revoked({"p0"})  # type: ignore[arg-type]

        handle = AsyncMock(return_value=DispatchDisposition.ACCEPTED)
        consumer._handle_record = handle  # type: ignore[method-assign]
        mock.commit = AsyncMock(side_effect=commit)
        await consumer._process_batch({"p0": [_rec(5)]})  # type: ignore[dict-item]
        mock.commit.assert_awaited_once_with({"p0": 5})
        handle.assert_not_awaited()
    finally:
        await consumer._dispatcher._http.close()


async def test_withhold_stops_partition_at_first_unconfirmed_offset() -> None:
    """暂扣分区只提交暂扣位点之前的消息。"""
    consumer, _dispatcher, mock = await _make_consumer()
    try:
        tp = "p0"
        batch = {tp: [_rec(10), _rec(11), _rec(12)]}
        dispositions = {
            10: DispatchDisposition.ACCEPTED,
            11: DispatchDisposition.WITHHOLD,  # foreign tenant → withhold
            12: DispatchDisposition.WITHHOLD,  # unknown schema v3 → withhold
        }
        handled, committed = await _drive_one_batch(consumer, mock, batch, dispositions)

        assert handled == [10, 11]
        mock.seek.assert_called_once_with(tp, 11)
        mock.pause.assert_any_call(tp)
        # 上限 = 最低 withheld offset。
        assert consumer.withheld_ceilings == {tp: 11}
        # 只提交上限之下的 valid(10)→ next offset 11;withheld(11)从原位重投。
        assert committed == {tp: 11}, f"must commit only below-ceiling record, got {committed}"
    finally:
        await consumer._dispatcher._http.close()


async def test_withhold_first_record_commits_nothing_no_clamp() -> None:
    """首条暂扣时不得处理或提交同分区后续记录。"""
    consumer, _dispatcher, mock = await _make_consumer()
    try:
        tp = "p0"
        batch = {tp: [_rec(5), _rec(6), _rec(7)]}
        dispositions = {
            5: DispatchDisposition.WITHHOLD,  # 首条即 withhold
            6: DispatchDisposition.ACCEPTED,
            7: DispatchDisposition.ACCEPTED,
        }
        handled, committed = await _drive_one_batch(consumer, mock, batch, dispositions)

        assert handled == [5]
        assert consumer.withheld_ceilings == {tp: 5}
        # 只保存待重读位置 5, 不提交该消息本身。
        assert committed == {tp: 5}
    finally:
        await consumer._dispatcher._http.close()


async def test_retry_later_seeks_pauses_and_stops_partition_batch() -> None:
    """瞬时 RETRY_LATER 不得固化成 commit ceiling。

    平台 PAUSED / draining / fatal 的 poll 竞态要 seek 回本条并临时 pause;
    同分区后续记录留待恢复后重投,且不污染 withhold ceiling。
    """
    consumer, dispatcher, mock = await _make_consumer()
    try:
        dispatcher.apply_platform_directive({"runtimeState": "PAUSED"})
        tp = "p0"
        batch = {tp: [_rec(5), _rec(6)]}
        dispositions = {
            5: DispatchDisposition.RETRY_LATER,
            6: DispatchDisposition.ACCEPTED,
        }
        handled, committed = await _drive_one_batch(consumer, mock, batch, dispositions)

        assert handled == [5]
        mock.seek.assert_called_once_with(tp, 5)
        # poll 前平台态先 pause 整个 assignment;竞态记录再精确 pause 当前分区。
        mock.pause.assert_any_call(tp)
        assert consumer.paused is True
        assert consumer.withheld_ceilings == {}
        assert committed == {tp: 5}
    finally:
        await consumer._dispatcher._http.close()


async def test_withhold_ceiling_persists_across_polls_blocks_later_commit() -> None:
    """较高位点意外到达时回退到未确认位置, 不执行业务或跨越该位置。"""
    consumer, _dispatcher, mock = await _make_consumer()
    try:
        tp = "p0"
        # 先手动置上限(等价于前一 poll 的 withhold)。
        consumer._withheld_ceilings[tp] = 8
        batch = {tp: [_rec(9)]}
        dispositions = {9: DispatchDisposition.ACCEPTED}
        handled, committed = await _drive_one_batch(consumer, mock, batch, dispositions)

        assert handled == []
        assert committed == {tp: 8}
        assert consumer.withheld_ceilings == {tp: 8}
        mock.seek.assert_called_once_with(tp, 8)
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


async def test_claim_failure_retains_offset_with_actual_dispatcher() -> None:
    """真实分发器认领失败时, 消费循环回退原位且不提交同批后续 offset。"""
    consumer, dispatcher, kafka = await _make_consumer()

    async def failed_claim(*_args: Any, **_kwargs: Any) -> tuple[dict[str, Any], int]:
        raise PlatformError("unreachable before claim persistence")

    dispatcher._http.claim_status = failed_claim  # type: ignore[method-assign]
    value = json.dumps({"taskId": 901, "tenantId": "acme", "schemaVersion": "v2"}).encode()
    record = SimpleNamespace(offset=5, value=value)
    polls = 0

    async def next_batch(**_kwargs: Any) -> dict[Any, Any]:
        nonlocal polls
        polls += 1
        if polls == 1:
            return {"p0": [record]}
        consumer._running = False
        return {}

    kafka.getmany = AsyncMock(side_effect=next_batch)
    kafka.commit = AsyncMock()
    consumer._running = True
    try:
        await asyncio.wait_for(consumer._poll_loop(), timeout=2)
        kafka.seek.assert_called_once_with("p0", 5)
        kafka.commit.assert_awaited_once_with({"p0": 5})
        assert not consumer.crashed
    finally:
        await dispatcher._http.close()

package kafka

import (
	"context"
	"errors"
	"io"
	"log"
	"testing"
	"time"

	kgo "github.com/segmentio/kafka-go"
)

type testCommitter struct {
	err     error
	offsets map[string]map[int]int64
}

func (c *testCommitter) CommitOffsets(offsets map[string]map[int]int64) error {
	c.offsets = offsets
	return c.err
}

func testConsumer(t *testing.T) *Consumer {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	return &Consumer{ctx: ctx, cancel: cancel, events: make(chan *delivery), resumeCh: make(chan struct{}, 1), withheld: make(map[tpKey]int64)}
}

func TestWithholdAllowsOtherPartition(t *testing.T) {
	c := testConsumer(t)
	bad := &delivery{message: kgo.Message{Topic: "a", Partition: 0, Offset: 2}, ctx: c.ctx, decision: make(chan bool, 1)}
	c.active, c.pending = bad, []kgo.Message{bad.message}
	c.Withhold()
	if <-bad.decision {
		t.Fatal("拒绝的消息被确认")
	}
	other := &delivery{message: kgo.Message{Topic: "a", Partition: 1, Offset: 3}, ctx: c.ctx, decision: make(chan bool, 1)}
	go func() { c.events <- other }()
	records, ok := c.Poll()
	if !ok || len(records) != 1 || c.pending[0].Partition != 1 {
		t.Fatal("其他分区未继续消费")
	}
}

func TestPollRetainsDeliveryWhilePaused(t *testing.T) {
	c := testConsumer(t)
	item := &delivery{message: kgo.Message{Topic: "a", Partition: 0}, ctx: c.ctx, decision: make(chan bool, 1)}
	done := make(chan []interface{}, 1)
	go func() { records, ok := c.Poll(); done <- []interface{}{records, ok} }()
	// 等待 Poll 进入接收，再并发暂停；记录到达后仍不得提前交付。
	time.Sleep(30 * time.Millisecond)
	c.Pause()
	go func() { c.events <- item }()
	select {
	case <-done:
		t.Fatal("暂停期间交付了记录")
	case <-time.After(30 * time.Millisecond):
	}
	c.Resume()
	select {
	case result := <-done:
		if !result[1].(bool) {
			t.Fatal("恢复后消费者停止")
		}
	case <-time.After(time.Second):
		t.Fatal("恢复后没有交付记录")
	}
}

func TestCommitFailureRetriesCurrentPartition(t *testing.T) {
	c := testConsumer(t)
	committer := &testCommitter{err: errors.New("commit failed")}
	item := &delivery{message: kgo.Message{Topic: "a", Partition: 2, Offset: 7}, ctx: c.ctx, generation: committer, decision: make(chan bool, 1)}
	c.logger = log.New(io.Discard, "", 0)
	c.active, c.pending = item, []kgo.Message{item.message}
	c.Commit()
	if <-item.decision {
		t.Fatal("提交失败后错误地放行下一条")
	}
	if got := committer.offsets["a"][2]; got != 8 {
		t.Fatalf("提交位置=%d，预期 8", got)
	}
}

func TestExpiredGenerationCannotCommit(t *testing.T) {
	c := testConsumer(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	committer := &testCommitter{}
	item := &delivery{message: kgo.Message{Topic: "a", Partition: 0, Offset: 1}, ctx: ctx, generation: committer, decision: make(chan bool, 1)}
	c.active, c.pending = item, []kgo.Message{item.message}
	c.Commit()
	if committer.offsets != nil {
		t.Fatal("失效代际不应提交")
	}
}

package client

import (
	"context"
	"testing"
	"time"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/protocol"
)

func strp(s string) *string { return &s }
func boolp(b bool) *bool    { return &b }

// DRAINING directive -> FSM DRAINING + consumer paused + onDrain fired.
func TestHeartbeat_DrainingDirective(t *testing.T) {
	fp := NewFakePlatform()
	fp.ScriptHeartbeat(protocol.HeartbeatResponse{PlatformStatus: strp("DRAINING")}, nil)

	fsm := NewFSM()
	drained := make(chan struct{}, 1)
	hb := NewHeartbeatScheduler(fp, fsm, "w1", "t1",
		WithHeartbeatInterval(time.Millisecond),
		WithOnDrain(func() { drained <- struct{}{} }),
	)

	if _, err := hb.Beat(context.Background()); err != nil {
		t.Fatalf("beat error: %v", err)
	}
	if fsm.State() != StateDraining {
		t.Fatalf("expected FSM DRAINING, got %s", fsm.State())
	}
	if !fsm.Paused() {
		t.Fatalf("expected Kafka paused on DRAINING")
	}
	select {
	case <-drained:
	default:
		t.Fatalf("expected onDrain callback to fire")
	}
}

// PAUSED directive -> FSM PAUSED + paused, no drain.
func TestHeartbeat_PausedDirective(t *testing.T) {
	fp := NewFakePlatform()
	fp.ScriptHeartbeat(protocol.HeartbeatResponse{PlatformStatus: strp("PAUSED")}, nil)
	fsm := NewFSM()
	hb := NewHeartbeatScheduler(fp, fsm, "w1", "t1", WithHeartbeatInterval(time.Millisecond))
	if _, err := hb.Beat(context.Background()); err != nil {
		t.Fatalf("beat: %v", err)
	}
	if fsm.State() != StatePaused {
		t.Fatalf("expected PAUSED, got %s", fsm.State())
	}
	if !fsm.Paused() {
		t.Fatalf("expected consumer paused")
	}
}

// nextHeartbeatHint PT15S -> interval becomes 15s (dynamic re-pacing).
func TestHeartbeat_DynamicRepacing(t *testing.T) {
	fp := NewFakePlatform()
	fp.ScriptHeartbeat(protocol.HeartbeatResponse{
		PlatformStatus:    strp("NORMAL"),
		NextHeartbeatHint: "PT15S",
	}, nil)
	fsm := NewFSM()
	hb := NewHeartbeatScheduler(fp, fsm, "w1", "t1", WithHeartbeatInterval(30*time.Second))

	if hb.Interval() != 30*time.Second {
		t.Fatalf("expected initial 30s, got %v", hb.Interval())
	}
	if _, err := hb.Beat(context.Background()); err != nil {
		t.Fatalf("beat: %v", err)
	}
	if hb.Interval() != 15*time.Second {
		t.Fatalf("expected interval re-paced to 15s, got %v", hb.Interval())
	}
}

// NORMAL after PAUSED -> resumes consumer.
func TestHeartbeat_NormalResumes(t *testing.T) {
	fp := NewFakePlatform()
	fp.ScriptHeartbeat(protocol.HeartbeatResponse{PlatformStatus: strp("NORMAL")}, nil)
	fsm := NewFSM()
	fsm.SetPaused(true)
	hb := NewHeartbeatScheduler(fp, fsm, "w1", "t1", WithHeartbeatInterval(time.Millisecond))
	if _, err := hb.Beat(context.Background()); err != nil {
		t.Fatalf("beat: %v", err)
	}
	if fsm.Paused() {
		t.Fatalf("expected NORMAL to resume (unpause) the consumer")
	}
}

// Lease renew: cancelRequested=true -> task signal MarkCancelled.
func TestLeaseRenew_CancelRequested(t *testing.T) {
	fp := NewFakePlatform()
	fp.ScriptRenew("task-1", protocol.RenewResponse{CancelRequested: boolp(true)})

	reg := NewInFlightRegistry()
	sig := NewCancellationSignal(context.Background())
	reg.Add("task-1", sig)

	ls := NewLeaseRenewalScheduler(fp, reg, "w1", "t1", WithLeaseInterval(time.Millisecond))
	ls.RenewOnce(context.Background())

	if !sig.IsCancellationRequested() {
		t.Fatalf("expected cancellation signalled on cancelRequested=true")
	}
}

// Lease renew: 404 -> drop task locally.
func TestLeaseRenew_NotFoundDrops(t *testing.T) {
	fp := NewFakePlatform()
	fp.RenewErr = &NotFoundError{Op: "renew"}

	reg := NewInFlightRegistry()
	reg.Add("task-1", NewCancellationSignal(context.Background()))
	ls := NewLeaseRenewalScheduler(fp, reg, "w1", "t1", WithLeaseInterval(time.Millisecond))

	dropped := ls.RenewOnce(context.Background())
	if len(dropped) != 1 || dropped[0] != "task-1" {
		t.Fatalf("expected task-1 dropped, got %v", dropped)
	}
	if reg.Count() != 0 {
		t.Fatalf("expected registry empty after drop, got %d", reg.Count())
	}
}

package client

import (
	"io"
	"log"
	"testing"
)

func quietLogger() *log.Logger { return log.New(io.Discard, "", 0) }

func rec(payload string) Record {
	return Record{Topic: "batch.task.dispatch.t1.0", Value: []byte(payload)}
}

// Unknown schemaVersion major -> rejected, offset withheld.
func TestPipeline_SchemaVersionReject(t *testing.T) {
	p := NewMessagePipeline("t1", NewFSM(), 4, func() int { return 0 }, quietLogger())
	_, disp := p.OnMessage(rec(`{"taskId":"x","tenantId":"t1","schemaVersion":"v3"}`))
	if disp != DispositionRejectedSchema {
		t.Fatalf("expected REJECTED_SCHEMA for v3, got %s", disp)
	}
}

// Known major (v2-rc) -> accepted.
func TestPipeline_SchemaVersionKnownMajor(t *testing.T) {
	p := NewMessagePipeline("t1", NewFSM(), 4, func() int { return 0 }, quietLogger())
	_, disp := p.OnMessage(rec(`{"taskId":"x","tenantId":"t1","schemaVersion":"v2-rc"}`))
	if disp != DispositionAccepted {
		t.Fatalf("expected ACCEPTED for v2-rc, got %s", disp)
	}
}

// Missing schemaVersion -> treated as v1 -> accepted.
func TestPipeline_SchemaVersionMissingAcceptsV1(t *testing.T) {
	p := NewMessagePipeline("t1", NewFSM(), 4, func() int { return 0 }, quietLogger())
	_, disp := p.OnMessage(rec(`{"taskId":"x","tenantId":"t1"}`))
	if disp != DispositionAccepted {
		t.Fatalf("expected ACCEPTED for missing schemaVersion, got %s", disp)
	}
}

// Foreign tenant -> dropped (§1.9).
func TestPipeline_ForeignTenantDrop(t *testing.T) {
	p := NewMessagePipeline("t1", NewFSM(), 4, func() int { return 0 }, quietLogger())
	_, disp := p.OnMessage(rec(`{"taskId":"x","tenantId":"OTHER","schemaVersion":"v1"}`))
	if disp != DispositionDroppedForeignTenant {
		t.Fatalf("expected DROPPED_FOREIGN_TENANT, got %s", disp)
	}
}

// At capacity -> backpressure, FSM paused.
func TestPipeline_Backpressure(t *testing.T) {
	fsm := NewFSM()
	p := NewMessagePipeline("t1", fsm, 2, func() int { return 2 }, quietLogger())
	_, disp := p.OnMessage(rec(`{"taskId":"x","tenantId":"t1","schemaVersion":"v1"}`))
	if disp != DispositionBackpressure {
		t.Fatalf("expected BACKPRESSURE, got %s", disp)
	}
	if !fsm.Paused() {
		t.Fatalf("expected FSM paused under backpressure")
	}
}

// Malformed JSON -> decode error.
func TestPipeline_DecodeError(t *testing.T) {
	p := NewMessagePipeline("t1", NewFSM(), 4, func() int { return 0 }, quietLogger())
	_, disp := p.OnMessage(rec(`not json`))
	if disp != DispositionDecodeError {
		t.Fatalf("expected DECODE_ERROR, got %s", disp)
	}
}

package client

import (
	"context"
	"testing"
)

func TestNewFakePlatformT_returnsUsablePlatform(t *testing.T) {
	p := NewFakePlatformT(t)
	if p == nil {
		t.Fatal("NewFakePlatformT returned nil")
	}
	// RenewScript must be initialized (same as NewFakePlatform).
	if p.RenewScript == nil {
		t.Fatal("RenewScript not initialized")
	}
}

func TestRequireReport_findsRecordedReport(t *testing.T) {
	p := NewFakePlatformT(t)
	if err := p.Report(context.Background(), "task-7", "idem-1", ReportRequest{Success: true}); err != nil {
		t.Fatalf("Report: %v", err)
	}
	got := RequireReport(t, p, "task-7")
	if got.TaskID != "task-7" || !got.Result.Success {
		t.Fatalf("unexpected report: %+v", got)
	}
}

func TestRequireReport_failsWhenAbsent(t *testing.T) {
	p := NewFakePlatformT(t)
	spy := &spyTB{}
	RequireReport(spy, p, "missing")
	if !spy.failed {
		t.Fatal("expected RequireReport to fail the test when no report exists")
	}
	if !spy.helperCalled {
		t.Fatal("expected RequireReport to mark itself a Helper")
	}
}

// spyTB captures Helper()/Fatalf() so the absent-report path can be asserted
// without aborting the real test.
type spyTB struct {
	helperCalled bool
	failed       bool
}

func (s *spyTB) Helper()               { s.helperCalled = true }
func (s *spyTB) Fatalf(string, ...any) { s.failed = true }

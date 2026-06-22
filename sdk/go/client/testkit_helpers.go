package client

// Test-framework ergonomics over FakePlatform — the Go equivalent of the Java
// testkit's @BatchWorkerTest auto-provided platform + awaitReport assertion.
//
// These accept a minimal TB interface rather than *testing.T so the production
// `client` package never imports "testing"/"flag" (a real *testing.T satisfies
// TB, so call sites just pass `t`).

// TB is the subset of *testing.T the helpers use. *testing.T satisfies it.
type TB interface {
	Helper()
	Fatalf(format string, args ...any)
}

// NewFakePlatformT builds a FakePlatform and marks the caller as a test helper,
// so a failed assertion in a helper points at the test line, not here. Mirrors
// the Java @BatchWorkerTest-injected FakeBatchPlatform.
func NewFakePlatformT(t TB) *FakePlatform {
	t.Helper()
	return NewFakePlatform()
}

// RequireReport returns the report recorded for taskID (last wins), failing the
// test if none was recorded. Mirrors the Java testkit's awaitReport(taskId)
// assertion sugar (ReportFor is the non-fatal variant).
func RequireReport(t TB, p *FakePlatform, taskID string) ReportCall {
	t.Helper()
	c, ok := p.ReportFor(taskID)
	if !ok {
		t.Fatalf("no report recorded for task %q", taskID)
	}
	return c
}

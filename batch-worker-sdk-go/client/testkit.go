package client

import (
	"context"
	"sync"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/protocol"
)

// FakePlatform is an in-memory Transport that scripts responses and records
// every call. It pairs with FakeConsumer (consumer.go) to drive the whole
// runtime engine with no real network or broker — the verifiable "fake
// platform" of phase 2.
type FakePlatform struct {
	mu sync.Mutex

	// Scripted responses (zero values are sane defaults).
	RegisterResp    RegisterResult
	RegisterErr     error
	HeartbeatResp   protocol.HeartbeatResponse
	HeartbeatErr    error
	HeartbeatScript []heartbeatStep // optional per-call override queue
	ClaimResp       ClaimResult
	ClaimErr        error
	ClaimScript     []claimStep
	ReportErr       error
	RenewResp       protocol.RenewResponse
	RenewErr        error
	RenewScript     map[string]protocol.RenewResponse // per-taskId override
	DeactivateErr   error

	// Recorded calls.
	RegisterCalls   []RegisterRequest
	HeartbeatCalls  []HeartbeatRequest
	DeactivateCalls []string
	ClaimCalls      []string
	ReportCalls     []ReportCall
	RenewCalls      []string
}

type heartbeatStep struct {
	resp protocol.HeartbeatResponse
	err  error
}

type claimStep struct {
	resp ClaimResult
	err  error
}

// ReportCall captures a report invocation for assertions.
type ReportCall struct {
	TaskID         string
	IdempotencyKey string
	Result         TaskResult
}

// NewFakePlatform builds a FakePlatform with empty scripts.
func NewFakePlatform() *FakePlatform {
	return &FakePlatform{RenewScript: map[string]protocol.RenewResponse{}}
}

// ScriptHeartbeat queues a heartbeat response consumed in order (then falls back
// to HeartbeatResp/HeartbeatErr).
func (p *FakePlatform) ScriptHeartbeat(resp protocol.HeartbeatResponse, err error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.HeartbeatScript = append(p.HeartbeatScript, heartbeatStep{resp, err})
}

// ScriptClaim queues a claim response consumed in order.
func (p *FakePlatform) ScriptClaim(resp ClaimResult, err error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.ClaimScript = append(p.ClaimScript, claimStep{resp, err})
}

// ScriptRenew sets a per-task renew response.
func (p *FakePlatform) ScriptRenew(taskID string, resp protocol.RenewResponse) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.RenewScript == nil {
		p.RenewScript = map[string]protocol.RenewResponse{}
	}
	p.RenewScript[taskID] = resp
}

// Register implements Transport.
func (p *FakePlatform) Register(_ context.Context, req RegisterRequest) (RegisterResult, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.RegisterCalls = append(p.RegisterCalls, req)
	return p.RegisterResp, p.RegisterErr
}

// Heartbeat implements Transport.
func (p *FakePlatform) Heartbeat(_ context.Context, _ string, req HeartbeatRequest) (protocol.HeartbeatResponse, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.HeartbeatCalls = append(p.HeartbeatCalls, req)
	if len(p.HeartbeatScript) > 0 {
		step := p.HeartbeatScript[0]
		p.HeartbeatScript = p.HeartbeatScript[1:]
		return step.resp, step.err
	}
	return p.HeartbeatResp, p.HeartbeatErr
}

// Deactivate implements Transport.
func (p *FakePlatform) Deactivate(_ context.Context, workerCode string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.DeactivateCalls = append(p.DeactivateCalls, workerCode)
	return p.DeactivateErr
}

// Claim implements Transport.
func (p *FakePlatform) Claim(_ context.Context, taskID, _ string) (ClaimResult, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.ClaimCalls = append(p.ClaimCalls, taskID)
	if len(p.ClaimScript) > 0 {
		step := p.ClaimScript[0]
		p.ClaimScript = p.ClaimScript[1:]
		return step.resp, step.err
	}
	return p.ClaimResp, p.ClaimErr
}

// Report implements Transport.
func (p *FakePlatform) Report(_ context.Context, taskID, idempotencyKey string, result TaskResult) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.ReportCalls = append(p.ReportCalls, ReportCall{TaskID: taskID, IdempotencyKey: idempotencyKey, Result: result})
	return p.ReportErr
}

// Renew implements Transport.
func (p *FakePlatform) Renew(_ context.Context, taskID string, _ RenewRequest) (protocol.RenewResponse, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.RenewCalls = append(p.RenewCalls, taskID)
	if resp, ok := p.RenewScript[taskID]; ok {
		return resp, p.RenewErr
	}
	return p.RenewResp, p.RenewErr
}

// ReportFor returns the recorded report for taskID (last wins) and whether one exists.
func (p *FakePlatform) ReportFor(taskID string) (ReportCall, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	var found ReportCall
	ok := false
	for _, c := range p.ReportCalls {
		if c.TaskID == taskID {
			found = c
			ok = true
		}
	}
	return found, ok
}

// Counts returns call counts for quick assertions.
func (p *FakePlatform) Counts() (register, heartbeat, claim, report, renew, deactivate int) {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.RegisterCalls), len(p.HeartbeatCalls), len(p.ClaimCalls),
		len(p.ReportCalls), len(p.RenewCalls), len(p.DeactivateCalls)
}

var _ Transport = (*FakePlatform)(nil)

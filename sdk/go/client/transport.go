package client

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/protocol"
)

// Transport is the control-plane HTTP surface (byo-sdk-guide §1.1's 8 endpoints,
// minus the internal-only cancel/renew-batch). All methods are blocking and
// already route status through protocol.ClassifyHTTP — callers act on the
// returned error class, never raw status codes.
type Transport interface {
	Register(ctx context.Context, req RegisterRequest) (RegisterResult, error)
	Heartbeat(ctx context.Context, workerCode string, req HeartbeatRequest) (protocol.HeartbeatResponse, error)
	Deactivate(ctx context.Context, workerCode string) error
	Claim(ctx context.Context, taskID, idempotencyKey string, req ClaimRequest) (ClaimResult, error)
	Report(ctx context.Context, taskID, idempotencyKey string, req ReportRequest) error
	Renew(ctx context.Context, taskID string, req RenewRequest) (RenewResult, error)
}

// ClaimRequest is the TaskClaimRequest body (openapi). claim / renew share the
// schema; workerId == workerCode (ADR-035 §9). partitionInvocationId is optional.
type ClaimRequest struct {
	TenantID              string `json:"tenantId"`
	WorkerID              string `json:"workerId"`
	PartitionInvocationID string `json:"partitionInvocationId,omitempty"`
}

// ReportRequest is the TaskExecutionReportDto wire body (openapi). Required
// fields [taskId, tenantId, workerId, success] are always populated; the
// handler-supplied TaskResult fields (errorCode / outputs / resultSummary) are
// folded in. Field names are a HARD contract — errorCode (not errorClass),
// outputs (not output).
type ReportRequest struct {
	TaskID        string             `json:"taskId"`
	TenantID      string             `json:"tenantId"`
	WorkerID      string             `json:"workerId"`
	Success       bool               `json:"success"`
	ErrorCode     protocol.ErrorCode `json:"errorCode,omitempty"`
	Outputs       map[string]any     `json:"outputs,omitempty"`
	ResultSummary string             `json:"resultSummary,omitempty"`
	// PartitionInvocationID carries the claim-time partitionInvocationId through
	// to report so the platform's late-invocation CAS guard isn't skipped
	// (ADR-014, R3-P0-5; must be invariant across claim→renew→report).
	PartitionInvocationID string `json:"partitionInvocationId,omitempty"`
}

// NewReportRequest folds a handler TaskResult into the wire report DTO, filling
// the openapi-required identity + success fields. partitionInvocationID is the
// value cached at claim (empty for non-partition tasks).
func NewReportRequest(taskID, tenantID, workerID, partitionInvocationID string, result TaskResult) ReportRequest {
	code := "SUCCESS"
	if !result.IsSuccess() {
		code = string(result.ErrorCode)
	}
	// result_summary is a JSONB column on the platform (`#{resultSummary}::jsonb`), so it
	// must be VALID JSON — a bare human string ("echoed 0 param(s)") fails with
	// "invalid input syntax for type json" and the report 500s. Mirror the built-in
	// worker contract: a {"code","message"} JSON object (DefaultTaskExecutionWrapper).
	summaryJSON, err := json.Marshal(map[string]string{"code": code, "message": result.ResultSummary})
	if err != nil {
		summaryJSON = []byte(`{"code":"SUCCESS","message":""}`)
	}
	return ReportRequest{
		TaskID:                taskID,
		TenantID:              tenantID,
		WorkerID:              workerID,
		Success:               result.IsSuccess(),
		ErrorCode:             result.ErrorCode,
		Outputs:               result.Outputs,
		ResultSummary:         string(summaryJSON),
		PartitionInvocationID: partitionInvocationID,
	}
}

// RenewResult carries the decoded renew response plus the Revoked flag, set when
// the platform returned 409 (lease reclaimed / zombie claim). On Revoked the
// caller MUST stop the handler and abandon the report (openapi renew 409).
type RenewResult struct {
	protocol.RenewResponse
	Revoked bool
}

// RegisterRequest is the WorkerHeartbeatDto fingerprint sent at startup
// (byo-sdk-guide §1.1). Attributes carries arbitrary capability tags; it is
// scanned by SensitiveValidator before send.
type RegisterRequest struct {
	WorkerCode string `json:"workerCode"`
	TenantID   string `json:"tenantId"`
	// WorkerGroup is fixed to "sdk-self-hosted" for SDK self-hosted workers (ADR-035 §2):
	// the platform requires it (worker_registry.worker_group is NOT NULL) and selects SDK
	// workers by this group. Omitting it makes register fail with HTTP 500. The decision-core
	// (protocol/request.go) already encodes this value; the transport struct was missing it.
	WorkerGroup    string         `json:"workerGroup"`
	BuildID        string         `json:"buildId"`
	SDKVersion     string         `json:"sdkVersion"`
	CapabilityTags []string       `json:"capabilityTags,omitempty"`
	Attributes     map[string]any `json:"attributes,omitempty"`
	// ProtocolVersion advertises the SDK's current wire-protocol major (#536
	// register-time gate). Register only — heartbeat carries null.
	ProtocolVersion string `json:"protocolVersion,omitempty"`
}

// RegisterResult reports whether the platform created a fresh record or reused
// an existing (idempotent) one.
type RegisterResult struct {
	Idempotent bool
}

// HeartbeatRequest is the periodic liveness ping body.
type HeartbeatRequest struct {
	WorkerCode string `json:"workerCode"`
	TenantID   string `json:"tenantId"`
	InFlight   int    `json:"inFlight"`
	State      string `json:"state"`
}

// ClaimResult carries the EffectiveTaskConfig snapshot + lease bound.
type ClaimResult struct {
	EffectiveConfig map[string]any `json:"effectiveConfig"`
	LeaseUntil      string         `json:"leaseUntil"`
	TraceID         string         `json:"traceId"`
	// Idempotent is set when the claim returned 409 (already claimed / reclaimed).
	Idempotent bool `json:"-"`
}

// RenewRequest is the TaskHeartbeatRequest lease-renew body (openapi). The
// platform reads workerId (== workerCode, ADR-035 §9); tenantId is required.
type RenewRequest struct {
	WorkerID string `json:"workerId"`
	TenantID string `json:"tenantId"`
	// PartitionInvocationID is required for partition tasks (fixture 14): without
	// it the platform's CAS renew fails → renewLease=false → 409 → the task is
	// dropped + redispatched → double-run. Empty for non-partition tasks.
	PartitionInvocationID string `json:"partitionInvocationId,omitempty"`
}

// ---------------------------------------------------------------------------
// Transport errors
// ---------------------------------------------------------------------------

// FatalError marks an unrecoverable auth failure (401/403): the worker must
// stop accepting work and let the container restart (byo-sdk-guide §1.7).
type FatalError struct {
	Status int
	Op     string
}

func (e *FatalError) Error() string {
	return fmt.Sprintf("fatal auth error on %s: http %d", e.Op, e.Status)
}

// IsFatal reports whether err (or anything it wraps) is a FatalError.
func IsFatal(err error) bool {
	var fe *FatalError
	return errors.As(err, &fe)
}

// NotFoundError marks a 404 (workerCode/taskId gone). Caller drops the request.
type NotFoundError struct{ Op string }

func (e *NotFoundError) Error() string { return "not found: " + e.Op }

// asNotFound reports whether err is (or wraps) a *NotFoundError.
func asNotFound(err error, target **NotFoundError) bool {
	return errors.As(err, target)
}

// ClientError marks a non-auth 4xx that has not yet hit the fail-fast threshold.
type ClientError struct {
	Status int
	Op     string
}

func (e *ClientError) Error() string {
	return fmt.Sprintf("client error on %s: http %d", e.Op, e.Status)
}

// RetryExhaustedError marks a 5xx/transport failure that survived all backoff
// attempts.
type RetryExhaustedError struct {
	Op   string
	Last error
}

func (e *RetryExhaustedError) Error() string {
	return fmt.Sprintf("retries exhausted on %s: %v", e.Op, e.Last)
}

func (e *RetryExhaustedError) Unwrap() error { return e.Last }

// ---------------------------------------------------------------------------
// HTTPTransport
// ---------------------------------------------------------------------------

// HTTPTransport is the net/http implementation. The http.Client carries an
// explicit 10s Timeout (byo-sdk-guide §4 Go pit — the stdlib default is no
// timeout, which hangs the heartbeat loop and kills the worker). The underlying
// Transport keeps connections alive so heartbeats don't re-handshake each tick.
type HTTPTransport struct {
	baseURL    string
	httpClient *http.Client
	// clientErrorCount accumulates non-auth 4xx errors toward the fail-fast
	// threshold (protocol.ClientErrorFailFastThreshold). Accessed from multiple
	// goroutines (heartbeat / lease / consume loops share one transport), so it
	// is atomic.
	clientErrorCount atomic.Int32
	// sleep is injectable so tests don't pay real backoff time; defaults to
	// time.Sleep (real backoff, per spec).
	sleep func(time.Duration)
	// retryBaseMs / retryAttempts tune 5xx/transport backoff (0 -> protocol defaults).
	retryBaseMs   int
	retryAttempts int
	// apiKey is the worker's API key. When set it is sent as X-Batch-Api-Key on
	// every request and used as the HMAC key when requestSigningEnabled is on.
	apiKey string
	// requestSigningEnabled gates request signing (方案 A, opt-in, default false).
	// When on AND apiKey is set, write requests (POST/PUT/PATCH/DELETE) carry the
	// X-Batch-Timestamp / X-Batch-Nonce / X-Batch-Signature headers. The platform
	// must have batch.request-signing.enabled set to honor them.
	requestSigningEnabled bool
}

// HTTPTransportOption configures an HTTPTransport.
type HTTPTransportOption func(*HTTPTransport)

// WithHTTPClient overrides the default client (e.g. to inject a test transport).
// The caller is responsible for setting a sane Timeout.
func WithHTTPClient(c *http.Client) HTTPTransportOption {
	return func(t *HTTPTransport) { t.httpClient = c }
}

// WithSleep injects the backoff sleeper (tests pass a no-op to skip real waits).
func WithSleep(f func(time.Duration)) HTTPTransportOption {
	return func(t *HTTPTransport) { t.sleep = f }
}

// WithRetryTuning overrides 5xx/transport backoff base + attempts.
func WithRetryTuning(baseMs, attempts int) HTTPTransportOption {
	return func(t *HTTPTransport) { t.retryBaseMs = baseMs; t.retryAttempts = attempts }
}

// WithAPIKey sets the worker's API key. When set it is sent as X-Batch-Api-Key
// on every request and used as the HMAC key for request signing.
func WithAPIKey(apiKey string) HTTPTransportOption {
	return func(t *HTTPTransport) { t.apiKey = apiKey }
}

// WithRequestSigning enables request signing (方案 A, opt-in, default off).
// Signing only takes effect when an API key is also configured. Mirrors the
// Java SDK's requestSigningEnabled flag.
func WithRequestSigning(enabled bool) HTTPTransportOption {
	return func(t *HTTPTransport) { t.requestSigningEnabled = enabled }
}

// NewHTTPTransport builds an HTTPTransport for the given orchestrator base URL.
//
// Env defaults (mirroring the Java SDK's BATCH_SDK_ prefix) are applied first,
// then overridden by any explicit options:
//   - BATCH_SDK_API_KEY              -> WithAPIKey
//   - BATCH_SDK_REQUEST_SIGNING_ENABLED -> WithRequestSigning (false / 0 / no / off => off)
func NewHTTPTransport(baseURL string, opts ...HTTPTransportOption) *HTTPTransport {
	t := &HTTPTransport{
		baseURL: baseURL,
		httpClient: &http.Client{
			// §4 Go pit: NEVER leave the client with no timeout.
			Timeout: 10 * time.Second,
			Transport: &http.Transport{
				MaxIdleConns:        32,
				MaxIdleConnsPerHost: 16,
				IdleConnTimeout:     90 * time.Second,
				// keep-alive enabled (DisableKeepAlives defaults false).
			},
		},
		sleep: time.Sleep,
	}
	if v := os.Getenv("BATCH_SDK_API_KEY"); v != "" {
		t.apiKey = v
	}
	if v := strings.TrimSpace(os.Getenv("BATCH_SDK_REQUEST_SIGNING_ENABLED")); v != "" {
		t.requestSigningEnabled = parseSdkBool(v)
	}
	for _, o := range opts {
		o(t)
	}
	return t
}

// parseSdkBool mirrors the Java SDK's parseBoolean: any value other than the
// explicit off tokens (false / 0 / no / off, case-insensitive) is true.
func parseSdkBool(raw string) bool {
	switch strings.ToLower(raw) {
	case "false", "0", "no", "off":
		return false
	default:
		return true
	}
}

// doJSON performs one request attempt: marshal body, set headers, return the
// status + raw response bytes. Transport errors return status 0.
func (t *HTTPTransport) doJSON(ctx context.Context, method, path string, body any, idempotencyKey string) (int, []byte, error) {
	var reader io.Reader
	var payload []byte
	if body != nil {
		buf, err := json.Marshal(body)
		if err != nil {
			return 0, nil, fmt.Errorf("marshal %s: %w", path, err)
		}
		payload = buf
		reader = bytes.NewReader(buf)
	}
	req, err := http.NewRequestWithContext(ctx, method, t.baseURL+path, reader)
	if err != nil {
		return 0, nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	if idempotencyKey != "" {
		req.Header.Set("Idempotency-Key", idempotencyKey)
	}
	if t.apiKey != "" {
		req.Header.Set("X-Batch-Api-Key", t.apiKey)
		// 请求签名(方案 A, opt-in):仅对写请求(POST/PUT/PATCH/DELETE)且开关开启
		// 且有 api_key 时附加。timestamp/nonce/signature 与服务端逐字节一致。
		if t.requestSigningEnabled && isWriteMethod(method) {
			timestamp := strconv.FormatInt(time.Now().UnixMilli(), 10)
			nonce := newNonce()
			sig := signRequest(t.apiKey, method, path, timestamp, nonce, payload)
			req.Header.Set(HeaderSignatureTimestamp, timestamp)
			req.Header.Set(HeaderSignatureNonce, nonce)
			req.Header.Set(HeaderSignature, sig)
		}
	}
	resp, err := t.httpClient.Do(req)
	if err != nil {
		return 0, nil, err // transport error -> status 0
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, data, nil
}

// call drives doJSON through protocol.ClassifyHTTP, applying real-time backoff
// for retryable (5xx/transport) classes and mapping terminal classes to typed
// errors. On 2xx it returns the response bytes; on 409 it returns
// (body, idempotent=true, nil) so callers can treat it as success.
func (t *HTTPTransport) call(ctx context.Context, method, path string, body any, idempotencyKey, op string, retryable bool) (data []byte, idempotent bool, err error) {
	baseMs, attempts := t.retryBaseMs, t.retryAttempts
	if attempts <= 0 {
		attempts = protocol.DefaultRetryMaxAttempts
	}
	backoff := protocol.ExponentialBackoff(firstPositive(baseMs, protocol.DefaultRetryBaseMs), attempts)
	// wire-protocol §C: heartbeat / renew are periodic ticks — a single failure
	// waits for the next tick, NO internal backoff (fixture 25). retryable=false
	// caps the loop at a single attempt without changing ClassifyHTTP semantics.
	maxRetries := attempts
	if !retryable {
		maxRetries = 0
	}

	var lastErr error
	// attempt 0 is the initial try; 1..maxRetries are retries with backoff[i-1].
	for attempt := 0; attempt <= maxRetries; attempt++ {
		if attempt > 0 {
			// honor context cancellation between retries.
			if ctx.Err() != nil {
				return nil, false, ctx.Err()
			}
			t.sleep(time.Duration(backoff[attempt-1]) * time.Millisecond)
		}
		status, raw, derr := t.doJSON(ctx, method, path, body, idempotencyKey)
		if derr != nil {
			// context cancellation is not a retryable transport error.
			if ctx.Err() != nil {
				return nil, false, derr
			}
			lastErr = derr
			status = 0 // transport error -> classify as retryable
		}

		decision := protocol.ClassifyHTTP(status, int(t.clientErrorCount.Load()), baseMs, attempts)
		switch decision.Action {
		case "success":
			return raw, false, nil
		case "idempotent-success":
			return raw, true, nil
		case "fail-fast":
			if status == 401 || status == 403 {
				return nil, false, &FatalError{Status: status, Op: op}
			}
			// 4xx fail-fast threshold reached.
			t.clientErrorCount.Add(1)
			return nil, false, &FatalError{Status: status, Op: op}
		case "not-found":
			return nil, false, &NotFoundError{Op: op}
		case "client-error":
			t.clientErrorCount.Add(1)
			return nil, false, &ClientError{Status: status, Op: op}
		case "retry-then-drop":
			if status >= 500 {
				lastErr = fmt.Errorf("http %d", status)
			}
			// loop continues to next attempt (or exits below).
			continue
		default:
			return nil, false, fmt.Errorf("unexpected decision %q on %s", decision.Action, op)
		}
	}
	return nil, false, &RetryExhaustedError{Op: op, Last: lastErr}
}

func firstPositive(a, b int) int {
	if a > 0 {
		return a
	}
	return b
}

// ---------------------------------------------------------------------------
// Transport method impls
// ---------------------------------------------------------------------------

// Register posts the worker fingerprint. 200 -> fresh; 409 -> idempotent reuse.
func (t *HTTPTransport) Register(ctx context.Context, req RegisterRequest) (RegisterResult, error) {
	_, idem, err := t.call(ctx, http.MethodPost, "/internal/workers/register", req, "", "register", true)
	if err != nil {
		return RegisterResult{}, err
	}
	return RegisterResult{Idempotent: idem}, nil
}

// Heartbeat posts liveness and decodes the reverse-directive response.
func (t *HTTPTransport) Heartbeat(ctx context.Context, workerCode string, req HeartbeatRequest) (protocol.HeartbeatResponse, error) {
	var out protocol.HeartbeatResponse
	raw, _, err := t.call(ctx, http.MethodPost, "/internal/workers/"+workerCode+"/heartbeat", req, "", "heartbeat", false)
	if err != nil {
		return out, err
	}
	if len(raw) > 0 {
		if jerr := json.Unmarshal(raw, &out); jerr != nil {
			return out, fmt.Errorf("decode heartbeat response: %w", jerr)
		}
	}
	return out, nil
}

// Deactivate sends the graceful-goodbye.
func (t *HTTPTransport) Deactivate(ctx context.Context, workerCode string) error {
	_, _, err := t.call(ctx, http.MethodPost, "/internal/workers/"+workerCode+"/deactivate", nil, "", "deactivate", true)
	return err
}

// Claim claims a dispatched task. Idempotency-Key dedups retries; 409 -> already
// claimed/reclaimed (idempotent).
func (t *HTTPTransport) Claim(ctx context.Context, taskID, idempotencyKey string, req ClaimRequest) (ClaimResult, error) {
	var out ClaimResult
	raw, idem, err := t.call(ctx, http.MethodPost, "/internal/tasks/"+taskID+"/claim", req, idempotencyKey, "claim", true)
	if err != nil {
		return out, err
	}
	if len(raw) > 0 {
		if jerr := json.Unmarshal(raw, &out); jerr != nil {
			return out, fmt.Errorf("decode claim response: %w", jerr)
		}
	}
	out.Idempotent = idem
	return out, nil
}

// Report posts the terminal TaskExecutionReportDto. Idempotency-Key dedups
// retries.
func (t *HTTPTransport) Report(ctx context.Context, taskID, idempotencyKey string, req ReportRequest) error {
	_, _, err := t.call(ctx, http.MethodPost, "/internal/tasks/"+taskID+"/report", req, idempotencyKey, "report", true)
	return err
}

// Renew renews a task lease and decodes cancelRequested. A 409 (lease reclaimed
// / zombie claim) surfaces as RenewResult.Revoked=true with no error so the
// caller stops the handler and abandons the report (openapi renew 409).
func (t *HTTPTransport) Renew(ctx context.Context, taskID string, req RenewRequest) (RenewResult, error) {
	var out RenewResult
	raw, revoked, err := t.call(ctx, http.MethodPost, "/internal/tasks/"+taskID+"/renew", req, "", "renew", false)
	if err != nil {
		return out, err
	}
	out.Revoked = revoked
	if len(raw) > 0 {
		if jerr := json.Unmarshal(raw, &out.RenewResponse); jerr != nil {
			return out, fmt.Errorf("decode renew response: %w", jerr)
		}
	}
	return out, nil
}

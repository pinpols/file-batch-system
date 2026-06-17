package protocol

import (
	"crypto/rand"
	"fmt"
)

// Outgoing request construction (request-side conformance).
//
// These builders model the OUTGOING request body + headers the SDK would send
// for register / claim / renew / report. They mirror the Java wire DTOs
// (RegisterRequest / ClaimRequest / RenewRequest / ReportRequest) and the
// PlatformHttpClient header policy. The conformance runner drives them from a
// fixture's given.config + given.state.request and asserts requestBodyIncludes
// / requestBodyExcludes / requestHeaders. This is what locks the report
// field-name red-line (outputs/success:bool, not output/errorClass/status) and
// the partitionInvocationId pass-through across every language.

// RequestBuildConfig is the boot-config subset the request builders read.
type RequestBuildConfig struct {
	TenantID   string
	WorkerCode string
	APIKey     string
}

// ReportPayload is the report-specific body carried in given.state.request.report.
type ReportPayload struct {
	Success       *bool          `json:"success"`
	Outputs       map[string]any `json:"outputs"`
	ErrorCode     string         `json:"errorCode"`
	ResultSummary string         `json:"resultSummary"`
	FailureClass  string         `json:"failureClass"`
}

// RequestSpec describes which outgoing call to build (from given.state.request).
type RequestSpec struct {
	Kind                  string         `json:"kind"`
	TaskID                *int64         `json:"taskId"`
	PartitionInvocationID string         `json:"partitionInvocationId"`
	IdempotencyKey        string         `json:"idempotencyKey"`
	Report                *ReportPayload `json:"report"`
}

// OutgoingRequest is the built request body + headers.
type OutgoingRequest struct {
	Body    map[string]any
	Headers map[string]string
}

func baseHeaders(cfg RequestBuildConfig) map[string]string {
	h := map[string]string{
		"Content-Type":      "application/json",
		"X-Batch-Tenant-Id": cfg.TenantID,
	}
	if cfg.APIKey != "" {
		h["X-Batch-Api-Key"] = cfg.APIKey
	}
	return h
}

// BuildRequest builds the outgoing request (body + headers) for a
// register/claim/renew/report call. Field names and NON_NULL omission mirror
// the platform wire DTOs; apiKey lives only in the header, never the body.
func BuildRequest(spec RequestSpec, cfg RequestBuildConfig) (OutgoingRequest, error) {
	headers := baseHeaders(cfg)
	switch spec.Kind {
	case "register":
		body := map[string]any{
			"tenantId":    cfg.TenantID,
			"workerCode":  cfg.WorkerCode,
			"workerGroup": "sdk-self-hosted",
			"status":      "RUNNING",
		}
		return OutgoingRequest{Body: body, Headers: headers}, nil

	case "claim", "renew":
		body := map[string]any{
			"tenantId": cfg.TenantID,
			"workerId": cfg.WorkerCode,
		}
		if spec.PartitionInvocationID != "" {
			body["partitionInvocationId"] = spec.PartitionInvocationID
		}
		headers["Idempotency-Key"] = idempotencyKey(spec.IdempotencyKey)
		return OutgoingRequest{Body: body, Headers: headers}, nil

	case "report":
		body := map[string]any{
			"tenantId": cfg.TenantID,
			"workerId": cfg.WorkerCode,
		}
		if spec.TaskID != nil {
			body["taskId"] = *spec.TaskID
		}
		if spec.PartitionInvocationID != "" {
			body["partitionInvocationId"] = spec.PartitionInvocationID
		}
		if r := spec.Report; r != nil {
			if r.Success != nil {
				body["success"] = *r.Success
			}
			if r.Outputs != nil {
				body["outputs"] = r.Outputs
			}
			if r.ErrorCode != "" {
				body["errorCode"] = r.ErrorCode
			}
			if r.ResultSummary != "" {
				body["resultSummary"] = r.ResultSummary
			}
			if r.FailureClass != "" {
				body["failureClass"] = r.FailureClass
			}
		}
		headers["Idempotency-Key"] = idempotencyKey(spec.IdempotencyKey)
		return OutgoingRequest{Body: body, Headers: headers}, nil

	default:
		return OutgoingRequest{}, fmt.Errorf("unknown request kind: %q", spec.Kind)
	}
}

func idempotencyKey(provided string) string {
	if provided != "" {
		return provided
	}
	return "go-" + randomUUID()
}

// NewIdempotencyKey mints a fresh `go-<uuid>` Idempotency-Key. Each distinct
// write (claim, report) MUST use a NEW key so a retried/redelivered call does
// not replay a stale outcome from the platform's idempotency store (fixture 24:
// never a fixed report-{taskId}, never reuse across distinct calls). 5xx retries
// of the SAME call reuse the key (handled inside the transport).
func NewIdempotencyKey() string {
	return "go-" + randomUUID()
}

// randomUUID returns an RFC-4122-ish v4 uuid (no deps); only the shape matters.
func randomUUID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

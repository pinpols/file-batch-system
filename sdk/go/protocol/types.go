package protocol

// Wire-protocol types for the orchestrator <-> SDK control plane.
// Mirrors docs/sdk/wire-protocol.md §2 (reverse directives) and §B (error codes).

// HeartbeatResponse is the heartbeat reverse-directive response body
// (wire-protocol §2.1). Pointer fields distinguish "absent/null" from a zero
// value (e.g. shouldDrain=false vs shouldDrain absent).
type HeartbeatResponse struct {
	PlatformStatus       *string  `json:"platformStatus"`
	ShouldDrain          *bool    `json:"shouldDrain"`
	DesiredMaxConcurrent *int     `json:"desiredMaxConcurrent"`
	PausedTaskTypes      []string `json:"pausedTaskTypes"`
	// NextHeartbeatHint is an ISO-8601 duration string ("PT15S") or a raw seconds
	// number, or null. Decoded as a generic value and normalized in decide.go.
	NextHeartbeatHint any `json:"nextHeartbeatHint"`
}

// RenewResponse is the lease-renew response body (wire-protocol §2.2).
type RenewResponse struct {
	LeaseUntil      *string `json:"leaseUntil"`
	CancelRequested *bool   `json:"cancelRequested"`
}

// ErrorCode — canonical platform error codes (wire-protocol §B report errorCode).
type ErrorCode string

const (
	ErrorCodeSuccess           ErrorCode = "SUCCESS"
	ErrorCodeTimeout           ErrorCode = "TIMEOUT"
	ErrorCodeCancelled         ErrorCode = "CANCELLED"
	ErrorCodeKilled            ErrorCode = "KILLED"
	ErrorCodeSecurityRejected  ErrorCode = "SECURITY_REJECTED"
	ErrorCodeExecutionFailed   ErrorCode = "EXECUTION_FAILED"
	ErrorCodeConfigInvalid     ErrorCode = "CONFIG_INVALID"
	ErrorCodeResourceExhausted ErrorCode = "RESOURCE_EXHAUSTED"
)

// FailureClass — ADR-012 failure classes.
type FailureClass string

const (
	FailureClassTransient      FailureClass = "TRANSIENT"
	FailureClassTerminalUser   FailureClass = "TERMINAL_USER"
	FailureClassTerminalConfig FailureClass = "TERMINAL_CONFIG"
	FailureClassBusiness       FailureClass = "BUSINESS"
)

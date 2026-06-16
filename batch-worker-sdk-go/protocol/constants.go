// Package protocol holds the pure decision core for the BYO worker SDK plus the
// cross-language shared constants and wire-protocol types.
//
// These constant slices are mirrored from docs/api/sdk-shared-constants.yaml and
// are NOT re-authored freely: constants_parity_test.go deep-equals each slice
// against the YAML source of truth and fails on any drift (contract §1.1).
package protocol

// SupportedSchemaVersions — schema_versions_supported: known major versions the
// SDK accepts (wire-protocol §A).
var SupportedSchemaVersions = []string{"v1", "v2"}

// WorkerRuntimeStates — worker_runtime_states: worker FSM states.
var WorkerRuntimeStates = []string{
	"NORMAL",
	"DEGRADED",
	"PAUSED",
	"DRAINING",
}

// SensitiveKeywords — sensitive_keywords: credential-leak detection keywords.
var SensitiveKeywords = []string{
	"password",
	"passwd",
	"secret",
	"apikey",
	"api_key",
	"token",
	"credential",
	"accesskey",
	"access_key",
	"privatekey",
	"private_key",
	"clientsecret",
	"client_secret",
}

// TaskStatuses — task_statuses: terminal + lifecycle task statuses.
var TaskStatuses = []string{
	"CREATED",
	"READY",
	"RUNNING",
	"SUCCESS",
	"FAILED",
	"CANCELLED",
	"TERMINATED",
}

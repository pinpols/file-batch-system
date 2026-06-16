package client

import (
	"testing"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/protocol"
)

// Register body with a non-empty password -> error (fail-fast).
func TestSensitive_RegisterRejectsCredential(t *testing.T) {
	v := NewSensitiveValidator()
	err := v.ValidateRegister(RegisterRequest{
		Attributes: map[string]any{"password": "hunter2"},
	})
	if err == nil {
		t.Fatalf("expected register to reject non-empty password")
	}
}

// Empty sensitive value is allowed (credential goes via env).
func TestSensitive_EmptyValueAllowed(t *testing.T) {
	v := NewSensitiveValidator()
	if err := v.ValidateRegister(RegisterRequest{
		Attributes: map[string]any{"apiKey": "", "region": "cn"},
	}); err != nil {
		t.Fatalf("empty sensitive value should be allowed, got %v", err)
	}
}

// Dispatch parameters with a token -> SECURITY_REJECTED TaskResult.
func TestSensitive_ParametersSecurityRejected(t *testing.T) {
	v := NewSensitiveValidator()
	res, rejected := v.ValidateParameters(map[string]any{"access_token": "abc"})
	if !rejected {
		t.Fatalf("expected parameters to be rejected")
	}
	if res.ErrorCode != protocol.ErrorCodeSecurityRejected {
		t.Fatalf("expected SECURITY_REJECTED, got %s", res.ErrorCode)
	}
}

// Nested map credential is caught.
func TestSensitive_NestedCredential(t *testing.T) {
	v := NewSensitiveValidator()
	_, rejected := v.ValidateParameters(map[string]any{
		"db": map[string]any{"clientSecret": "s3cr3t"},
	})
	if !rejected {
		t.Fatalf("expected nested clientSecret to be rejected")
	}
}

// Deny-list extension hook catches a custom keyword.
func TestSensitive_DenyListExtension(t *testing.T) {
	v := NewSensitiveValidator("vaultpath")
	_, rejected := v.ValidateParameters(map[string]any{"vaultPath": "/secret/x"})
	if !rejected {
		t.Fatalf("expected custom deny-list keyword to be rejected")
	}
}

// Non-sensitive keys pass.
func TestSensitive_CleanParamsPass(t *testing.T) {
	v := NewSensitiveValidator()
	if _, rejected := v.ValidateParameters(map[string]any{"batchSize": 100, "name": "x"}); rejected {
		t.Fatalf("clean params should not be rejected")
	}
}

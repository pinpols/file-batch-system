package client

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"reflect"
	"regexp"
	"testing"
)

func TestExecutionStartDetailsSurviveRealHTTPTransport(t *testing.T) {
	requests := make(chan map[string]any, 1)
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodPost || request.URL.Path != "/internal/tasks/42/renew" {
			t.Errorf("unexpected request %s %s", request.Method, request.URL.Path)
		}
		var body map[string]any
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
			t.Error(err)
		}
		requests <- body
		writer.Header().Set("Content-Type", "application/json")
		_, _ = writer.Write([]byte(`{"cancelRequested":true}`))
	}))
	defer server.Close()
	transport := NewHTTPTransport(server.URL, WithTenantID("t1"))
	request := RenewRequest{TenantID: "t1", WorkerID: "w1", PartitionInvocationID: "inv-current", Details: map[string]any{"executionStarted": true, "executionAttemptId": "attempt-1"}}
	result, err := transport.Renew(context.Background(), "42", request)
	if err != nil {
		t.Fatal(err)
	}
	if result.CancelRequested == nil || !*result.CancelRequested {
		t.Fatal("丢失取消指令")
	}
	body := <-requests
	expected := map[string]any{"tenantId": "t1", "workerId": "w1", "partitionInvocationId": "inv-current", "details": map[string]any{"executionStarted": true, "executionAttemptId": "attempt-1"}}
	if !reflect.DeepEqual(body, expected) {
		t.Fatalf("HTTP 开始载荷=%#v", body)
	}
}

// 入口测试在真实 dispatch 协程中核对确认身份和业务执行顺序，不依赖定时续租触发。
type executionStartPlatform struct {
	*FakePlatform
	scenario string
	request  map[string]any
}

func (p *executionStartPlatform) Renew(_ context.Context, _ string, request RenewRequest) (RenewResult, error) {
	encoded, err := json.Marshal(request)
	if err != nil {
		return RenewResult{}, err
	}
	if err = json.Unmarshal(encoded, &p.request); err != nil {
		return RenewResult{}, err
	}
	if p.scenario == "unknown" {
		return RenewResult{}, errors.New("injected renew failure")
	}
	result := RenewResult{Revoked: p.scenario == "revoked"}
	cancelled := p.scenario == "cancelled"
	result.CancelRequested = &cancelled
	return result, nil
}

func TestExecutionStartConfirmsCurrentOwnerBeforeBusiness(t *testing.T) {
	for _, scenario := range []string{"confirmed", "revoked", "unknown", "cancelled"} {
		t.Run(scenario, func(t *testing.T) {
			platform := &executionStartPlatform{FakePlatform: NewFakePlatform(), scenario: scenario}
			platform.ClaimResp = ClaimResult{PartitionInvocationID: "inv-current"}
			executions := 0
			handler := HandlerFunc(func(_ *TaskContext) TaskResult {
				if platform.request == nil {
					t.Error("业务执行前未确认实际执行入口")
				}
				executions++
				return Success(nil, "ok")
			})
			worker := NewWorker(testConfig(), platform, NewFakeConsumer(nil), handler, nil, quietLogger())
			message := TaskDispatchMessage{TaskID: "task-1", TenantID: "t1", RuntimeAttributes: map[string]any{"partitionInvocationId": "inv-stale"}}
			worker.dispatch(context.Background(), message)
			worker.taskWg.Wait()
			details, ok := platform.request["details"].(map[string]any)
			if !ok {
				t.Fatalf("缺少执行入口详情: %#v", platform.request)
			}
			attemptID, ok := details["executionAttemptId"].(string)
			if !ok || !regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`).MatchString(attemptID) {
				t.Fatalf("无效执行尝试标识: %#v", details["executionAttemptId"])
			}
			delete(details, "executionAttemptId")
			expected := map[string]any{"tenantId": "t1", "workerId": "w1", "partitionInvocationId": "inv-current", "details": map[string]any{"executionStarted": true}}
			if !reflect.DeepEqual(platform.request, expected) {
				t.Fatalf("开始确认载荷 = %#v", platform.request)
			}
			wantExecutions, wantReports := 0, 0
			if scenario == "confirmed" {
				wantExecutions, wantReports = 1, 1
			}
			if scenario == "cancelled" {
				wantReports = 1
			}
			if executions != wantExecutions || len(platform.ReportCalls) != wantReports {
				t.Fatalf("executions=%d reports=%d", executions, len(platform.ReportCalls))
			}
			if scenario == "cancelled" && platform.ReportCalls[0].Result.ErrorCode != "CANCELLED" {
				t.Fatal("未保留取消结果")
			}
		})
	}
}

func TestRedeliveredTaskReceivesNewExecutionAttemptID(t *testing.T) {
	platform := &executionStartPlatform{FakePlatform: NewFakePlatform(), scenario: "confirmed"}
	platform.ClaimResp = ClaimResult{PartitionInvocationID: "inv-current"}
	worker := NewWorker(testConfig(), platform, NewFakeConsumer(nil),
		HandlerFunc(func(*TaskContext) TaskResult { return Success(nil, "ok") }), nil, quietLogger())
	message := TaskDispatchMessage{TaskID: "task-1", TenantID: "t1"}
	worker.dispatch(context.Background(), message)
	worker.taskWg.Wait()
	first := platform.request["details"].(map[string]any)["executionAttemptId"].(string)
	worker.dispatch(context.Background(), message)
	worker.taskWg.Wait()
	second := platform.request["details"].(map[string]any)["executionAttemptId"].(string)
	if first == second {
		t.Fatal("重投业务入口不能复用上一次执行尝试标识")
	}
}

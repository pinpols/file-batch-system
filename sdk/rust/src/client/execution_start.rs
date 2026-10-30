//! 实际执行入口确认；自定义执行桥在调用业务处理器前使用，不以认领或排队代替实际开始。

use super::handler::TaskContext;
use super::transport::Transport;
use std::fmt;

/// 执行入口确认结果；取消结果由执行桥按既有取消回报路径处理。
#[derive(Debug, PartialEq, Eq)]
pub enum ExecutionStartOutcome<R> {
    Executed(R),
    Cancelled,
}

/// 控制面未确认开始，不能转换为业务失败，也不能调用业务或伪造执行回报。
#[derive(Debug, PartialEq, Eq)]
pub struct ExecutionStartError {
    pub status: i64,
}

impl fmt::Display for ExecutionStartError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            formatter,
            "execution start was not confirmed (status={})",
            self.status
        )
    }
}

impl std::error::Error for ExecutionStartError {}

/// 先用 CLAIM 当前身份确认计时，再执行闭包；该入口不写非幂等副作用标记。
/// 返回错误时，调用方保留租约恢复，不发送业务失败回报。
pub fn execute_after_start_confirmation<T: Transport, R>(
    transport: &T,
    context: &TaskContext,
    worker_code: &str,
    execute: impl FnOnce() -> R,
) -> Result<ExecutionStartOutcome<R>, ExecutionStartError> {
    if context.is_cancelled() {
        return Ok(ExecutionStartOutcome::Cancelled);
    }
    let body = serde_json::json!({
        "tenantId": context.tenant_id,
        "workerId": worker_code,
        "partitionInvocationId": context.partition_invocation_id,
        "details": { "executionStarted": true }
    });
    let serialized = body.to_string();
    let response = transport.renew(&context.task_id, &serialized);
    if !(200..300).contains(&response.status) {
        return Err(ExecutionStartError {
            status: response.status,
        });
    }
    #[derive(serde::Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct StartResponse {
        cancel_requested: bool,
    }
    let confirmed: StartResponse =
        serde_json::from_str(&response.body).map_err(|_| ExecutionStartError {
            status: response.status,
        })?;
    if confirmed.cancel_requested || context.is_cancelled() {
        context.cancellation.cancel();
        return Ok(ExecutionStartOutcome::Cancelled);
    }
    Ok(ExecutionStartOutcome::Executed(execute()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::client::transport::HttpResponse;
    use std::sync::Mutex;

    struct RecordingTransport {
        response: HttpResponse,
        request: Mutex<Option<String>>,
    }

    impl Transport for RecordingTransport {
        fn register(&self, _: &str, _: &str) -> HttpResponse {
            unreachable!()
        }
        fn heartbeat(&self, _: &str, _: &str) -> HttpResponse {
            unreachable!()
        }
        fn deactivate(&self, _: &str) -> HttpResponse {
            unreachable!()
        }
        fn claim(&self, _: &str, _: &str) -> HttpResponse {
            unreachable!()
        }
        fn report(&self, _: &str, _: &str) -> HttpResponse {
            unreachable!()
        }
        fn renew(&self, _: &str, body: &str) -> HttpResponse {
            let _ = self.request.lock().unwrap().replace(body.to_owned());
            HttpResponse::new(self.response.status, &self.response.body)
        }
    }

    #[test]
    fn confirms_current_identity_before_business_entry() {
        let transport = RecordingTransport {
            response: HttpResponse::new(200, r#"{"cancelRequested":false}"#),
            request: Mutex::new(None),
        };
        let context = TaskContext::new("42", "tenant-\"escaped", "echo")
            .with_partition_invocation_id(Some("inv-current".into()));
        let result = execute_after_start_confirmation(&transport, &context, "w1", || {
            assert!(
                transport.request.lock().unwrap().is_some(),
                "业务前必须确认入口"
            );
            42
        })
        .unwrap();
        assert_eq!(result, ExecutionStartOutcome::Executed(42));
        let raw = transport.request.lock().unwrap().clone().unwrap();
        let request: serde_json::Value = serde_json::from_str(&raw).unwrap();
        assert_eq!(
            request,
            serde_json::json!({"tenantId":"tenant-\"escaped", "workerId":"w1", "partitionInvocationId":"inv-current", "details":{"executionStarted":true}})
        );
    }

    #[test]
    fn rejection_unknown_and_malformed_responses_never_enter_business() {
        for (status, body) in [
            (409, "{}"),
            (503, "{}"),
            (0, ""),
            (200, "not-json"),
            (200, "{}"),
            (200, r#"{"cancelRequested":"false"}"#),
        ] {
            let transport = RecordingTransport {
                response: HttpResponse::new(status, body),
                request: Mutex::new(None),
            };
            let context = TaskContext::new("42", "tenant", "echo");
            let result = execute_after_start_confirmation(&transport, &context, "w1", || {
                panic!("未确认开始不得进入业务")
            });
            assert!(result.is_err(), "状态 {status} 未拒绝未知开始响应");
        }
    }

    #[test]
    fn cancellation_response_prevents_business_and_preserves_signal() {
        let transport = RecordingTransport {
            response: HttpResponse::new(200, r#"{"cancelRequested":true}"#),
            request: Mutex::new(None),
        };
        let context = TaskContext::new("42", "tenant", "echo");
        let result = execute_after_start_confirmation(&transport, &context, "w1", || {
            panic!("取消后不得进入业务")
        })
        .unwrap();
        assert_eq!(result, ExecutionStartOutcome::Cancelled);
        assert!(context.is_cancelled());
    }

    #[test]
    fn existing_cancellation_does_not_write_a_start_that_did_not_happen() {
        let transport = RecordingTransport {
            response: HttpResponse::new(200, "{}"),
            request: Mutex::new(None),
        };
        let context = TaskContext::new("42", "tenant", "echo");
        context.cancellation.cancel();
        let result = execute_after_start_confirmation(&transport, &context, "w1", || {
            panic!("取消后不得进入业务")
        })
        .unwrap();
        assert_eq!(result, ExecutionStartOutcome::Cancelled);
        assert!(transport.request.lock().unwrap().is_none());
    }
}

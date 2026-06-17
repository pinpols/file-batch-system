use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::client::handler::{TaskContext, TaskHandler, TaskResult};
use crate::protocol::error_code;

pub const IDEMPOTENT_IN_FLIGHT: &str = "IDEMPOTENT_IN_FLIGHT";

pub struct RetryPolicy {
    pub max_attempts: usize,
    pub initial_delay: Duration,
    pub backoff_multiplier: f64,
    pub should_retry: fn(&TaskResult) -> bool,
}

impl Default for RetryPolicy {
    fn default() -> Self {
        Self {
            max_attempts: 3,
            initial_delay: Duration::from_millis(100),
            backoff_multiplier: 2.0,
            should_retry: default_should_retry,
        }
    }
}

pub fn default_should_retry(result: &TaskResult) -> bool {
    matches!(
        result.error_code.as_str(),
        error_code::EXECUTION_FAILED | error_code::TIMEOUT | error_code::RESOURCE_EXHAUSTED
    )
}

pub fn with_retry<H: TaskHandler>(inner: H, policy: RetryPolicy) -> RetryHandler<H> {
    RetryHandler { inner, policy }
}

pub struct RetryHandler<H: TaskHandler> {
    inner: H,
    policy: RetryPolicy,
}

impl<H: TaskHandler> TaskHandler for RetryHandler<H> {
    fn task_type(&self) -> &str {
        self.inner.task_type()
    }

    fn execute(&self, ctx: &TaskContext) -> TaskResult {
        let max_attempts = self.policy.max_attempts.max(1);
        let multiplier = if self.policy.backoff_multiplier > 0.0 {
            self.policy.backoff_multiplier
        } else {
            1.0
        };
        let mut delay = self.policy.initial_delay;
        let mut result = TaskResult::failure(error_code::EXECUTION_FAILED, "not executed");

        for attempt in 1..=max_attempts {
            result = self.inner.execute(ctx);
            if result.is_success()
                || attempt == max_attempts
                || !(self.policy.should_retry)(&result)
            {
                return result;
            }
            if !delay.is_zero() {
                std::thread::sleep(delay);
                delay = Duration::from_millis(((delay.as_millis() as f64) * multiplier) as u64);
            }
        }
        result
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IdempotencyEntity {
    pub result: TaskResult,
}

pub trait SdkIdempotencyStore: Send + Sync {
    fn try_acquire(&self, key: &str, ttl: Duration) -> Result<bool, String>;
    fn find(&self, key: &str) -> Result<Option<IdempotencyEntity>, String>;
    fn record(&self, key: &str, entity: IdempotencyEntity, ttl: Duration) -> Result<(), String>;
    fn release(&self, key: &str) -> Result<(), String>;
}

pub fn task_id_idempotency_key(ctx: &TaskContext) -> Result<String, String> {
    if ctx.task_id.is_empty() {
        return Err("taskId is required".to_string());
    }
    Ok(ctx.task_id.clone())
}

pub fn with_idempotency<H, F>(
    inner: H,
    store: Arc<dyn SdkIdempotencyStore>,
    ttl: Duration,
    key_resolver: F,
) -> IdempotencyHandler<H, F>
where
    H: TaskHandler,
    F: Fn(&TaskContext) -> Result<String, String> + Send + Sync,
{
    IdempotencyHandler {
        inner,
        store,
        ttl,
        key_resolver,
    }
}

pub struct IdempotencyHandler<H, F>
where
    H: TaskHandler,
    F: Fn(&TaskContext) -> Result<String, String> + Send + Sync,
{
    inner: H,
    store: Arc<dyn SdkIdempotencyStore>,
    ttl: Duration,
    key_resolver: F,
}

impl<H, F> TaskHandler for IdempotencyHandler<H, F>
where
    H: TaskHandler,
    F: Fn(&TaskContext) -> Result<String, String> + Send + Sync,
{
    fn task_type(&self) -> &str {
        self.inner.task_type()
    }

    fn execute(&self, ctx: &TaskContext) -> TaskResult {
        let key = match (self.key_resolver)(ctx) {
            Ok(key) if !key.is_empty() => key,
            Ok(_) => {
                return TaskResult::failure(error_code::CONFIG_INVALID, "idempotency key is empty")
            }
            Err(e) => return TaskResult::failure(error_code::CONFIG_INVALID, &e),
        };

        let acquired = match self.store.try_acquire(&key, self.ttl) {
            Ok(v) => v,
            Err(e) => {
                return TaskResult::failure(
                    error_code::EXECUTION_FAILED,
                    &format!("idempotency acquire failed: {e}"),
                )
            }
        };

        if !acquired {
            return match self.store.find(&key) {
                Ok(Some(entity)) => entity.result,
                Ok(None) => TaskResult::failure(
                    IDEMPOTENT_IN_FLIGHT,
                    "idempotent execution is already in flight",
                ),
                Err(e) => TaskResult::failure(
                    error_code::EXECUTION_FAILED,
                    &format!("idempotency lookup failed: {e}"),
                ),
            };
        }

        let result = self.inner.execute(ctx);
        if result.is_success() {
            if let Err(e) = self.store.record(
                &key,
                IdempotencyEntity {
                    result: result.clone(),
                },
                self.ttl,
            ) {
                return TaskResult::failure(
                    error_code::EXECUTION_FAILED,
                    &format!("idempotency record failed: {e}"),
                );
            }
            result
        } else {
            let _ = self.store.release(&key);
            result
        }
    }
}

#[derive(Default)]
pub struct NoopIdempotencyStore;

impl SdkIdempotencyStore for NoopIdempotencyStore {
    fn try_acquire(&self, _key: &str, _ttl: Duration) -> Result<bool, String> {
        Ok(true)
    }

    fn find(&self, _key: &str) -> Result<Option<IdempotencyEntity>, String> {
        Ok(None)
    }

    fn record(&self, _key: &str, _entity: IdempotencyEntity, _ttl: Duration) -> Result<(), String> {
        Ok(())
    }

    fn release(&self, _key: &str) -> Result<(), String> {
        Ok(())
    }
}

#[derive(Default)]
pub struct InMemoryIdempotencyStore {
    entries: Mutex<BTreeMap<String, IdempotencyEntry>>,
}

#[derive(Clone)]
struct IdempotencyEntry {
    entity: Option<IdempotencyEntity>,
    expires_at: Option<Instant>,
}

impl InMemoryIdempotencyStore {
    pub fn new() -> Self {
        Self::default()
    }

    fn delete_if_expired_locked(entries: &mut BTreeMap<String, IdempotencyEntry>, key: &str) {
        let expired = entries
            .get(key)
            .and_then(|entry| entry.expires_at)
            .map(|expires_at| Instant::now() >= expires_at)
            .unwrap_or(false);
        if expired {
            entries.remove(key);
        }
    }
}

impl SdkIdempotencyStore for InMemoryIdempotencyStore {
    fn try_acquire(&self, key: &str, ttl: Duration) -> Result<bool, String> {
        let mut entries = self.entries.lock().map_err(|e| e.to_string())?;
        Self::delete_if_expired_locked(&mut entries, key);
        if entries.contains_key(key) {
            return Ok(false);
        }
        entries.insert(
            key.to_string(),
            IdempotencyEntry {
                entity: None,
                expires_at: expires_at(ttl),
            },
        );
        Ok(true)
    }

    fn find(&self, key: &str) -> Result<Option<IdempotencyEntity>, String> {
        let mut entries = self.entries.lock().map_err(|e| e.to_string())?;
        Self::delete_if_expired_locked(&mut entries, key);
        Ok(entries.get(key).and_then(|entry| entry.entity.clone()))
    }

    fn record(&self, key: &str, entity: IdempotencyEntity, ttl: Duration) -> Result<(), String> {
        let mut entries = self.entries.lock().map_err(|e| e.to_string())?;
        entries.insert(
            key.to_string(),
            IdempotencyEntry {
                entity: Some(entity),
                expires_at: expires_at(ttl),
            },
        );
        Ok(())
    }

    fn release(&self, key: &str) -> Result<(), String> {
        let mut entries = self.entries.lock().map_err(|e| e.to_string())?;
        entries.remove(key);
        Ok(())
    }
}

fn expires_at(ttl: Duration) -> Option<Instant> {
    if ttl.is_zero() {
        None
    } else {
        Some(Instant::now() + ttl)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct CountingHandler {
        calls: Arc<AtomicUsize>,
    }

    impl TaskHandler for CountingHandler {
        fn task_type(&self) -> &str {
            "demo"
        }

        fn execute(&self, _ctx: &TaskContext) -> TaskResult {
            let call = self.calls.fetch_add(1, Ordering::SeqCst) + 1;
            if call < 3 {
                TaskResult::failure(error_code::EXECUTION_FAILED, "temporary")
            } else {
                TaskResult::success("ok").with_output("attempts", &call.to_string())
            }
        }
    }

    #[test]
    fn retry_retries_retryable_result() {
        let calls = Arc::new(AtomicUsize::new(0));
        let handler = with_retry(
            CountingHandler {
                calls: Arc::clone(&calls),
            },
            RetryPolicy {
                max_attempts: 3,
                initial_delay: Duration::ZERO,
                ..RetryPolicy::default()
            },
        );

        let result = handler.execute(&TaskContext::new("t1", "tenant", "demo"));

        assert!(result.is_success());
        assert_eq!(calls.load(Ordering::SeqCst), 3);
        assert_eq!(
            result.outputs.get("attempts").map(String::as_str),
            Some("3")
        );
    }

    #[test]
    fn retry_stops_on_non_retryable_result() {
        struct BadConfig;
        impl TaskHandler for BadConfig {
            fn task_type(&self) -> &str {
                "demo"
            }
            fn execute(&self, _ctx: &TaskContext) -> TaskResult {
                TaskResult::failure(error_code::CONFIG_INVALID, "bad config")
            }
        }

        let result = with_retry(
            BadConfig,
            RetryPolicy {
                max_attempts: 3,
                initial_delay: Duration::ZERO,
                ..RetryPolicy::default()
            },
        )
        .execute(&TaskContext::new("t1", "tenant", "demo"));

        assert_eq!(result.error_code, error_code::CONFIG_INVALID);
    }

    #[test]
    fn idempotency_caches_success() {
        struct Once {
            calls: Arc<AtomicUsize>,
        }
        impl TaskHandler for Once {
            fn task_type(&self) -> &str {
                "demo"
            }
            fn execute(&self, _ctx: &TaskContext) -> TaskResult {
                let call = self.calls.fetch_add(1, Ordering::SeqCst) + 1;
                TaskResult::success("done").with_output("calls", &call.to_string())
            }
        }

        let calls = Arc::new(AtomicUsize::new(0));
        let handler = with_idempotency(
            Once {
                calls: Arc::clone(&calls),
            },
            Arc::new(InMemoryIdempotencyStore::new()),
            Duration::from_secs(60),
            task_id_idempotency_key,
        );
        let ctx = TaskContext::new("same", "tenant", "demo");

        let first = handler.execute(&ctx);
        let second = handler.execute(&ctx);

        assert!(first.is_success());
        assert!(second.is_success());
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        assert_eq!(second.outputs.get("calls").map(String::as_str), Some("1"));
    }

    #[test]
    fn idempotency_releases_after_failure() {
        struct FailsOnce {
            calls: Arc<AtomicUsize>,
        }
        impl TaskHandler for FailsOnce {
            fn task_type(&self) -> &str {
                "demo"
            }
            fn execute(&self, _ctx: &TaskContext) -> TaskResult {
                let call = self.calls.fetch_add(1, Ordering::SeqCst) + 1;
                if call == 1 {
                    TaskResult::failure(error_code::EXECUTION_FAILED, "temporary")
                } else {
                    TaskResult::success("ok")
                }
            }
        }

        let calls = Arc::new(AtomicUsize::new(0));
        let handler = with_idempotency(
            FailsOnce {
                calls: Arc::clone(&calls),
            },
            Arc::new(InMemoryIdempotencyStore::new()),
            Duration::from_secs(60),
            task_id_idempotency_key,
        );
        let ctx = TaskContext::new("retry", "tenant", "demo");

        assert!(!handler.execute(&ctx).is_success());
        assert!(handler.execute(&ctx).is_success());
        assert_eq!(calls.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn idempotency_reports_in_flight_without_snapshot() {
        struct Never;
        impl TaskHandler for Never {
            fn task_type(&self) -> &str {
                "demo"
            }
            fn execute(&self, _ctx: &TaskContext) -> TaskResult {
                panic!("handler should not run")
            }
        }

        let store = Arc::new(InMemoryIdempotencyStore::new());
        assert!(store.try_acquire("busy", Duration::from_secs(60)).unwrap());
        let handler = with_idempotency(Never, store, Duration::from_secs(60), |_ctx| {
            Ok("busy".to_string())
        });

        let result = handler.execute(&TaskContext::new("t1", "tenant", "demo"));

        assert_eq!(result.error_code, IDEMPOTENT_IN_FLIGHT);
    }
}

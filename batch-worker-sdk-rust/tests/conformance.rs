//! §1.2 conformance runner: load all 12 contract fixtures, drive the decision
//! core from each fixture's `given`/`when`, and assert that EVERY field present
//! in `then.expect` equals the computed [`Decision`]'s same field.
//!
//! The dispatch picks a decision function from the protocol shape of `when`
//! (channel / path / status / response body) — NOT from `then.expect`. The
//! decision functions contain the real logic; this runner only routes inputs and
//! flattens outputs into the closed `then.expect` vocabulary.

#[path = "support/json.rs"]
mod json;

use std::fs;
use std::path::{Path, PathBuf};

use batch_worker_sdk::decide::{
    apply_heartbeat_directive, apply_renew, classify_http, decide_backpressure, decide_register,
    plan_stop, Decision,
};
use batch_worker_sdk::protocol::{HeartbeatHint, HeartbeatResponse, RenewResponse};
use json::Json;

const EXPECTED_FIXTURE_COUNT: usize = 12;

fn fixtures_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../docs/api/sdk-contract-fixtures")
}

/// Numeric coercion from a `given.config` / `given.state` JSON value.
fn num_from(value: Option<&Json>, default: i64) -> i64 {
    match value.and_then(Json::as_num) {
        Some(n) => n as i64,
        None => default,
    }
}

/// Build a [`HeartbeatResponse`] from a fixture `responseBody` JSON object.
fn heartbeat_response(body: &Json) -> HeartbeatResponse {
    let mut resp = HeartbeatResponse::default();
    if let Some(obj) = body.as_object() {
        resp.platform_status = obj
            .get("platformStatus")
            .and_then(Json::as_str)
            .map(str::to_string);
        resp.should_drain = obj.get("shouldDrain").and_then(Json::as_bool);
        resp.desired_max_concurrent = obj
            .get("desiredMaxConcurrent")
            .and_then(Json::as_num)
            .map(|n| n as i64);
        if let Some(Json::Arr(items)) = obj.get("pausedTaskTypes") {
            resp.paused_task_types = items
                .iter()
                .filter_map(Json::as_str)
                .map(str::to_string)
                .collect();
        }
        resp.next_heartbeat_hint = match obj.get("nextHeartbeatHint") {
            None | Some(Json::Null) => None,
            Some(Json::Str(s)) => Some(HeartbeatHint::Iso(s.clone())),
            Some(Json::Num(n)) => Some(HeartbeatHint::Seconds(*n)),
            Some(_) => None,
        };
    }
    resp
}

/// Build a [`RenewResponse`] from a fixture `responseBody` JSON object.
fn renew_response(body: &Json) -> RenewResponse {
    let mut resp = RenewResponse::default();
    if let Some(obj) = body.as_object() {
        resp.lease_until = obj.get("leaseUntil").and_then(Json::as_str).map(str::to_string);
        resp.cancel_requested = obj.get("cancelRequested").and_then(Json::as_bool);
    }
    resp
}

/// Route a fixture to the appropriate decision function based on the protocol
/// shape of `when`, exactly like the TS / Go runners.
fn compute(fx: &Json) -> Decision {
    let when = fx.get("when").expect("fixture missing `when`");
    let given = fx.get("given");
    let config = given.and_then(|g| g.get("config"));
    let state = given.and_then(|g| g.get("state"));

    let channel = when.get("channel").and_then(Json::as_str).unwrap_or("");

    // ----- Kafka receive -> capacity backpressure -----
    if channel == "kafka" {
        let in_flight = num_from(state.and_then(|s| s.get("inFlight")), 0);
        let max_concurrent =
            num_from(config.and_then(|c| c.get("maxConcurrentTasks")), i64::MAX);
        return decide_backpressure(in_flight, max_concurrent);
    }

    // ----- HTTP -----
    let path = when.get("path").and_then(Json::as_str).unwrap_or("");
    let status = match when.get("responseStatus") {
        Some(Json::Num(n)) => *n as i64,
        _ => 0, // null / absent -> transport-error sentinel
    };
    let response_body = when.get("responseBody").cloned().unwrap_or(Json::Null);

    if path.ends_with("/register") {
        // idempotent reuse signal: platform already had a (tenant, workerCode)
        // record — fixtures encode this via given.state describing prior existence.
        let idempotent = state.is_some();
        decide_register(idempotent)
    } else if path.contains("/heartbeat") {
        apply_heartbeat_directive(&heartbeat_response(&response_body))
    } else if path.contains("/deactivate") {
        // graceful stop. stop timeout: prefer config, else default 30s grace.
        let mut timeout_ms = num_from(config.and_then(|c| c.get("stopTimeoutMs")), 0);
        if timeout_ms == 0 {
            timeout_ms = 30000;
        }
        plan_stop(timeout_ms)
    } else if path.contains("/renew") {
        apply_renew(&renew_response(&response_body))
    } else if path.contains("/claim") || path.contains("/report") {
        let base_ms = num_from(config.and_then(|c| c.get("retryBaseDelayMs")), 0);
        let attempts = num_from(config.and_then(|c| c.get("retryMaxAttempts")), 0);
        classify_http(status, 0, base_ms, attempts)
    } else {
        panic!("no decision route for fixture (path={path})");
    }
}

/// Look up a `then.expect` field on the computed decision, returning its value
/// as a [`Json`] for comparison — or `None` if the decision did not emit it
/// (mirrors the TS/Go "field present in computed?" check).
fn computed_field(d: &Decision, field: &str) -> Option<Json> {
    let bool_j = |b: Option<bool>| b.map(Json::Bool);
    let str_j = |s: &Option<String>| s.as_ref().map(|v| Json::Str(v.clone()));
    let num_j = |n: Option<i64>| n.map(|v| Json::Num(v as f64));
    let arr_str = |v: &Option<Vec<String>>| {
        v.as_ref()
            .map(|items| Json::Arr(items.iter().map(|s| Json::Str(s.clone())).collect()))
    };
    let arr_num = |v: &Option<Vec<i64>>| {
        v.as_ref()
            .map(|items| Json::Arr(items.iter().map(|n| Json::Num(*n as f64)).collect()))
    };

    match field {
        "action" => Some(Json::Str(d.action.clone())),
        "retry" => bool_j(d.retry),
        "failFast" => bool_j(d.fail_fast),
        "retryBackoffMs" => arr_num(&d.retry_backoff_ms),
        "maxAttempts" => num_j(d.max_attempts),
        "fsmTransition" => str_j(&d.fsm_transition),
        "kafka" => str_j(&d.kafka),
        "startSchedulers" => arr_str(&d.start_schedulers),
        "heartbeatNextIntervalMs" => num_j(d.heartbeat_next_interval_ms),
        "cancelRequested" => bool_j(d.cancel_requested),
        "idempotent" => bool_j(d.idempotent),
        "reportFailure" => bool_j(d.report_failure),
        "deactivate" => bool_j(d.deactivate),
        "drainThenDeactivate" => bool_j(d.drain_then_deactivate),
        "resumeWhenDrained" => bool_j(d.resume_when_drained),
        "withinMs" => num_j(d.within_ms),
        _ => None,
    }
}

fn fixture_files() -> Vec<PathBuf> {
    let dir = fixtures_dir();
    let mut files: Vec<PathBuf> = fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("read fixtures dir {}: {e}", dir.display()))
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| {
            p.is_file()
                && p.extension().map(|x| x == "json").unwrap_or(false)
                && p.file_name()
                    .and_then(|n| n.to_str())
                    .map(|n| n.chars().next().map(|c| c.is_ascii_digit()).unwrap_or(false))
                    .unwrap_or(false)
        })
        .collect();
    files.sort();
    files
}

#[test]
fn all_12_contract_fixtures_present() {
    let files = fixture_files();
    assert_eq!(
        files.len(),
        EXPECTED_FIXTURE_COUNT,
        "expected {EXPECTED_FIXTURE_COUNT} fixtures, found {}: {:?}",
        files.len(),
        files
    );
}

#[test]
fn conformance_all_fixtures() {
    let files = fixture_files();
    assert_eq!(files.len(), EXPECTED_FIXTURE_COUNT, "fixture count drifted");

    for path in &files {
        let name = path.file_name().unwrap().to_string_lossy().to_string();
        let text = fs::read_to_string(path).unwrap_or_else(|e| panic!("read {name}: {e}"));
        let fx = json::parse(&text).unwrap_or_else(|e| panic!("parse {name}: {e}"));

        let scenario = fx
            .get("scenario")
            .and_then(Json::as_str)
            .unwrap_or("<unknown>")
            .to_string();

        let expect = fx
            .get("then")
            .and_then(|t| t.get("expect"))
            .and_then(Json::as_object)
            .unwrap_or_else(|| panic!("{name}: missing then.expect object"));
        assert!(!expect.is_empty(), "{name}: then.expect is empty");

        let computed = compute(&fx);

        for (field, expected_value) in expect {
            let got = computed_field(&computed, field).unwrap_or_else(|| {
                panic!(
                    "{name} [{scenario}]: decision core did not produce field '{field}' (computed={computed:?})"
                )
            });
            assert_eq!(
                &got, expected_value,
                "{name} [{scenario}]: field '{field}' mismatch — computed {got:?}, expected {expected_value:?}"
            );
        }
    }
}

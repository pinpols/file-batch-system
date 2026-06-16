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

use std::collections::BTreeMap;

use batch_worker_sdk::decide::{
    apply_heartbeat_directive, apply_renew, build_request, classify_heartbeat_renew_error,
    classify_http, classify_schema_version, decide_backpressure, decide_paused_task_type,
    decide_register, plan_stop, BodyValue, Decision, OutgoingRequest, ReportPayload,
    RequestBuildConfig, RequestSpec,
};
use batch_worker_sdk::protocol::{HeartbeatHint, HeartbeatResponse, RenewResponse};
use json::Json;

const EXPECTED_FIXTURE_COUNT: usize = 29;

/// then.expect keys handled by the request builder, not the decision core.
const REQUEST_SIDE_KEYS: &[&str] = &[
    "requestBodyIncludes",
    "requestBodyExcludes",
    "requestHeaders",
];

fn fixtures_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../docs/api/sdk-contract-fixtures")
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

    // ----- Kafka receive -> pausedTaskTypes drop / capacity backpressure -----
    // (schemaAccept is handled separately by the runner, not compute.)
    if channel == "kafka" {
        if let Some(Json::Arr(paused)) = state.and_then(|s| s.get("pausedTaskTypes")) {
            let paused_types: Vec<String> = paused
                .iter()
                .filter_map(Json::as_str)
                .map(str::to_string)
                .collect();
            let body = when.get("body");
            let task_type = body
                .and_then(|b| b.get("workerType"))
                .or_else(|| body.and_then(|b| b.get("taskType")))
                .and_then(Json::as_str)
                .unwrap_or("");
            return decide_paused_task_type(task_type, &paused_types);
        }
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
        // A non-2xx heartbeat is a §C-exempt single-attempt failure (skip this
        // tick, no internal backoff); a 2xx heartbeat applies the directive.
        if status >= 400 {
            classify_heartbeat_renew_error()
        } else {
            apply_heartbeat_directive(&heartbeat_response(&response_body))
        }
    } else if path.contains("/deactivate") {
        // graceful stop. stop timeout: prefer config, else default 30s grace.
        let mut timeout_ms = num_from(config.and_then(|c| c.get("stopTimeoutMs")), 0);
        if timeout_ms == 0 {
            timeout_ms = 30000;
        }
        plan_stop(timeout_ms)
    } else if path.contains("/renew") {
        // error statuses classify by §B (404 give-up, 5xx backoff, ...);
        // a 2xx renew applies the cancel directive from the response body.
        if status >= 400 {
            let client_error_count = num_from(state.and_then(|s| s.get("clientErrorCount")), 0);
            classify_http(status, client_error_count, 0, 0)
        } else {
            apply_renew(&renew_response(&response_body))
        }
    } else if path.contains("/claim") || path.contains("/report") {
        let base_ms = num_from(config.and_then(|c| c.get("retryBaseDelayMs")), 0);
        let attempts = num_from(config.and_then(|c| c.get("retryMaxAttempts")), 0);
        let client_error_count = num_from(state.and_then(|s| s.get("clientErrorCount")), 0);
        classify_http(status, client_error_count, base_ms, attempts)
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
        "effectiveMaxConcurrent" => num_j(d.effective_max_concurrent),
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

/// Convert a builder [`BodyValue`] into a [`Json`] for comparison against the
/// fixture's expected values.
fn body_value_to_json(v: &BodyValue) -> Json {
    match v {
        BodyValue::Bool(b) => Json::Bool(*b),
        BodyValue::Num(n) => Json::Num(*n),
        BodyValue::Str(s) => Json::Str(s.clone()),
        BodyValue::Obj(o) => {
            let mut m = BTreeMap::new();
            for (k, val) in o {
                m.insert(k.clone(), body_value_to_json(val));
            }
            Json::Obj(m)
        }
    }
}

/// Parse a fixture's `given.state.request` block into a typed [`RequestSpec`].
fn request_spec_from(state: Option<&Json>) -> RequestSpec {
    let req = state
        .and_then(|s| s.get("request"))
        .expect("request-side fixture missing given.state.request");
    let kind = req.get("kind").and_then(Json::as_str).unwrap_or("");
    let inv = req
        .get("partitionInvocationId")
        .and_then(Json::as_str)
        .map(str::to_string);
    let idk = req
        .get("idempotencyKey")
        .and_then(Json::as_str)
        .map(str::to_string);
    let task_id = req.get("taskId").and_then(Json::as_num).map(|n| n as i64);

    match kind {
        "register" => RequestSpec::Register,
        "claim" | "renew" => RequestSpec::ClaimOrRenew {
            partition_invocation_id: inv,
            idempotency_key: idk,
        },
        "report" => {
            let mut payload = ReportPayload::default();
            if let Some(r) = req.get("report") {
                payload.success = r.get("success").and_then(Json::as_bool);
                payload.error_code = r.get("errorCode").and_then(Json::as_str).map(str::to_string);
                payload.result_summary = r
                    .get("resultSummary")
                    .and_then(Json::as_str)
                    .map(str::to_string);
                payload.failure_class = r
                    .get("failureClass")
                    .and_then(Json::as_str)
                    .map(str::to_string);
                if let Some(Json::Obj(o)) = r.get("outputs") {
                    let mut outs = BTreeMap::new();
                    for (k, val) in o {
                        outs.insert(k.clone(), json_to_body_value(val));
                    }
                    payload.outputs = Some(outs);
                }
            }
            RequestSpec::Report {
                task_id,
                partition_invocation_id: inv,
                idempotency_key: idk,
                report: payload,
            }
        }
        other => panic!("unknown request kind: {other}"),
    }
}

/// Convert a fixture [`Json`] value into a builder [`BodyValue`] (for outputs).
fn json_to_body_value(v: &Json) -> BodyValue {
    match v {
        Json::Bool(b) => BodyValue::Bool(*b),
        Json::Num(n) => BodyValue::Num(*n),
        Json::Str(s) => BodyValue::Str(s.clone()),
        Json::Obj(o) => {
            let mut m = BTreeMap::new();
            for (k, val) in o {
                m.insert(k.clone(), json_to_body_value(val));
            }
            BodyValue::Obj(m)
        }
        // arrays/null are not used inside report outputs fixtures; map to string.
        other => BodyValue::Str(format!("{other:?}")),
    }
}

/// Build the outgoing request a fixture's given.state.request describes.
fn compute_request(fx: &Json) -> OutgoingRequest {
    let given = fx.get("given");
    let config = given.and_then(|g| g.get("config"));
    let state = given.and_then(|g| g.get("state"));
    let cfg = RequestBuildConfig {
        tenant_id: config
            .and_then(|c| c.get("tenantId"))
            .and_then(Json::as_str)
            .unwrap_or("")
            .to_string(),
        worker_code: config
            .and_then(|c| c.get("workerCode"))
            .and_then(Json::as_str)
            .unwrap_or("")
            .to_string(),
        api_key: config
            .and_then(|c| c.get("apiKey"))
            .and_then(Json::as_str)
            .map(str::to_string),
    };
    build_request(&request_spec_from(state), &cfg)
}

/// Minimal anchored regex matcher for the header patterns the fixtures use:
/// `^...$` with literal chars and a few classes (`[a-z]`, `[0-9a-f]`, `[0-9a-f-]`)
/// plus `+` and `{n,}` / `{n}` quantifiers. Kept std-only (no regex crate).
fn regex_matches(pattern: &str, text: &str) -> bool {
    let pat = pattern
        .strip_prefix('^')
        .and_then(|p| p.strip_suffix('$'))
        .unwrap_or(pattern);
    let tokens = compile_tokens(pat);
    match_tokens(&tokens, &text.chars().collect::<Vec<_>>(), 0, 0)
}

enum Tok {
    Class(Box<dyn Fn(char) -> bool>),
}

fn class_of(spec: &str) -> Box<dyn Fn(char) -> bool> {
    match spec {
        "a-z" => Box::new(|c: char| c.is_ascii_lowercase()),
        // lowercase + hyphen: the minted-key prefix may carry multiple segments
        // (e.g. Python `sdk-py-`), so the leading run tolerates `-`.
        "a-z-" => Box::new(|c: char| c.is_ascii_lowercase() || c == '-'),
        "0-9a-f" => Box::new(|c: char| c.is_ascii_digit() || ('a'..='f').contains(&c)),
        "0-9a-f-" => {
            Box::new(|c: char| c.is_ascii_digit() || ('a'..='f').contains(&c) || c == '-')
        }
        other => {
            let owned = other.to_string();
            Box::new(move |c: char| owned.contains(c))
        }
    }
}

/// (token, min, max) where max=usize::MAX means unbounded.
fn compile_tokens(pat: &str) -> Vec<(Tok, usize, usize)> {
    let chars: Vec<char> = pat.chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        let cls: Box<dyn Fn(char) -> bool> = if chars[i] == '[' {
            let end = chars[i..].iter().position(|&c| c == ']').unwrap() + i;
            let spec: String = chars[i + 1..end].iter().collect();
            i = end + 1;
            class_of(&spec)
        } else {
            let lit = chars[i];
            i += 1;
            Box::new(move |c: char| c == lit)
        };
        // quantifier
        let (min, max) = if i < chars.len() && chars[i] == '+' {
            i += 1;
            (1, usize::MAX)
        } else if i < chars.len() && chars[i] == '{' {
            let end = chars[i..].iter().position(|&c| c == '}').unwrap() + i;
            let body: String = chars[i + 1..end].iter().collect();
            i = end + 1;
            if let Some((lo, hi)) = body.split_once(',') {
                let lo: usize = lo.parse().unwrap_or(0);
                let hi = if hi.is_empty() {
                    usize::MAX
                } else {
                    hi.parse().unwrap_or(usize::MAX)
                };
                (lo, hi)
            } else {
                let n: usize = body.parse().unwrap_or(1);
                (n, n)
            }
        } else {
            (1, 1)
        };
        out.push((Tok::Class(cls), min, max));
    }
    out
}

fn match_tokens(toks: &[(Tok, usize, usize)], text: &[char], ti: usize, xi: usize) -> bool {
    if ti == toks.len() {
        return xi == text.len();
    }
    let (Tok::Class(pred), min, max) = &toks[ti];
    // Greedy consume up to `max` matching chars, then backtrack down to `min`.
    let mut max_take = 0usize;
    while max_take < *max && xi + max_take < text.len() && pred(text[xi + max_take]) {
        max_take += 1;
    }
    if max_take < *min {
        return false;
    }
    let mut take = max_take;
    loop {
        if match_tokens(toks, text, ti + 1, xi + take) {
            return true;
        }
        if take == *min {
            return false;
        }
        take -= 1;
    }
}

/// Assert the request-side expectations on a fixture (body includes/excludes,
/// header regexes).
fn assert_request_side(fx: &Json, name: &str) {
    let req = compute_request(fx);
    let expect = fx
        .get("then")
        .and_then(|t| t.get("expect"))
        .and_then(Json::as_object)
        .unwrap();

    if let Some(Json::Obj(inc)) = expect.get("requestBodyIncludes") {
        for (k, v) in inc {
            let got = req
                .body
                .get(k)
                .unwrap_or_else(|| panic!("{name}: outgoing body missing key '{k}'"));
            let got_json = body_value_to_json(got);
            assert_eq!(
                &got_json, v,
                "{name}: body['{k}'] mismatch — got {got_json:?}, want {v:?}"
            );
        }
    }
    if let Some(Json::Arr(exc)) = expect.get("requestBodyExcludes") {
        for key in exc {
            if let Some(k) = key.as_str() {
                assert!(
                    !req.body.contains_key(k),
                    "{name}: outgoing body must NOT contain key '{k}' (body={:?})",
                    req.body
                );
            }
        }
    }
    if let Some(Json::Obj(hdrs)) = expect.get("requestHeaders") {
        for (name_hdr, pat) in hdrs {
            let pattern = pat.as_str().unwrap();
            let value = req
                .headers
                .get(name_hdr)
                .unwrap_or_else(|| panic!("{name}: outgoing headers missing '{name_hdr}'"));
            assert!(
                regex_matches(pattern, value),
                "{name}: header '{name_hdr}'='{value}' does not match /{pattern}/"
            );
        }
    }
}

/// Assert the §A schemaAccept classification on a kafka message.
fn assert_schema_accept(fx: &Json, name: &str, want: &Json) {
    let version = fx
        .get("when")
        .and_then(|w| w.get("body"))
        .and_then(|b| b.get("schemaVersion"))
        .and_then(Json::as_str);
    let got = classify_schema_version(version) == "accept";
    assert_eq!(
        Json::Bool(got),
        *want,
        "{name}: schemaAccept mismatch (version={version:?})"
    );
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
fn all_contract_fixtures_present() {
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

        // Request-side assertions (body includes/excludes, header regexes) are
        // driven by the request builder, not the response→reaction decision core.
        let has_request_side = expect.keys().any(|k| REQUEST_SIDE_KEYS.contains(&k.as_str()));
        if has_request_side {
            assert_request_side(&fx, &name);
        }

        // schemaAccept (§A) is asserted on the received kafka message.
        if let Some(want) = expect.get("schemaAccept") {
            assert_schema_accept(&fx, &name, want);
        }

        // Remaining response-side fields come from the decision core.
        let decision_fields: Vec<&String> = expect
            .keys()
            .filter(|k| !REQUEST_SIDE_KEYS.contains(&k.as_str()) && k.as_str() != "schemaAccept")
            .collect();
        if decision_fields.is_empty() {
            continue;
        }

        let computed = compute(&fx);
        for field in decision_fields {
            let expected_value = &expect[field];
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

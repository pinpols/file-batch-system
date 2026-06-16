//! Real, blocking control-plane transport backed by [`reqwest`] — behind the
//! optional `http` cargo feature.
//!
//! [`ReqwestTransport`] implements the same [`Transport`] trait as the in-memory
//! [`FakeTransport`](super::transport::FakeTransport) and the (deprecated)
//! [`HttpTransport`](super::transport::HttpTransport) stub, so the synchronous
//! engine (scheduler / lifecycle / dispatcher) drives it unchanged. It performs
//! exactly one HTTP round-trip per call and returns the raw
//! [`HttpResponse`] — **it does NOT retry**. Retry / backoff / fail-fast all live
//! in the engine, which routes every [`HttpResponse`] through
//! [`classify_response`](super::transport::classify_response) →
//! [`classify_http`](crate::decide::classify_http). A transport-level failure
//! (DNS / connect / read timeout) is surfaced as [`HttpResponse::transport_error`]
//! (status `0`), which the engine classifies as retryable.
//!
//! ## Header / path conventions
//! Identical to the Java (`PlatformHttpClient`), Python (`PlatformHttpClient`)
//! and Go (`HTTPTransport`) SDKs:
//! * `X-Batch-Tenant-Id` — tenant header (always).
//! * `X-Batch-Api-Key` — api-key header (when configured, non-empty).
//! * `Idempotency-Key` — on `claim` and `report` only (write dedup).
//! * `Content-Type: application/json` + `Accept: application/json`.
//!
//! ## Paths (`docs/api/orchestrator-internal.openapi.yaml`)
//! * register → `POST /internal/workers/register`
//! * heartbeat → `POST /internal/workers/{code}/heartbeat`
//! * deactivate → `POST /internal/workers/{code}/deactivate`
//! * claim → `POST /internal/tasks/{id}/claim`
//! * report → `POST /internal/tasks/{id}/report`
//! * renew → `POST /internal/tasks/{id}/renew`
//!
//! ## No `unwrap()` on network input
//! Every fallible reqwest call maps `Err` to `HttpResponse::transport_error()`;
//! a non-2xx status is *not* an error here — the body + status are returned
//! verbatim so the engine's FSM decides what to do.

use std::time::Duration;

use reqwest::blocking::Client;
use reqwest::header::{HeaderMap, HeaderName, HeaderValue, ACCEPT, CONTENT_TYPE};

use super::transport::{HttpResponse, Transport};
use crate::constants::SUPPORTED_SCHEMA_VERSIONS;

// Header names (lowercase for `HeaderName::from_static`), identical to the
// Java/Python/Go SDKs: `X-Batch-Tenant-Id`, `X-Batch-Api-Key`, `Idempotency-Key`.
const HEADER_TENANT_ID: &str = "x-batch-tenant-id";
const HEADER_API_KEY: &str = "x-batch-api-key";
const HEADER_IDEMPOTENCY_KEY: &str = "idempotency-key";

/// Construction-time configuration for [`ReqwestTransport`].
///
/// `connect_timeout_ms` and `read_timeout_ms` map to reqwest's connect + read
/// timeouts. Per the BYO SDK guide §1.1 the per-request timeout must stay below
/// `heartbeat_interval / 3` so a stalled call can never starve the heartbeat
/// loop; the default (10s) matches the Go/Java clients.
#[derive(Debug, Clone)]
pub struct ReqwestConfig {
    /// Orchestrator base URL, e.g. `https://orch.internal:8080` (no trailing `/`).
    pub base_url: String,
    /// Tenant id sent as `X-Batch-Tenant-Id` on every call. Required.
    pub tenant_id: String,
    /// Platform api key sent as `X-Batch-Api-Key`. When `None`/empty the header
    /// is omitted (mirrors the other SDKs' "api key optional in dev" behavior).
    pub api_key: Option<String>,
    /// TCP connect timeout in milliseconds.
    pub connect_timeout_ms: u64,
    /// Per-request read timeout in milliseconds (§1.1: keep < heartbeat/3).
    pub read_timeout_ms: u64,
}

impl ReqwestConfig {
    /// Build a config with the default 5s connect / 10s read timeouts.
    pub fn new(base_url: impl Into<String>, tenant_id: impl Into<String>) -> Self {
        Self {
            base_url: base_url.into(),
            tenant_id: tenant_id.into(),
            api_key: None,
            connect_timeout_ms: 5_000,
            read_timeout_ms: 10_000,
        }
    }

    /// Set the api key (`X-Batch-Api-Key`).
    pub fn with_api_key(mut self, api_key: impl Into<String>) -> Self {
        self.api_key = Some(api_key.into());
        self
    }

    /// Override connect + read timeouts (milliseconds).
    pub fn with_timeouts(mut self, connect_timeout_ms: u64, read_timeout_ms: u64) -> Self {
        self.connect_timeout_ms = connect_timeout_ms;
        self.read_timeout_ms = read_timeout_ms;
        self
    }
}

/// A real, blocking [`Transport`] backed by a pooled `reqwest::blocking::Client`.
///
/// One instance is shared (`Arc`) across the heartbeat / lease-renew / consume
/// loops; the underlying client keeps connections alive so heartbeats don't
/// re-handshake each tick. Cloning is cheap — reqwest's client is internally
/// reference-counted.
#[derive(Debug, Clone)]
pub struct ReqwestTransport {
    client: Client,
    base_url: String,
    tenant_id: String,
    api_key: Option<String>,
}

/// Construction error for [`ReqwestTransport::new`] — only the one-time client
/// build (TLS backend init / invalid header value) can fail; per-call network
/// failures are modeled as [`HttpResponse::transport_error`], never this error.
#[derive(Debug)]
pub struct TransportBuildError(String);

impl std::fmt::Display for TransportBuildError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "failed to build ReqwestTransport: {}", self.0)
    }
}

impl std::error::Error for TransportBuildError {}

impl ReqwestTransport {
    /// Build the transport from [`ReqwestConfig`]. Returns
    /// [`TransportBuildError`] only if the client (TLS / timeout) cannot be
    /// constructed or the tenant/api-key contain non-ASCII header bytes.
    pub fn new(config: ReqwestConfig) -> Result<Self, TransportBuildError> {
        // Validate the static auth headers up-front so a bad tenant/api-key
        // fails loudly at construction, not silently per request.
        let mut default_headers = HeaderMap::new();
        default_headers.insert(ACCEPT, HeaderValue::from_static("application/json"));
        default_headers.insert(
            HeaderName::from_static(HEADER_TENANT_ID),
            HeaderValue::from_str(&config.tenant_id)
                .map_err(|e| TransportBuildError(format!("invalid tenant id: {e}")))?,
        );
        if let Some(key) = config.api_key.as_ref().filter(|k| !k.is_empty()) {
            default_headers.insert(
                HeaderName::from_static(HEADER_API_KEY),
                HeaderValue::from_str(key)
                    .map_err(|e| TransportBuildError(format!("invalid api key: {e}")))?,
            );
        }

        let client = Client::builder()
            .connect_timeout(Duration::from_millis(config.connect_timeout_ms))
            .timeout(Duration::from_millis(config.read_timeout_ms))
            .default_headers(default_headers)
            .build()
            .map_err(|e| TransportBuildError(e.to_string()))?;

        Ok(Self {
            client,
            base_url: config.base_url.trim_end_matches('/').to_string(),
            tenant_id: config.tenant_id,
            api_key: config.api_key.filter(|k| !k.is_empty()),
        })
    }

    /// The configured tenant id (for diagnostics / tests).
    pub fn tenant_id(&self) -> &str {
        &self.tenant_id
    }

    /// Whether an api key is configured (for diagnostics / tests).
    pub fn has_api_key(&self) -> bool {
        self.api_key.is_some()
    }

    /// POST `body` (already-serialized JSON from the engine) to `path`,
    /// optionally with an `Idempotency-Key`. Returns the raw status + body;
    /// any transport failure becomes [`HttpResponse::transport_error`].
    fn post(&self, path: &str, body: &str, idempotency_key: Option<&str>) -> HttpResponse {
        let url = format!("{}{}", self.base_url, path);
        let mut req = self
            .client
            .post(url)
            .header(CONTENT_TYPE, "application/json")
            .body(body.to_string());

        if let Some(key) = idempotency_key.filter(|k| !k.is_empty()) {
            // `header` validates the value; a bad value would be silently
            // dropped by reqwest, so fall back to transport_error on a
            // malformed key rather than send an un-deduped write.
            match HeaderValue::from_str(key) {
                Ok(value) => {
                    req = req.header(HeaderName::from_static(HEADER_IDEMPOTENCY_KEY), value);
                }
                Err(_) => return HttpResponse::transport_error(),
            }
        }

        match req.send() {
            Ok(resp) => {
                let status = resp.status().as_u16() as i64;
                // Read the body for the engine to parse; an unreadable body is
                // not fatal — surface the status with an empty body.
                let text = resp.text().unwrap_or_default();
                HttpResponse::new(status, &text)
            }
            // DNS / connect / read-timeout / TLS → status 0 (retryable).
            Err(_) => HttpResponse::transport_error(),
        }
    }
}

/// Generate a one-shot idempotency key (`rs-<uuid>`), matching the decision
/// core's fallback (`crate::decide`) and the Go/Python SDKs' per-attempt key.
/// std-only — no `rand`/`uuid` crate.
fn new_idempotency_key() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let mut state = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0x9e37_79b9_7f4a_7c15)
        | 1;
    let mut next = || {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        state
    };
    let mut bytes = [0u8; 16];
    for chunk in bytes.chunks_mut(8) {
        let r = next().to_le_bytes();
        chunk.copy_from_slice(&r[..chunk.len()]);
    }
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    let hex: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    format!(
        "rs-{}-{}-{}-{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..32],
    )
}

/// The SDK's current wire-protocol major — the last entry of
/// [`SUPPORTED_SCHEMA_VERSIONS`] — advertised on register (#536 gate). Kept in
/// sync with the supported-versions list rather than hard-coded.
fn current_protocol_version() -> &'static str {
    SUPPORTED_SCHEMA_VERSIONS
        .last()
        .copied()
        .unwrap_or("v1")
}

/// Default `protocolVersion` into a register body. If `body` parses to a JSON
/// object lacking `protocolVersion`, insert the current major and re-serialize;
/// a tenant-provided value is preserved. If `body` is not a JSON object (or
/// fails to parse), it is returned unchanged.
fn with_protocol_version(body: &str) -> String {
    match serde_json::from_str::<serde_json::Value>(body) {
        Ok(serde_json::Value::Object(mut map)) => {
            map.entry("protocolVersion".to_string())
                .or_insert_with(|| serde_json::Value::from(current_protocol_version()));
            serde_json::Value::Object(map).to_string()
        }
        _ => body.to_string(),
    }
}

impl Transport for ReqwestTransport {
    fn register(&self, _worker_code: &str, body: &str) -> HttpResponse {
        // #536 register-time protocol-version gate: default `protocolVersion` to
        // the SDK's current major (last of SUPPORTED_SCHEMA_VERSIONS) so the
        // platform identifies + accepts us. A tenant-provided value is left
        // intact; a non-object body is sent verbatim. Register only.
        let sent = with_protocol_version(body);
        self.post("/internal/workers/register", &sent, None)
    }

    fn heartbeat(&self, worker_code: &str, body: &str) -> HttpResponse {
        let path = format!("/internal/workers/{worker_code}/heartbeat");
        self.post(&path, body, None)
    }

    fn deactivate(&self, worker_code: &str) -> HttpResponse {
        let path = format!("/internal/workers/{worker_code}/deactivate");
        // Deactivate has no body; the platform tolerates an empty object.
        self.post(&path, "{}", None)
    }

    fn claim(&self, task_id: &str, body: &str) -> HttpResponse {
        let path = format!("/internal/tasks/{task_id}/claim");
        self.post(&path, body, Some(&new_idempotency_key()))
    }

    fn report(&self, task_id: &str, body: &str) -> HttpResponse {
        let path = format!("/internal/tasks/{task_id}/report");
        self.post(&path, body, Some(&new_idempotency_key()))
    }

    fn renew(&self, task_id: &str, body: &str) -> HttpResponse {
        let path = format!("/internal/tasks/{task_id}/renew");
        // renew carries no Idempotency-Key (openapi: lease renew is naturally
        // idempotent on the lease key), matching Java/Go.
        self.post(&path, body, None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::client::transport::{classify_response, TransportOutcome};
    use std::io::{BufRead, BufReader, Read, Write};
    use std::net::{TcpListener, TcpStream};
    use std::sync::mpsc;
    use std::thread;

    /// A captured request the one-shot mock server hands back to the test.
    #[derive(Debug, Default, Clone)]
    struct CapturedRequest {
        method: String,
        path: String,
        headers: Vec<(String, String)>,
        body: String,
    }

    impl CapturedRequest {
        fn header(&self, name: &str) -> Option<&str> {
            self.headers
                .iter()
                .find(|(k, _)| k.eq_ignore_ascii_case(name))
                .map(|(_, v)| v.as_str())
        }
    }

    /// Spawn a single-connection HTTP/1.1 server that captures one request and
    /// replies with `status`/`resp_body`. Returns the bound base URL and a
    /// receiver for the captured request.
    fn one_shot_server(status: u16, resp_body: &'static str) -> (String, mpsc::Receiver<CapturedRequest>) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind ephemeral port");
        let addr = listener.local_addr().expect("local addr");
        let (tx, rx) = mpsc::channel();

        thread::spawn(move || {
            let (stream, _) = match listener.accept() {
                Ok(s) => s,
                Err(_) => return,
            };
            let captured = handle_connection(stream, status, resp_body);
            let _ = tx.send(captured);
        });

        (format!("http://{addr}"), rx)
    }

    fn handle_connection(mut stream: TcpStream, status: u16, resp_body: &str) -> CapturedRequest {
        let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
        let mut req = CapturedRequest::default();

        // Request line: e.g. "POST /internal/tasks/7/claim HTTP/1.1".
        let mut request_line = String::new();
        reader.read_line(&mut request_line).expect("read request line");
        let mut parts = request_line.split_whitespace();
        req.method = parts.next().unwrap_or_default().to_string();
        req.path = parts.next().unwrap_or_default().to_string();

        // Headers until the blank line.
        let mut content_length = 0usize;
        loop {
            let mut line = String::new();
            reader.read_line(&mut line).expect("read header line");
            let trimmed = line.trim_end_matches(['\r', '\n']);
            if trimmed.is_empty() {
                break;
            }
            if let Some((name, value)) = trimmed.split_once(':') {
                let name = name.trim().to_string();
                let value = value.trim().to_string();
                if name.eq_ignore_ascii_case("content-length") {
                    content_length = value.parse().unwrap_or(0);
                }
                req.headers.push((name, value));
            }
        }

        // Body, exactly content-length bytes.
        if content_length > 0 {
            let mut buf = vec![0u8; content_length];
            reader.read_exact(&mut buf).expect("read body");
            req.body = String::from_utf8_lossy(&buf).into_owned();
        }

        let reason = if (200..300).contains(&status) { "OK" } else { "ERR" };
        let response = format!(
            "HTTP/1.1 {status} {reason}\r\nContent-Length: {}\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{resp_body}",
            resp_body.len(),
        );
        stream.write_all(response.as_bytes()).expect("write response");
        stream.flush().expect("flush response");
        req
    }

    fn transport(base_url: &str) -> ReqwestTransport {
        ReqwestTransport::new(
            ReqwestConfig::new(base_url, "tenant-42")
                .with_api_key("secret-key")
                .with_timeouts(2_000, 2_000),
        )
        .expect("build transport")
    }

    #[test]
    fn claim_sends_path_auth_and_idempotency_headers() {
        let (base, rx) = one_shot_server(200, r#"{"leaseUntil":"2026-01-01T00:00:00Z"}"#);
        let t = transport(&base);

        let resp = t.claim("7", r#"{"tenantId":"tenant-42","workerId":"w1"}"#);

        let req = rx.recv().expect("captured request");
        assert_eq!(req.method, "POST");
        assert_eq!(req.path, "/internal/tasks/7/claim");
        assert_eq!(req.header("X-Batch-Tenant-Id"), Some("tenant-42"));
        assert_eq!(req.header("X-Batch-Api-Key"), Some("secret-key"));
        assert_eq!(req.header("Content-Type"), Some("application/json"));
        // Idempotency-Key MUST be present on claim.
        let idem = req.header("Idempotency-Key").expect("idempotency-key on claim");
        assert!(idem.starts_with("rs-"), "idempotency key = {idem}");
        // Body carries the required identity fields.
        assert!(req.body.contains("\"tenantId\":\"tenant-42\""));
        assert!(req.body.contains("\"workerId\":\"w1\""));

        assert_eq!(resp.status, 200);
        assert_eq!(classify_response(&resp, 0), TransportOutcome::Success);
    }

    #[test]
    fn report_carries_idempotency_key_and_required_fields() {
        let (base, rx) = one_shot_server(200, "");
        let t = transport(&base);

        let body = r#"{"taskId":7,"tenantId":"tenant-42","workerId":"w1","success":true}"#;
        let resp = t.report("7", body);

        let req = rx.recv().expect("captured request");
        assert_eq!(req.path, "/internal/tasks/7/report");
        assert!(req.header("Idempotency-Key").is_some(), "report needs idempotency-key");
        assert!(req.body.contains("\"success\":true"));
        assert!(req.body.contains("\"taskId\":7"));
        assert_eq!(classify_response(&resp, 0), TransportOutcome::Success);
    }

    #[test]
    fn register_hits_correct_path_without_idempotency_key() {
        let (base, rx) = one_shot_server(200, "");
        let t = transport(&base);

        t.register("w1", r#"{"workerCode":"w1","tenantId":"tenant-42"}"#);

        let req = rx.recv().expect("captured request");
        assert_eq!(req.path, "/internal/workers/register");
        assert_eq!(req.header("X-Batch-Tenant-Id"), Some("tenant-42"));
        // register is not a per-task write → no Idempotency-Key.
        assert!(req.header("Idempotency-Key").is_none());
        // #536: protocolVersion defaulted to the SDK's current major (v2).
        assert!(
            req.body.contains("\"protocolVersion\":\"v2\""),
            "register body must advertise protocolVersion, got {}",
            req.body,
        );
        // original fields preserved.
        assert!(req.body.contains("\"workerCode\":\"w1\""));
    }

    #[test]
    fn register_preserves_tenant_provided_protocol_version() {
        let (base, rx) = one_shot_server(200, "");
        let t = transport(&base);
        t.register("w1", r#"{"workerCode":"w1","protocolVersion":"v1"}"#);
        let req = rx.recv().expect("captured request");
        // tenant-provided value is left intact (not overwritten).
        assert!(req.body.contains("\"protocolVersion\":\"v1\""));
        assert!(!req.body.contains("\"protocolVersion\":\"v2\""));
    }

    #[test]
    fn register_non_object_body_sent_verbatim() {
        let (base, rx) = one_shot_server(200, "");
        let t = transport(&base);
        // a non-object JSON body is forwarded unchanged.
        t.register("w1", "[]");
        let req = rx.recv().expect("captured request");
        assert_eq!(req.body, "[]");
    }

    #[test]
    fn heartbeat_and_deactivate_use_worker_code_paths() {
        let (base, rx) = one_shot_server(200, "{}");
        let t = transport(&base);
        t.heartbeat("worker-9", r#"{"state":"RUNNING"}"#);
        assert_eq!(rx.recv().unwrap().path, "/internal/workers/worker-9/heartbeat");

        let (base2, rx2) = one_shot_server(200, "");
        let t2 = transport(&base2);
        t2.deactivate("worker-9");
        assert_eq!(rx2.recv().unwrap().path, "/internal/workers/worker-9/deactivate");
    }

    #[test]
    fn renew_has_no_idempotency_key() {
        let (base, rx) = one_shot_server(200, r#"{"cancelRequested":false}"#);
        let t = transport(&base);
        t.renew("7", r#"{"workerId":"w1","tenantId":"tenant-42"}"#);
        let req = rx.recv().expect("captured request");
        assert_eq!(req.path, "/internal/tasks/7/renew");
        assert!(req.header("Idempotency-Key").is_none());
    }

    #[test]
    fn server_error_surfaces_as_retry() {
        let (base, _rx) = one_shot_server(503, "");
        let t = transport(&base);
        let resp = t.report("7", "{}");
        assert_eq!(resp.status, 503);
        assert!(matches!(
            classify_response(&resp, 0),
            TransportOutcome::Retry { .. }
        ));
    }

    #[test]
    fn conflict_surfaces_as_idempotent_success() {
        let (base, _rx) = one_shot_server(409, r#"{"error":"already claimed"}"#);
        let t = transport(&base);
        let resp = t.claim("7", "{}");
        assert_eq!(resp.status, 409);
        assert_eq!(
            classify_response(&resp, 0),
            TransportOutcome::IdempotentSuccess
        );
    }

    #[test]
    fn auth_failure_surfaces_as_fail_fast() {
        let (base, _rx) = one_shot_server(401, "");
        let t = transport(&base);
        let resp = t.claim("7", "{}");
        assert_eq!(resp.status, 401);
        assert_eq!(classify_response(&resp, 0), TransportOutcome::FailFast);
    }

    #[test]
    fn unreachable_host_surfaces_as_transport_error() {
        // No server bound here → connect fails fast.
        let t = ReqwestTransport::new(
            ReqwestConfig::new("http://127.0.0.1:1", "tenant-42").with_timeouts(300, 300),
        )
        .expect("build transport");
        let resp = t.report("7", "{}");
        assert_eq!(resp.status, 0);
        assert!(matches!(
            classify_response(&resp, 0),
            TransportOutcome::Retry { .. }
        ));
    }

    #[test]
    fn api_key_omitted_when_absent() {
        let (base, rx) = one_shot_server(200, "");
        let t = ReqwestTransport::new(ReqwestConfig::new(&base, "tenant-42"))
            .expect("build transport");
        assert!(!t.has_api_key());
        t.register("w1", "{}");
        let req = rx.recv().expect("captured request");
        assert!(req.header("X-Batch-Api-Key").is_none());
    }
}

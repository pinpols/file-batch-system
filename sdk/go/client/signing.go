package client

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"strings"
)

// SDK 侧请求签名（方案 A，以 api_key 为 HMAC 密钥）。
//
// 必须与服务端 io.github.pinpols.batch.common.security.RequestSignatures 以及
// Java SDK 的 RequestSigner 逐字节一致 —— 三者由各语言 SDK 的 conformance 用例
// 钉死同一 golden 向量。算法：
//
//	canonical = UPPER(method) "\n" path "\n" timestamp "\n" nonce "\n" hex(sha256(body))
//	signature = hex(hmacSha256(apiKey, canonical))     // 小写 hex
//
// timestamp 为 epoch 毫秒字符串；nonce 建议 UUID；body 为请求原始字节（空 body 也要
// 算 sha256）。
const (
	// HeaderSignatureTimestamp carries the epoch-millis timestamp.
	HeaderSignatureTimestamp = "X-Batch-Timestamp"
	// HeaderSignatureNonce carries the per-request nonce (UUID).
	HeaderSignatureNonce = "X-Batch-Nonce"
	// HeaderSignature carries the lowercase-hex HMAC-SHA256 signature.
	HeaderSignature = "X-Batch-Signature"
)

// bodySha256Hex returns the lowercase-hex SHA-256 of the request body. A nil
// body hashes the empty byte slice (the platform always hashes a body, even an
// absent one).
func bodySha256Hex(body []byte) string {
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}

// canonicalString assembles the canonical signing string. method is uppercased;
// the remaining components are joined verbatim by '\n', with the SHA-256 body
// digest as the final line.
func canonicalString(method, path, timestamp, nonce string, body []byte) string {
	return strings.ToUpper(method) +
		"\n" + path +
		"\n" + timestamp +
		"\n" + nonce +
		"\n" + bodySha256Hex(body)
}

// signRequest computes the lowercase-hex HMAC-SHA256 of the canonical string
// keyed by apiKey. This is the value sent in the X-Batch-Signature header.
func signRequest(apiKey, method, path, timestamp, nonce string, body []byte) string {
	mac := hmac.New(sha256.New, []byte(apiKey))
	mac.Write([]byte(canonicalString(method, path, timestamp, nonce, body)))
	return hex.EncodeToString(mac.Sum(nil))
}

// isWriteMethod reports whether the HTTP method is a mutating (write) request
// (POST/PUT/PATCH/DELETE). Only write requests carry a signature.
func isWriteMethod(method string) bool {
	switch strings.ToUpper(method) {
	case http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete:
		return true
	default:
		return false
	}
}

// newNonce mints a per-request RFC-4122-ish v4 UUID for the X-Batch-Nonce
// header. Only uniqueness/shape matters (the platform treats it as opaque).
func newNonce() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

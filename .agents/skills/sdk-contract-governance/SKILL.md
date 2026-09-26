---
name: sdk-contract-governance
description: 修改或审查 Java/Go/Python/TypeScript/Rust SDK、共享契约、conformance、transport、版本发布和多语言兼容性时使用。重点区分 fixture 通过与真实生产链路可用。
---

# SDK 契约治理

## 契约优先

- SDK 行为以平台协议、共享 fixture、错误码、offset disposition、心跳/续约/取消和幂等语义为准；单语言实现不能自行改变契约。
- 公共 API、配置名、默认值、异常类型和生成常量变化要评估向后兼容与版本发布影响。
- Java core、Spring 集成和示例工程要保持边界；不要把 Spring 依赖泄漏到 core。

## 多语言一致性

- Java、Go、Python、TypeScript、Rust 的决策核、序列化、时间处理、重试、退避、offset 提交和错误映射要对齐。
- Fixture conformance 只能证明静态决策一致；真实 transport/lifecycle/scheduler 仍需独立验证。
- 变更一门语言时检查其它语言是否需要同步，不能只更新 Java 后宣称 SDK 全部充分。

## 生产链路关注点

- HTTP/Kafka transport、DNS/IPv6/Happy Eyeballs、代理、TLS、连接池、关闭 drain、lease renew、backpressure、取消和优雅停机。
- 未知大版本、中间件不可达、超时、重复投递和 offset 不提交等边界要有明确行为。
- 示例、README、包版本和发布脚本要与实际代码一致。

## 验证与报告

- 优先运行受影响语言的单测、fixture conformance 和真实 transport 集成；无法运行时说明语言、原因和风险。
- 报告中区分“Java 已完整验证”“其它语言 fixture 通过”“真实网络/代理/故障未验证”。
- 生成代码或共享常量变化后，检查所有语言仓库文件是否同步提交。

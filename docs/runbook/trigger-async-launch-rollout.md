# Trigger 异步路径与旧灰度流程退役说明

ADR-010 的异步 Kafka 路径已固化为唯一运行路径；同步 HTTP 适配器及旧灰度开关已删除，旧开关切换与同步回退步骤不再适用。

运行和恢复沿用[Trigger 运维手册](trigger-operations.md)，检查触发持久化、Outbox、消息投递与实例身份后再处置；不能通过恢复旧同步入口绕过幂等和状态推进。设计边界见[ADR-010](../architecture/adr/ADR-010-trigger-async-decoupling.md)。

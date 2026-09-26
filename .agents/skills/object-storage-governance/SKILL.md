---
name: object-storage-governance
description: 审查或修改对象存储、本地文件存储、S3 兼容适配、MinIO/RustFS/Ceph/SeaweedFS 选型、加密、校验和分发文件完整性时使用。
---

# 文件与对象存储治理

## 抽象边界

- 业务代码应依赖 `BatchObjectStore` 等仓库抽象，不直接绑定 MinIO、AWS S3、RustFS、Ceph RGW 或本地文件系统实现。
- 对外命名优先使用“对象存储”或“S3 兼容”，只有 provider-specific 配置、文档和测试才写具体产品名。
- 本地文件存储要明确由后端下载接口返回、由网关静态映射，还是仅供 worker 内部使用；路径不能泄露宿主机布局。

## 完整性与安全

- `put`/`get` 契约必须覆盖实际字节数、声明长度、checksum、metadata、content type 和失败清理。
- 加密、压缩或封装层不能信任调用方声明的 size；需要自己计数、限制实际读取字节并校验长度一致。
- 到达触发和 dispatch 分发建议使用 `.chk`/manifest 或等价 envelope 记录文件名、长度、hash、生成时间和业务键，确保“先写数据、后发布完成信号”。
- 禁止在日志、异常和对象 metadata 中泄漏密钥、内部路径或租户敏感字段。

## 兼容验证

- 存储适配变化至少覆盖文件系统实现和一个 S3 兼容实现；不要把 MinIO 通过等同于所有云 OSS/私有 S3 通过。
- 检查 multipart、range/read-after-write、metadata 大小写、路径编码、删除幂等、异常映射和超时重试。
- 端到端测试若绑定 MinIO，要评估是否能通过配置切换到其它 S3 兼容实现；不能切换时记录绑定原因。

## 选型报告

选型时说明合规授权、私有部署成本、运维复杂度、S3 兼容差异、HA/EC 能力、监控备份和迁移路径。不要仅凭“开源”或“兼容 S3”判定适配。

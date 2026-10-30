# 对象完整性、完成信号与生命周期

## OBJ-1 发布完成信号前核对对象

数据上传、临时对象/promote、checksum/manifest或.chk发布及业务状态提交分别建时间线。先写完成标记却还没完成对象会让下游读取半文件；对上传响应丢失、multipart完成失败、重复promote和孤儿分片按实际provider契约验证。

## OBJ-2 流式变换与长度

压缩/解密/Range等操作分别核对输入长度、实际读字节、解压后上限、checksum、资源关闭与失败残留；不能信任声明size或以文件存在判成功。路径编码、metadata大小写与read-after-write差异按provider实测，不从一个S3兼容实现推广全部。

## OBJ-3 清理不只看对象年龄

业务文件、AI附件、临时产物与审计/回执有不同前缀、租户权限和保留要求；生命周期apply与preview不同。删除可能让恢复或交付重放失去依据，先核对对象和receipt的拥有权、用途、合法hold及目标环境，不把旧示例保留天数当通用参数。

## 当前来源

- [file-integrity-sidecar-manifest-design.md](../../../../docs/design/file-integrity-sidecar-manifest-design.md)
- [file-integrity-recovery.md](../../../../docs/design/file-integrity-recovery.md)
- [object-storage-lifecycle-policy.md](../../../../docs/runbook/object-storage-lifecycle-policy.md)
- [README.md](../../../../scripts/minio/README.md)
- [object-storage-s3-backends.md](../../../../docs/runbook/object-storage-s3-backends.md)

只沿本次任务读取必要章节；脚本参数和环境事实每次从现行文件确认。本文不授权部署、迁移、清理、压测或外部发送。

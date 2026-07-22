# 代码生成辅助脚本

这里放不会直接改变生产运行态的生成类工具。

- `gen-error-codes-dict.py`：从后端错误码定义生成文档或前端可消费的错误码字典。

生成 OpenAPI / SDK 时优先使用 Maven profile 和 [../ci/README.md](../ci/README.md) 中记录的 CI 入口。

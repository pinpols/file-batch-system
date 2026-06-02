"""Python SDK handler/ 子树的跨域契约测试。

这些测试验证 Python SDK 的 6 个抽象 handler 基类与 11 个具体
handler(4 atomic + 3 builtin + 4 typed)在行为上等价于其 Java SDK
对应实现。测试对模块缺失有意宽容:当其中某个依赖 feature 分支尚未
合入 main 时,受影响的用例只 skip(不 fail)。一旦全部 4 个依赖
PR 合并完成,本 PR 的 CI 应当完全转绿。
"""

# 多语言SDK协议与生命周期核对方法

<a id="SDK-1"></a>

## SDK-1 真实出站请求与跨语言矩阵

适用于协议改造与新语言适配。逐端点列method/path/header/body/默认值/错误反应，对每门语言沿真实公共API和序列化器捕获请求；区分只检查fixture结构、纯响应决策和实际transport调用的runner。

核对字段名称、bool/数字类型、null与缺失、时间、枚举、schemaVersion和未知字段；沿claim→renew→report核对同一次认领身份。删改必需请求字段、改错别名或请求方法应让真实请求侧断言失败，不能只让纯决策核输出预设正确对象。

同一逻辑请求的重试是否复用幂等键按现行协议确认；不能照搬旧报告“每次请求都新生成UUID”的表述，导致响应丢失后重复副作用。不同业务操作与同一请求重试分别测试。当前事实源为[wire protocol](../../../../docs/sdk/wire-protocol.md)和[conformance合同](../../../../docs/sdk/byo-conformance-contract.md)，不以Java实现单方面定义全部语言契约。

<a id="SDK-2"></a>

## SDK-2 生命周期等价而非API名字对齐

适用于心跳、续租、取消、背压、drain和关闭。按注册→启动调度器→接收/暂停→在途任务→终止调度器/transport/线程列所有者及失败清理，验证关闭后的请求、线程和in-flight记录确实终止，取消信号到达实际任务。

Java fixedDelay与Python异步sleep循环可以实现等价的“不重叠”语义，不能因没有同名调度API判缺功能。对共享字典、worker状态和任务注册的并发核对锁或事件循环归属；认证配置错误与瞬时网络故障分别验证终止/重试，不能无界重试后靠Pod强杀收尾。

<a id="SDK-3"></a>

## SDK-3 消费进度、失败分类与旧身份

适用于异步执行与重平衡。按partition追踪收到、在途、完成、失败与可提交的连续进度，注入乱序完成、前项瞬时失败、毒丸和rebalance，确认后项成功不会跨过未解决前项确认。

旧consumer generation或旧invocation完成时，不得改写新拥有者状态；任务认领/续租/上报被拒与HTTP请求重试也不能混为一个结果。未知协议版本、4xx、5xx与404的具体处置从当前协议取得，历史阈值不固化到方法中。每门语言分别报告fixture、真实transport与目标环境验证。

## 来源与适用性

本页从历史审查、失败记录和方案取舍中提炼工作方法；不继承旧缺陷状态、版本、阈值、部署方案或通过结论。逐篇来源与 SHA-256 见[经验提炼映射](../../../../docs/audit/archive-experience-skill-extraction-2026-10-25.csv)，当前规范优先。

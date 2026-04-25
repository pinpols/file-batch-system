汇总（按严重度）                                                                                                                                                                                                             
                                        
  真错误（应修代码）                                                                                                                                                                                                           
                                               
  ┌──────────────┬──────┬────────────────────────────────────────────────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐   
  │     位置     │ 次数 │                          报错                          │                                                                  根因                                                                   │
  ├──────────────┼──────┼───────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤   
  │ orchestrator │ 2    │ DuplicateKeyException: uk_batch_day_instance                          │ 每日 03:00 触发并发创建 default-tenant / default_calendar / 2026-04-23 的 batch day instance，第二个 INSERT 撞唯一键 →   │
  │              │      │ (tenant+calendar+biz_date)                                            │ 500 回给 trigger（变成 trigger.log 的 SYSTEM_ERROR）。典型 create-if-absent 竞态，应 ON CONFLICT DO NOTHING + 回查。     │   
  ├──────────────┼──────┼───────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤   
  │ orchestrator │ 1    │ OptimisticLockingFailureException on quota_runtime_state id=12        │ 两个线程并发改同一行 quota → 乐观锁冲突冒泡到 OrchestratorApiExceptionHandler → 500。应路径内 retry 或在 handler         │
  │              │      │                                                                       │ 里把乐观锁冲突降为 409。                                                                                                 │   
  ├──────────────┼──────┼───────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ orchestrator │ 12   │ QueryTimeoutException: Redis command timed out in                     │ ShedLock 从 Redis 取锁超时直接向 scheduler 顶层冒泡 → TaskUtils 打 ERROR。应在 lock() 里 catch 后 return                 │   
  │              │      │ RedisShedLockProvider.lock                                            │ false（视为没拿到锁，下 tick 再来）。                                                                                    │   
  ├──────────────┼──────┼───────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ trigger      │ 1    │ Quartz ERROR: Job ... threw an unhandled Exception，根因是            │ DefaultTriggerService.forward 没有把 422 业务拒绝当可预期结果，抛出变成 Quartz 未捕获异常 + 重试。应识别                 │   
  │              │      │ orchestrator 返回 422 BUSINESS_ERROR: outside batch window            │ 422/BUSINESS_ERROR 为"本次跳过、不重试"。                                                                                │   
  └──────────────┴──────┴───────────────────────────────────────────────────────────────────────┴──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
                                                                                                                                                                                                                               
  长期噪声（应降噪）                    
                                          
  ┌─────────────────┬──────┬───────────────────────────────────────────────────────────────────────────┬───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐   
  │      位置       │ 次数 │                                   报错                                    │                                                       建议                                                        │
  ├─────────────────┼──────┼───────────────────────────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤   
  │ worker-dispatch │ 878  │ NAS directory contains symlink(s): /tmp → /private/tmp                    │ macOS 本地 /tmp 本来就是 symlink，每次 NAS dispatch 都刷一次。要么按"首次 WARN 后降级 DEBUG"，要么识别等价根      │
  │                 │      │                                                                           │ (/tmp ≡ /private/tmp) 后不 WARN。                                                                                 │
  ├─────────────────┼──────┼───────────────────────────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤   
  │                 │      │ DefaultWorkerSelector - worker selection returned no match:               │ 脏数据：某个 partition/job 挂了 workerGroup=GENERAL + resourceTag=misc，但系统里根本没有这种 worker（都是         │
  │ orchestrator    │ 1073 │ workerGroup=GENERAL, resourceTag=misc, reason=no_online_workers_in_group  │ IMPORT/EXPORT/DISPATCH 组）。需要 SELECT 定位哪个 instance 再处理（禁用 job / 改 worker_group / 注入对应          │   
  │                 │      │                                                                           │ worker）。                                                                                                        │
  ├─────────────────┼──────┼───────────────────────────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤   
  │ orchestrator    │ 108  │ DefaultStateMachine - NOOP on unknown event: fromState=RUNNING,           │ 高频重复事件（worker 重复上报？），NOOP 安全但噪声大。建议"同状态同事件"降级为 DEBUG。                            │
  │                 │      │ event=RUNNING                                                             │                                                                                                                   │   
  └─────────────────┴──────┴───────────────────────────────────────────────────────────────────────────┴───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
                                                                                                                                                                                                                               
  建议处理顺序                            
                                               
  工作量约 1 个小时，我拆 4 个最小 fix，建议先做 P0 四条（代码 bug、对业务有真实影响）：                                                                                                                                       
                                        
  1. BatchDaySettleScheduler / LaunchBatchDayService：batch_day_instance 创建走 INSERT ... ON CONFLICT DO NOTHING + 回查（上次已经有 V62 乐观锁改造的底子，加 ON CONFLICT 最小）                                               
  2. DefaultTriggerService.forward：识别 HttpClientErrorException status=422 + code=BUSINESS_ERROR → WARN 一行、不 rethrow、不进 Quartz retry
  3. RedisShedLockProvider.lock：catch QueryTimeoutException / DataAccessResourceFailureException → return false（拿不到锁）                                                                                                   
  4. OrchestratorApiExceptionHandler 或 quota service：OptimisticLockingFailureException 映射为 CONFLICT（409）或内部 retry，不再 500                                                                                          
                                                                                                                                                                                                                               
  然后 P1（长期噪声）：                                                                                                                                                                                                        
  5. RemoteFilesystemDispatchSupport：WARN 加一次性抑制（ConcurrentHashMap<Path> warnedPaths），同一 configured path 只报第一次                                                                                                
  6. DefaultStateMachine：同状态自回边 NOOP 降 DEBUG；其他不明事件保持 WARN                                                                                                                                                    
  7. 排查 1073 条 GENERAL/misc 脏数据：跑一条 SQL 找源头（哪个 job_definition / pending partition 定了这个 group/tag）                                                                                                         
                                                                                                                                                                                                                               
  要我按这个顺序一次做完 1-4（P0），还是先做哪几项？ 5-6 我可以一起带；7 要跑 SQL 查出具体记录后再决定清理还是改 worker 配置。      
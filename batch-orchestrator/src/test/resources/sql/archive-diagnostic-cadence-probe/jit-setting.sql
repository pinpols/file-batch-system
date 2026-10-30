-- 同一物理连接中核对事务局部设置，不能用池中另一条连接代替复位证据。
select current_setting('jit')

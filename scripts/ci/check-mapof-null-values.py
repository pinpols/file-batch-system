#!/usr/bin/env python3
# scripts/ci/check-mapof-null-values.py
#
# Map.of / List.of / Set.of 空值守护(钉死唯一反复复发的生产 NPE 模式)。
#
# 背景(对齐 CLAUDE.md「异常→契约」红线 + 528k LOC null 纪律专项审计结论):
#   Map.of(...) / List.of(...) / Set.of(...) 是不可变工厂,对 null 键/值零容忍——
#   一旦某个值参在运行时为 null 立刻抛 NPE,把一条干净的业务流程 / 4xx 错误
#   掩盖成 500(UI 侧稳定复现)。历史实锤:
#     - #752 (388e43e39):changeFileStatus 审计 detail 的
#       Map.of("errorMessage", exception.getMessage()) —— 归档不带 reason / 异常
#       无 message 时 NPE→500。修法:Objects.requireNonNullElse 兜底。
#     - 2d152390e / 561238eac:Dashboard / 各 sensor 的 Map.of / List.of 同类。
#
#   全仓最高危、最可判定的空值来源是 `Throwable#getMessage()`:其契约明确
#   允许返回 null(无 message 的异常 / 部分 NPE),却被大量当作「必然非空」直接
#   塞进 Map.of / List.of / Set.of 作为参数。本守护**只拦这一最高危、零误报**的
#   模式:工厂调用的**某个顶层实参本身就是一个裸 `xxx.getMessage()` 调用**
#   (未被 Objects.requireNonNullElse / requireNonNull 等兜底包裹)。
#
#   刻意「宁窄勿噪」:.reason() / mapper 返回 / DB 可空列等其它空值来源,静态层面
#   无法零误报判定,留给 NullAway(激活后)接管;本守护不碰,避免噪声淹没信号。
#
# 判定精度:
#   命中  Map.of("k", e.getMessage())            —— 顶层实参就是裸 getMessage()
#   命中  List.of("TAG", e.getMessage())
#   放行  Map.of("k", Objects.requireNonNullElse(e.getMessage(), "")) —— 已兜底
#   放行  log.warn("...", e.getMessage())          —— 非 *.of 工厂
#   放行  ex.getMessage()  单独使用                —— 非工厂实参
#
# 用法:
#   python3 scripts/ci/check-mapof-null-values.py            # 扫全仓生产代码
#   python3 scripts/ci/check-mapof-null-values.py --self-test # 规则自测(CI 内先跑)
#
# escape hatch(仅 dev 本地 debug):BATCH_CI_SKIP_MAPOF_GATE=1
#
import os
import re
import sys

FACTORY_RE = re.compile(r'\b(Map|List|Set)\.of\(')
# 顶层实参「整体就是一个裸 getMessage() 调用链」——排除任何包裹(requireNonNullElse 等)。
BARE_GET_MESSAGE_RE = re.compile(r'^[A-Za-z_$][\w.$]*\.getMessage\(\)$')


def _find_factory_calls(text):
    """产出 (char_offset, kind, args_text) —— args_text 为工厂调用括号内的原文(含多行)。"""
    for m in FACTORY_RE.finditer(text):
        start = m.end()
        depth = 1
        i = start
        in_str = False
        quote = ''
        esc = False
        in_line_comment = False
        in_block_comment = False
        while i < len(text) and depth > 0:
            c = text[i]
            nxt = text[i + 1] if i + 1 < len(text) else ''
            if in_line_comment:
                if c == '\n':
                    in_line_comment = False
            elif in_block_comment:
                if c == '*' and nxt == '/':
                    in_block_comment = False
                    i += 1
            elif in_str:
                if esc:
                    esc = False
                elif c == '\\':
                    esc = True
                elif c == quote:
                    in_str = False
            else:
                if c == '/' and nxt == '/':
                    in_line_comment = True
                elif c == '/' and nxt == '*':
                    in_block_comment = True
                    i += 1
                elif c in '"\'':
                    in_str = True
                    quote = c
                elif c == '(':
                    depth += 1
                elif c == ')':
                    depth -= 1
            i += 1
        yield m.start(), m.group(1), text[start:i - 1]


def _split_top_level(args):
    """按顶层逗号切分实参(忽略括号/字符串内的逗号)。"""
    parts = []
    depth = 0
    cur = ''
    in_str = False
    quote = ''
    esc = False
    for ch in args:
        if in_str:
            cur += ch
            if esc:
                esc = False
            elif ch == '\\':
                esc = True
            elif ch == quote:
                in_str = False
            continue
        if ch in '"\'':
            in_str = True
            quote = ch
            cur += ch
            continue
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(cur.strip())
            cur = ''
        else:
            cur += ch
    if cur.strip():
        parts.append(cur.strip())
    return parts


def scan_text(text):
    """返回命中的 (line_no, kind, arg) 列表。"""
    hits = []
    for pos, kind, args in _find_factory_calls(text):
        for arg in _split_top_level(args):
            if BARE_GET_MESSAGE_RE.match(arg):
                line = text.count('\n', 0, pos) + 1
                hits.append((line, kind, arg))
    return hits


def _iter_prod_java(root):
    for dp, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in ('target', 'node_modules', '.git')]
        norm = dp.replace(os.sep, '/')
        if '/src/test/' in norm or '/test/' in norm or '/generated-sources/' in norm:
            continue
        for f in files:
            if f.endswith('.java'):
                yield os.path.join(dp, f)


# ---- 自测:规则对 #752 修复前的代码必须能报,对修复后不得误报 ----
_PREFIX_752 = (
    'audit(Map.of(\n'
    '    "reason", command.reason(),\n'
    '    "errorMessage", exception.getMessage()));\n'
)
_FIXED_752 = (
    'audit(Map.of(\n'
    '    "reason", Objects.requireNonNullElse(command.reason(), ""),\n'
    '    "errorMessage", Objects.requireNonNullElse(exception.getMessage(), "x")));\n'
)
_SENSOR_PREFIX = 'return error("k", List.of("KAFKA_OFFSET", e.getMessage()));\n'
_SAFE_LOG = 'log.warn("err {}", e.getMessage());\n'


def _self_test():
    ok = True

    def expect(name, text, want):
        nonlocal ok
        got = len(scan_text(text)) > 0
        status = 'PASS' if got == want else 'FAIL'
        if got != want:
            ok = False
        print(f'  [{status}] {name}: flagged={got} expected={want}')

    print('[mapof-gate] self-test:')
    expect('#752 pre-fix Map.of(getMessage) 必须命中', _PREFIX_752, True)
    expect('#752 fixed requireNonNullElse 不得误报', _FIXED_752, False)
    expect('sensor List.of(getMessage) 必须命中', _SENSOR_PREFIX, True)
    expect('log.warn(getMessage) 非工厂不得误报', _SAFE_LOG, False)
    if ok:
        print('[mapof-gate] self-test PASS')
        return 0
    print('[mapof-gate] self-test FAIL —— 守护规则失效,禁止合入')
    return 1


def main(argv):
    if '--self-test' in argv:
        return _self_test()

    if os.environ.get('BATCH_CI_SKIP_MAPOF_GATE', '0') == '1':
        print('[mapof-gate] BATCH_CI_SKIP_MAPOF_GATE=1 —— 跳过(仅 dev 本地 debug 应使用)')
        return 0

    # 先自测:规则本身失效则直接失败(避免「静默匹配为空 = 假绿」)。
    if _self_test() != 0:
        return 1

    root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
    violations = []
    for path in _iter_prod_java(root):
        try:
            text = open(path, encoding='utf-8').read()
        except OSError:
            continue
        for line, kind, arg in scan_text(text):
            violations.append((os.path.relpath(path, root), line, kind, arg))

    if violations:
        print('\n[mapof-gate] 发现不可变工厂裸吞可空 getMessage() 的 NPE→500 隐患:')
        for rel, line, kind, arg in sorted(violations):
            print(f'  {rel}:{line}  {kind}.of(..., {arg}, ...)')
        print(
            '\n修法(照 #752 / 388e43e39):用 '
            'Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()) 兜底,\n'
            '或若该值本应非空则改为显式 Objects.requireNonNull + 消息(fail-fast)。'
        )
        print(f'共 {len(violations)} 处。')
        return 1

    print('[mapof-gate] OK —— 无 Map/List/Set.of 裸吞 getMessage() 隐患')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))

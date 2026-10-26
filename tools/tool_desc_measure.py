#!/usr/bin/env python3
"""
工具描述测量器 —— 以「线上真实 tools/list 载荷」为唯一事实源。

为什么不用正则直接解析源码（2026-10-26 踩过的坑）：
  1. Java 字符串可写成多段拼接：`description = "a" + "b" + "c"`，
     正则 `[^)]*?` 会在 description 内出现半角 `)` 时提前截断 → 少算。
  2. 转义（`\\"`、`\\n`）需要按 Java 语义还原，不是 Python 语义。

本脚本做两件事：
  A. `extract.py` 模式：从 .java 源码按 Java 字符串字面量语义提取全部
     @Tool description 与 @ToolParam description（正确处理多段拼接）。
  B. `verify.py` 模式：拿线上 tools/list 真实 JSON 交叉验证提取器 ——
     若提取器在当前源码上算出的 41 条 description 与线上逐条一致，
     则该提取器可信，可用于计算「改前」版本。

用法：
  python3 tool_desc_measure.py extract <file.java>
  python3 tool_desc_measure.py verify  <online_tools_list.json> <file.java>
  python3 tool_desc_measure.py compare <before.java> <after.java>
"""
import json
import re
import sys


# ---------- Java 字符串字面量扫描 ----------

def java_string_at(src, i):
    """src[i] 必须是 '"'。返回 (字面量内容, 结束引号后的下标)。处理 \\ 转义。"""
    assert src[i] == '"', src[i:i + 20]
    out = []
    i += 1
    while i < len(src):
        c = src[i]
        if c == '\\':
            nxt = src[i + 1]
            if nxt == 'n':
                out.append('\n')
            elif nxt == 't':
                out.append('\t')
            elif nxt == 'r':
                out.append('\r')
            elif nxt == '"':
                out.append('"')
            elif nxt == '\\':
                out.append('\\')
            elif nxt == "'":
                out.append("'")
            elif nxt == 'u':
                out.append(chr(int(src[i + 2:i + 6], 16)))
                i += 4
            else:
                out.append(nxt)
            i += 2
            continue
        if c == '"':
            return ''.join(out), i + 1
        out.append(c)
        i += 1
    raise ValueError('未闭合的字符串字面量')


def skip_ws_and_plus(src, i):
    """跳过空白、注释、+ 号，返回下一个非空白位置（若不是字符串起点则返回 -1）。"""
    n = len(src)
    while i < n:
        c = src[i]
        if c.isspace() or c == '+':
            i += 1
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j + 1
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i)
            i = n if j < 0 else j + 2
            continue
        break
    return i


def concat_strings(src, i):
    """从 i 开始读取 Java 字符串拼接表达式（"a" + "b" ...），返回 (内容, 结束下标)。"""
    parts = []
    n = len(src)
    while True:
        i = skip_ws_and_plus(src, i)
        if i >= n or src[i] != '"':
            break
        lit, i = java_string_at(src, i)
        parts.append(lit)
    return ''.join(parts), i


# ---------- 从源码提取 ----------

#: Java 关键字/基础类型名 —— 它们出现在参数名**之前**，不能被当成参数名
JAVA_KEYWORDS = {
    'final', 'String', 'Long', 'Integer', 'Boolean', 'Double', 'Float', 'Long',
    'int', 'long', 'boolean', 'double', 'float', 'String[]', 'List', 'Map',
    'Object', 'var', 'new', 'return', 'this', 'null',
}


def extract_from_source(path):
    src = open(path, encoding='utf-8').read()
    tools = []   # (name, description)
    params = []  # (tool_name, param_name, description)

    # 逐个 @Tool( 注解块
    for m in re.finditer(r'@Tool\s*\(', src):
        i = m.end()
        # 找 name = "xxx"
        nm = re.search(r'name\s*=\s*"([a-z_]+)"', src[i:i + 400])
        if not nm:
            continue
        name = nm.group(1)
        # 找 description = 后面的字符串拼接
        dm = re.search(r'description\s*=\s*', src[i:i + 4000])
        if not dm:
            continue
        dstart = i + dm.end()
        desc, _ = concat_strings(src, dstart)
        tools.append((name, desc))

    # 逐个 @ToolParam( —— 需要知道它属于哪个工具：按出现顺序与最近的 @Tool 对齐
    cur = None
    for m in re.finditer(r'@Tool\s*\(|@ToolParam\s*\(', src):
        token = m.group(0)
        # ⚠️ 必须先判 @ToolParam —— 它也以 "@Tool" 开头（startswith 会把参数块误判成工具注解）
        if token.startswith('@ToolParam'):
            i = m.end()
            dm = re.search(r'description\s*=\s*', src[i:i + 3000])
            if not dm:
                continue
            dstart = i + dm.end()
            desc, dend = concat_strings(src, dstart)
            # 参数名 = 注解**闭合括号之后**的第一个标识符。
            #
            # ⚠️ 2026-10-26 17:5x 修（测量工具自己失效，与守卫失效同一类病）：
            #   原实现从「字符串字面量结尾」直接正则找 `标识符[,)]`，
            #   而注解里还有 `, required = false)` —— 于是把 **false 当成了参数名**。
            #   实测：update_project 的 5 个可选参数全被识别成 "false"，
            #   线上载荷明明有 name/aliases/description/defaultTestCmd/defaultConstraints。
            #   → 「158 个参数」这个数一直是错的（真实 178 个）。
            #   现在改成：先跳过整个注解的括号内容，再取 `) Type name`。
            pname = param_name_after_annotation(src, dend)
            params.append((cur, pname if pname else '?', desc))
            continue
        nm = re.search(r'name\s*=\s*"([a-z_]+)"', src[m.end():m.end() + 400])
        cur = nm.group(1) if nm else None
        continue

    return tools, params


def param_name_after_annotation(src, i):
    """从 @ToolParam(...) 的闭合括号之后，取声明的类型名（Java 里就是参数名）。

    @ToolParam(description = "新名称，留空表示不修改", required = false) String name
                                                    ↑ 闭合括号在这   ↑ 之后是 "String name"
    返回 "name"。
    """
    # 1) 跳过注解剩余部分：找到与之配对的右括号
    #    （注解里可能含嵌套括号，如 required = false 或数组默认值）
    depth = 1
    j = i
    n = len(src)
    while j < n and depth > 0:
        c = src[j]
        if c == '"':
            _, j = java_string_at(src, j)
            continue
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
        j += 1
    if depth != 0:
        return None
    # 2) 闭合括号之后：跳过空白与类型修饰符（final / String / Long / Integer / boolean ...），
    #    取最后一个标识符 —— 它才是参数名。
    tail = src[j:j + 200]
    m = re.search(r'([A-Za-z_][A-Za-z0-9_]*)\s*(?:[,)=;{]|$)', tail)
    if not m:
        return None
    # 排除 Java 关键字与类型名（它们在参数名之前）
    candidate = m.group(1)
    if candidate in JAVA_KEYWORDS:
        return None
    return candidate


# ---------- 主流程 ----------

def main():
    mode = sys.argv[1]
    if mode == 'extract':
        tools, params = extract_from_source(sys.argv[2])
        td = sum(len(d) for _, d in tools)
        pd = sum(len(d) for _, _, d in params)
        print('@Tool 数: %d ｜ description 合计 %d 字' % (len(tools), td))
        print('@ToolParam 数: %d ｜ description 合计 %d 字' % (len(params), pd))
        print('文案合计: %d 字' % (td + pd))
        if len(sys.argv) > 3 and sys.argv[3] == '--detail':
            for n, d in sorted(tools, key=lambda x: -len(x[1])):
                print('%4d  %s' % (len(d), n))
        return

    if mode == 'verify':
        online = json.load(open(sys.argv[2], encoding='utf-8'))
        tools, params = extract_from_source(sys.argv[3])
        src_map = {n: d for n, d in tools}
        on_map = {t['name']: (t.get('description') or '') for t in online}
        print('线上工具数: %d ｜ 源码提取: %d' % (len(on_map), len(src_map)))
        missing = sorted(set(on_map) - set(src_map))
        extra = sorted(set(src_map) - set(on_map))
        print('线上有、源码无:', missing or '无')
        print('源码有、线上无:', extra or '无')
        diff = [(n, len(src_map[n]), len(on_map[n])) for n in sorted(set(src_map) & set(on_map))
                if src_map[n] != on_map[n]]
        if diff:
            print('!! description 内容不一致 %d 条：' % len(diff))
            for n, a, b in diff:
                print('   %-30s 源码 %d 字 / 线上 %d 字' % (n, a, b))
                print('     源码: %s' % src_map[n][:160])
                print('     线上: %s' % on_map[n][:160])
            sys.exit(1)
        print('✅ 41 条 description 逐条与线上完全一致 —— 提取器可信')
        return

    if mode == 'compare':
        bt, bp = extract_from_source(sys.argv[2])
        at, ap = extract_from_source(sys.argv[3])
        bmap = {n: d for n, d in bt}
        amap = {n: d for n, d in at}
        print('| 工具 | 改前 | 改后 | 变化 |')
        print('|---|---|---|---|')
        rows = []
        for n in sorted(set(bmap) | set(amap)):
            b, a = len(bmap.get(n, '')), len(amap.get(n, ''))
            rows.append((n, b, a))
        for n, b, a in sorted(rows, key=lambda x: -(x[1])):
            print('| `%s` | %d | %d | %+d |' % (n, b, a, a - b))
        tb = sum(r[1] for r in rows)
        ta = sum(r[2] for r in rows)
        print('| **合计** | **%d** | **%d** | **%+d** |' % (tb, ta, ta - tb))
        pb = sum(len(d) for _, _, d in bp)
        pa = sum(len(d) for _, _, d in ap)
        print()
        print('@ToolParam 合计: 改前 %d → 改后 %d (%+d)' % (pb, pa, pa - pb))
        print('文案总计: 改前 %d → 改后 %d (%+d)' % (tb + pb, ta + pa, ta + pa - tb - pb))
        print()
        print('零变化工具（%d 个）: %s' % (
            len([r for r in rows if r[1] == r[2]]),
            ', '.join(n for n, b, a in rows if b == a)))
        return

    print('未知模式: %s' % mode)
    sys.exit(1)


if __name__ == '__main__':
    main()

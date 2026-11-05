#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""源码文本守卫体检：找出「断言串刚好也在注释里」的假绿风险（#1290 C59）。

背景
----
有一类测试不测行为，而是读 `src/main/...java` 的源码文本，断言
`src.contains("某句话")`。它防的是「文案被悄悄改掉」，但有个致命缺陷：

    如果这句话同时出现在代码注释里，改掉真正的文案、注释没删，
    断言依然为真 —— 测试全绿，功能已坏。

2026-11-06 真实踩到一次：`SearchScopeGuardTest` 断言
`src.contains("不含项目内进度流水")`，而这句旧文案恰好还留在新写的注释里，
文案改了、测试不红（#1290 D39）。

本脚本做三件事
--------------
1. 找出所有读源码的测试（`Files.readString` / `readAllBytes`）；
2. 抽出它们断言里的字符串字面量（`contains("...")` / `indexOf("...")`）；
3. 逐个回查目标源码，判断该字面量是只出现在注释里、还是也出现在代码里。

判据（保守）
------------
把目标源码按行分类：`//`、`*`、`/*`、`*/` 开头的算注释行，其余算代码行。
字面量只出现在注释行 → **高危（假绿）**；
注释行与代码行都有   → **中危（注释能兜住）**；
只在代码行           → 安全。

用法
----
    python3 tools/guard_audit.py              # 摘要 + 高危清单
    python3 tools/guard_audit.py --all        # 连中危、安全也列
    python3 tools/guard_audit.py --file XXX   # 只看某个测试
"""
import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_DIR = os.path.join(ROOT, 'src', 'test', 'java')

READ_SRC = re.compile(r'Files\.readString|Files\.readAllBytes|new\s+String\s*\(\s*Files')
# contains("x") / indexOf("x") / startsWith("x")，捕获双引号里的内容
ASSERT_LIT = re.compile(r'(?:contains|indexOf|startsWith|endsWith)\s*\(\s*"((?:[^"\\]|\\.)*)"')
# 断言极性：只有 assertTrue 型才会「字面量留在注释里 → 改文案不红」；
# assertFalse 型遇到同名注释反而会立刻红（不属假绿）。
POLARITY = re.compile(r'(assertTrue|assertFalse|assertNull|assertNotNull)\s*\(')
# 只认「对源码文本做断言」的调用：<变量或 source()>.contains("...")
# 同一文件里对运行时字符串（label / reason / msg）的断言不算源码守卫 ——
# 第一版没做区分，把 AliasMatcherTest 里 reason.contains("结构性拒绝") 误报成高危。
SRC_CALL = re.compile(
    r'(?P<recv>[A-Za-z_]\w*(?:\(\))?)\s*\.\s*(?:contains|indexOf|startsWith|endsWith)\s*\(\s*"(?P<lit>(?:[^"\\]|\\.)*)"')
# 被判为「装源码文本」的变量/方法：String x = Files.readString(...) 或 static String source()
SRC_VAR = re.compile(r'String\s+(\w+)\s*=[^;]*(?:Files\.readString|Files\.readAllBytes|readAllBytes)')
SRC_HELPER = re.compile(r'String\s+(\w+)\s*\([^)]*\)\s*(?:throws[^{]+)?\{')
# 测试里指向被测源码的路径
SRC_PATH = re.compile(r'Path\.of\s*\(\s*"((?:[^"\\]|\\.)*\.java)"')


def iter_tests():
    for dirpath, _dirs, files in os.walk(TEST_DIR):
        for fn in files:
            if fn.endswith('.java'):
                yield os.path.join(dirpath, fn)


def read(path):
    with open(path, encoding='utf-8', errors='replace') as f:
        return f.read()


def split_comment_and_code(text):
    """返回 (注释行集合文本, 代码行集合文本)，按行粗分，够用且不误判。"""
    comment, code = [], []
    in_block = False
    for line in text.splitlines():
        s = line.strip()
        if in_block:
            comment.append(line)
            if '*/' in s:
                in_block = False
            continue
        if s.startswith('/*'):
            comment.append(line)
            if '*/' not in s:
                in_block = True
            continue
        if s.startswith('//') or s.startswith('*'):
            comment.append(line)
            continue
        code.append(line)
    return '\n'.join(comment), '\n'.join(code)


def rel(p):
    return os.path.relpath(p, ROOT)


# 噪声：注释标记本身、纯数字、长度过短、纯符号。它们进不了「文案守卫」的定义，
# 留着只会把 36 条高危冲成一片噪声，让人放弃看这份清单。
NOISE = {'*/', '/*', '//', '\\n', '\\t', ' ', '(', ')', '<', '>', '=', '-'}


def is_noise(lit):
    if lit in NOISE or len(lit) < 3:
        return True
    if lit.isdigit():
        return True
    # 去掉符号后若没剩任何字母/汉字，就是纯符号串
    if not re.search(r'[0-9A-Za-z\u4e00-\u9fff]', lit):
        return True
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--all', action='store_true', help='连中危与安全项一起列')
    ap.add_argument('--file', default='', help='只分析文件名包含该串的测试')
    a = ap.parse_args()

    rows = []          # (测试文件, 目标源码, 字面量, 判定)
    tests = 0
    for tf in sorted(iter_tests()):
        src = read(tf)
        if not READ_SRC.search(src):
            continue
        if a.file and a.file not in tf:
            continue
        tests += 1
        targets = [m.group(1) for m in SRC_PATH.finditer(src)]
        targets = [t for t in targets if t.endswith('.java')]
        if not targets:
            rows.append((rel(tf), '(未定位到 Path.of)', '', '无法判定'))
            continue
        bodies = []
        for t in targets:
            p = os.path.join(ROOT, t)
            if os.path.exists(p):
                bodies.append((t, split_comment_and_code(read(p))))
        if not bodies:
            rows.append((rel(tf), '(目标文件不存在)', '', '无法判定'))
            continue
        # 有些测试在断言前先 stripComments（ScanReceiptSelfProofTest 就是），
        # 这类守卫天然免疫「注释兜住」，不必也不该报高危 —— 第一版漏了这个判断，
        # 把「已用满」误报成高危（它在 SimilarityService 代码里，只是注释里也有一份）。
        strips = 'stripComments' in src
        # 哪些变量/方法名装的是「源码文本」——只对它们做的断言才算源码守卫。
        # 变量名千奇百怪（src / java / body / s），不能靠名字猜，
        # 只认「它的值来自 Files.readString/readAllBytes」这个事实。
        src_names = set(SRC_VAR.findall(src))
        for hm in SRC_HELPER.finditer(src):
            body = src[hm.end():hm.end() + 400]
            if 'Files.readString' in body or 'readAllBytes' in body:
                src_names.add(hm.group(1))
        for m in SRC_CALL.finditer(src):
            recv = m.group('recv')
            if recv not in src_names and recv.replace('()', '') not in src_names:
                continue          # 对运行时字符串断言，不是源码守卫
            lit = m.group('lit')
            if is_noise(lit):
                continue
            # 往前找最近的断言调用，判断是 assertTrue 还是 assertFalse
            head = src[max(0, m.start() - 200):m.start()]
            pols = POLARITY.findall(head)
            positive = (not pols) or pols[-1] == 'assertTrue'
            if not positive:
                continue      # assertFalse 型不属假绿，跳过
            verdict = '安全'
            hit_targets = []
            for t, (cmt, code) in bodies:
                in_c, in_code = lit in cmt, lit in code
                if not (in_c or in_code):
                    continue
                hit_targets.append(t.split('/')[-1])
                if in_c and not in_code:
                    if strips:
                        # 注释已被剥掉，assertTrue 会直接红 —— 不属假绿
                        if verdict == '安全':
                            verdict = '会红（不属假绿）'
                    else:
                        verdict = '高危'
                elif in_c and in_code:
                    if verdict == '安全':
                        verdict = '中危'
            if not hit_targets:
                continue      # 断言串在两个目标里都不出现，与本脚本判断无关
            rows.append((rel(tf), ','.join(sorted(set(hit_targets))), lit, verdict))

    order = {'高危': 0, '中危': 1, '无法判定': 2, '安全': 3}
    rows.sort(key=lambda r: (order.get(r[3], 9), r[0]))

    high = [r for r in rows if r[3] == '高危']
    mid = [r for r in rows if r[3] == '中危']
    print('读源码的测试文件：%d 个' % tests)
    print('能定位到断言字面量的：%d 条（高危 %d / 中危 %d）' % (len(rows), len(high), len(mid)))
    print()

    def dump(title, items):
        if not items:
            return
        print('=== %s ===' % title)
        for tf, target, lit, _v in items:
            short = tf.replace('src/test/java/com/exphub/', '')
            print('  %-46s -> %-22s  "%s"' % (short, target, lit))
        print()

    dump('高危：字面量只在注释里，改文案不会红', high)
    dump('中危：注释与代码都有，注释能兜住', mid)
    if a.all:
        dump('安全：只在代码里', [r for r in rows if r[3] == '安全'])
        dump('无法判定', [r for r in rows if r[3] == '无法判定'])
    else:
        print('（--all 可看中危/安全明细）')
    return 0


if __name__ == '__main__':
    sys.exit(main())

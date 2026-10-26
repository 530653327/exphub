#!/usr/bin/env python3
"""
用守卫自己的词表扫**线上真实 tools/list 载荷** —— #674 口径 B 的最终验收。

为什么必须单独有这个脚本（2026-10-26 16:0x 白泽实测揪出的教训）：
  单测 `ToolDescriptionRedundancyTest` 读的是**源码注解**，
  它能证明「源码里没有」，但**证不了「发给模型的载荷里没有」**。
  第一版守卫就是这么漏的：单测全绿，线上真实载荷里还留着 2 处教学话术。

所以验收必须是两条腿：
  ① 单测：源码层（快、离线、能进 CI）
  ② 本脚本：线上载荷层（唯一事实源）

本脚本还做**反向验证**：把守卫声明「必须抓到」的样本喂进去，必须被抓到；
把必要信息喂进去，必须放行。规则本身不可信的话，绿灯没有意义。

用法：
  # 1) 先抓线上载荷
  MCP_KEY=xxx MCP_URL=https://cloudim.club/exphub/mcp/sse python3 tools/fetch_tools_list.py > /tmp/online_tools.json
  # 2) 再扫
  python3 tools/scan_online_redundancy.py /tmp/online_tools.json
"""
import json
import re
import sys
import os

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GUARD = os.path.join(REPO, 'src/test/java/com/exphub/mcp/ToolDescriptionRedundancyTest.java')


def unescape_java(s):
    """Java 源码字符串字面量 → 真实字符串。

    Java 正则里 `\\s` 才是「一个空白符」；源码里写的是 `"建议(?!\\s*...)"`，
    抽出来是 `建议(?!\\s*...)`（两个字符：反斜杠+s），Python 里要还原成 `\\s`。
    """
    return s.replace('\\\\', '\x00').replace('\\', '').replace('\x00', '\\')


def load_rules():
    src = open(GUARD, encoding='utf-8').read()
    block = src[src.index('private static final String[][] RULES'):]
    block = block[:block.index('};')]
    raw = re.findall(r'\{"((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)"\}', block)
    return [(unescape_java(p), unescape_java(w)) for p, w in raw]


def load_must_catch():
    src = open(GUARD, encoding='utf-8').read()
    block = src[src.index('private static final String[] MUST_CATCH'):]
    block = block[:block.index('};')]
    return re.findall(r'"((?:[^"\\]|\\.)*)"', block)


def scan(rules, text):
    out = []
    for p, why in rules:
        m = re.search(p, text)
        if m:
            out.append((p, why, m.start(), m.end()))
    return out


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    payload = json.load(open(sys.argv[1], encoding='utf-8'))
    tools = payload['tools'] if isinstance(payload, dict) and 'tools' in payload else payload

    rules = load_rules()
    must_catch = load_must_catch()

    print('=== ① 反向验证：守卫声明要抓的样本，必须抓到 ===')
    bad = 0
    for sample in must_catch:
        hits = scan(rules, sample)
        if hits:
            print('  ✅ 抓到  规则 %s' % hits[0][0])
        else:
            print('  ❌ 漏掉  %s' % sample[:70])
            bad += 1

    print()
    print('=== ② 线上真实载荷扫描（41 工具 / 全部参数）===')
    hits = []
    nparams = 0
    for t in tools:
        texts = [('description', t.get('description') or '')]
        props = (t.get('inputSchema') or {}).get('properties') or {}
        nparams += len(props)
        for k, v in props.items():
            texts.append(('param:' + k, v.get('description') or ''))
        for kind, text in texts:
            for p, why, s, e in scan(rules, text):
                hits.append((t['name'], kind, p, why, text[max(0, s - 25):e + 25]))

    if hits:
        for name, kind, p, why, ctx in hits:
            print('  ❌ %s [%s]' % (name, kind))
            print('     规则 %s → %s' % (p, why))
            print('     …%s…' % ctx)
        print()
        print('命中 %d 处 —— 未清完' % len(hits))
        sys.exit(1)

    print('  ✅ 命中 0 处 —— 线上载荷（%d 工具 / %d 参数）里已无可压缩冗余' % (len(tools), nparams))
    if bad:
        print()
        print('⚠️ 但反向验证有 %d 条漏掉 —— 守卫本身失效，这次绿灯不算数' % bad)
        sys.exit(1)
    print('  ✅ 反向验证通过（规则确实抓得住它声明要抓的东西）')
    print()
    print('口径说明：线上 description 合计 %d 字 / 参数 description 合计 %d 字' % (
        sum(len(t.get('description') or '') for t in tools),
        sum(len(v.get('description') or '') for t in tools
            for v in ((t.get('inputSchema') or {}).get('properties') or {}).values())))


if __name__ == '__main__':
    main()

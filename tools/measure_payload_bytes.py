#!/usr/bin/env python3
"""
工具描述**字节口径**测量器 —— 同一口径下算「改前 vs 改后」。

为什么需要这个脚本（2026-10-26 17:4x 阁主指出）：
  连续三轮回执里只给了「改后 34023 字节」，**没有给改前的同口径值**，
  导致那个数字是**孤儿** —— 没有基线就不知道省了多少，
  也没法判断是不是口径又变了一次（历史上 27268 / 23557 / 23340 / 34023
  四个数就是四种序列化方式混出来的）。

口径定死（唯一事实源 = 线上 tools/list 的 compact JSON）：
  1. **载荷结构**：拿线上真实的 tools/list JSON，把每个工具的
     description / 每个参数的 description 换成待测版本，其余字段
     （name / inputSchema 的 type / required 等）**原样保留**。
     → 这样字节差 = 纯文案差，不会把结构变动混进来。
  2. **序列化**：`json.dumps(payload, ensure_ascii=False, separators=(',', ':'))`
     —— compact，无空格，中文不转义。**改前改后必须用同一行代码**。
  3. **编码**：UTF-8，取 `len(s.encode('utf-8'))`。
     ⚠️ 中文是 3 字节，按字符数算会低估；按字节算才是「实际传输量」。

用法：
  # ① 先拉线上载荷（唯一事实源）
  python3 tools/measure_payload_bytes.py dump <online_tools.json>

  # ② 用改前源码算改前字节（需要先 git show 到临时文件）
  python3 tools/measure_payload_bytes.py bytes <online_tools.json> <before.java> [<after.java>]

  # ③ 守卫用：断言线上载荷与源码提取一致（防提取器漂移）
  python3 tools/measure_payload_bytes.py verify <online_tools.json> <source.java>
"""
import json
import re
import subprocess
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tool_desc_measure import extract_from_source  # noqa: E402


def compact_bytes(payload):
    """口径唯一入口：改前改后都必须走这里，不许各写各的。"""
    s = json.dumps(payload, ensure_ascii=False, separators=(',', ':'))
    return len(s.encode('utf-8'))


def rebuild(online, source_path):
    """用源码里的文案替换线上载荷的文案，其余字段原样保留。"""
    tools, params = extract_from_source(source_path)
    tmap = {name: desc for name, desc in tools}
    pmap = {}
    for tname, pname, desc in params:
        pmap.setdefault(tname, {})[pname] = desc

    out = json.loads(json.dumps(online))   # 深拷贝，不改原文件
    changed_tool = 0
    changed_param = 0
    missing_tool = []
    for t in out:
        name = t.get('name')
        if name in tmap:
            if t.get('description') != tmap[name]:
                changed_tool += 1
            t['description'] = tmap[name]
        else:
            missing_tool.append(name)
        props = (t.get('inputSchema') or {}).get('properties') or {}
        for pname, pv in props.items():
            if name in pmap and pname in pmap[name]:
                if pv.get('description') != pmap[name][pname]:
                    changed_param += 1
                pv['description'] = pmap[name][pname]
    return out, changed_tool, changed_param, missing_tool


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    mode = sys.argv[1]

    if mode == 'dump':
        # 拉线上载荷并打印口径基线
        path = sys.argv[2]
        online = json.load(open(path, encoding='utf-8'))
        tools = online if isinstance(online, list) else online.get('result', {}).get('tools', [])
        print(f"工具数: {len(tools)}")
        print(f"线上载荷字节（compact/UTF-8）: {compact_bytes(tools)}")
        desc_sum = sum(len(t.get('description', '')) for t in tools)
        p_sum = 0
        for t in tools:
            for pv in ((t.get('inputSchema') or {}).get('properties') or {}).values():
                p_sum += len((pv or {}).get('description', ''))
        print(f"线上 description 字符合计: {desc_sum}")
        print(f"线上 参数 description 字符合计: {p_sum}")
        print(f"线上文案字符合计: {desc_sum + p_sum}")
        return

    if mode == 'bytes':
        online = json.load(open(sys.argv[2], encoding='utf-8'))
        tools = online if isinstance(online, list) else online.get('result', {}).get('tools', [])
        before_path = sys.argv[3]

        payload, ct, cp, missing = rebuild(tools, before_path)
        before_bytes = compact_bytes(payload)
        print(f"=== 改前（{before_path}）===")
        print(f"工具数: {len(payload)}")
        print(f"载荷字节（compact/UTF-8）: {before_bytes}")
        ds = sum(len(t.get('description', '')) for t in payload)
        ps = 0
        for t in payload:
            for pv in ((t.get('inputSchema') or {}).get('properties') or {}).values():
                ps += len((pv or {}).get('description', ''))
        print(f"description 字符合计: {ds}")
        print(f"参数 description 字符合计: {ps}")
        print(f"文案字符合计: {ds + ps}")

        if len(sys.argv) > 4:
            after_path = sys.argv[4]
            apayload, act, acp, amissing = rebuild(tools, after_path)
            after_bytes = compact_bytes(apayload)
            ads = sum(len(t.get('description', '')) for t in apayload)
            aps = 0
            for t in apayload:
                for pv in ((t.get('inputSchema') or {}).get('properties') or {}).values():
                    aps += len((pv or {}).get('description', ''))
            print(f"\n=== 改后（{after_path}）===")
            print(f"载荷字节（compact/UTF-8）: {after_bytes}")
            print(f"description 字符合计: {ads}")
            print(f"参数 description 字符合计: {aps}")
            print(f"文案字符合计: {ads + aps}")
            print(f"\n=== 变化 ===")
            print(f"载荷字节: {before_bytes} → {after_bytes} "
                  f"({after_bytes - before_bytes:+d}, "
                  f"{(after_bytes - before_bytes) * 100.0 / before_bytes:+.1f}%)")
            print(f"文案字符: {ds + ps} → {ads + aps} ({ads + aps - (ds + ps):+d})")
        return

    if mode == 'verify':
        # 断言源码提取与线上载荷逐条一致 —— 提取器漂移时立刻报红
        online = json.load(open(sys.argv[2], encoding='utf-8'))
        tools = online if isinstance(online, list) else online.get('result', {}).get('tools', [])
        st, sp = extract_from_source(sys.argv[3])
        tmap = {n: d for n, d in st}
        pmap = {}
        for tn, pn, pd in sp:
            pmap.setdefault(tn, {})[pn] = pd

        bad = []
        for t in tools:
            n = t.get('name')
            if n not in tmap:
                bad.append(f"线上有工具 {n} 但源码里没提取到")
                continue
            if t.get('description') != tmap[n]:
                bad.append(f"{n}.description 不一致")
            for pn, pv in ((t.get('inputSchema') or {}).get('properties') or {}).items():
                if n in pmap and pn in pmap[n]:
                    if (pv or {}).get('description') != pmap[n][pn]:
                        bad.append(f"{n}.{pn} 不一致")
                else:
                    bad.append(f"{n}.{pn} 源码里没提取到")
        if bad:
            print("❌ 提取器与线上载荷不一致（提取器可能已漂移）:")
            for b in bad[:20]:
                print("   -", b)
            sys.exit(1)
        print(f"✅ 提取器与线上载荷逐条一致（{len(tools)} 个工具）")
        print(f"   载荷字节（compact/UTF-8）: {compact_bytes(tools)}")
        return

    print("未知模式:", mode)
    sys.exit(1)


if __name__ == '__main__':
    main()

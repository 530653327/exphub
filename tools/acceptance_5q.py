#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""5 组验收基线：每组一个唯一正解，看正解在不在前 3（#1290 C77）。

为什么单独一个脚本
------------------
调打分公式时最容易犯的错，是拿目标文档当训练集：为了让 #95 进前 3 调参数，
结果别的 query 全变差。评判标准必须固定成「5 组里正解进前 3 的数量」，
一次跑完、前后对比。

顺带回答两个常被问到的数
------------------------
1. **C75**：`project_agents` 有多少条出现在前 20（决定这类文档要不要整体降权）。
2. **C78**：目标文档与其前一名差多少分（判断是「微调能救」还是「结构问题」）。

正解可能在「过程记录」里
------------------------
主排名排除 `project_progress` / `todo_list` 后，正解若属于这两类，会出现在回执末尾的
「过程记录里也命中」补位段。所以本脚本对这类正解按「补位段前 3 命中」判定通过，
并在结果里标注 `补位`，免得把「主排名没进前 3」误判成失败。

用法（服务器上）
----------------
    export MCPKEY=<某个可读全局库的 api_key>
    python3 tools/acceptance_5q.py
    python3 tools/acceptance_5q.py --top 20     # C75 的统计窗口
"""
import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import score_distribution as sd           # 复用切词/候选集/打分，口径必须与线上同源

# 被主排名排除、只能走补位段的类型
EXCLUDED_TYPES = {'project_progress', 'todo_list'}

# (query, 正解 ID 列表, 一句话说明)
CASES = [
    ('代码怎么发布到线上服务器 部署', [95], 'ExpHub 部署手册'),
    ('昨天部署踩了什么坑', [1236, 1274], '施工回执（过程记录，走补位）'),
    ('scan_similar_docs 报错', [712], '工具的报错与复发取证'),
    ('积分兑换提现异常', [361], '提交错仓库那次事故'),
    ('AGENTS.md 怎么分片', [233], 'AGENTS 分片规范'),
]


_KEEP = []          # 保活旧的 stdout 包装器，否则它被 GC 时会把底层 buffer 关掉


def _utf8_stdout():
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        # Python 3.6 没有 reconfigure，只能换包装器；换完必须保留旧对象的引用，
        # 否则旧 TextIOWrapper 回收时 close 掉同一个 buffer → "I/O operation on closed file"
        import io
        _KEEP.append(sys.stdout)
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')


def main():
    _utf8_stdout()

    ap = argparse.ArgumentParser()
    ap.add_argument('--top', type=int, default=20, help='统计窗口（C75 用），默认 20')
    a = ap.parse_args()

    key = os.environ.get('MCP_KEY') or os.environ.get('MCPKEY') or ''
    if not key:
        print('缺 MCPKEY（要能读全局库的 api_key）')
        return 2

    print('窗口: 前 %d 名｜判定: 正解进前 3 算通过（过程记录类看补位段）\n' % a.top)
    header = '| # | query | 正解 | 主排名位次 | 补位位次 | 结论 |'
    print(header)
    print('|---|---|---|---|---|---|')

    passed = 0
    pa_hits = []          # (query, docId, 位次)
    for idx, (q, expect, note) in enumerate(CASES, 1):
        rows, variants, _orq, _ck = sd.fetch_candidates(q, key)
        if not rows:
            print('| %d | %s | %s | 候选为空 | - | ❌ |' % (idx, q, expect))
            continue
        for d in rows:
            d['p'] = sd.parts(d, variants)
        rows.sort(key=lambda x: -x['p']['total'])

        # 主排名：排除过程记录/待办后重新编号
        main_rows = [d for d in rows if (d.get('ttype') or '') not in EXCLUDED_TYPES]
        # 补位段：只留被排除的两类，最多 3 条（与 renderProcessHits 同口径）
        proc_rows = [d for d in rows if (d.get('ttype') or '') in EXCLUDED_TYPES][:3]

        main_ids = [d['id'] for d in main_rows]
        proc_ids = [d['id'] for d in proc_rows]

        main_rank = next((i + 1 for i, x in enumerate(main_ids) if x in expect), None)
        proc_rank = next((i + 1 for i, x in enumerate(proc_ids) if x in expect), None)

        if main_rank is not None and main_rank <= 3:
            verdict, passed = '✅ 主排名前3', passed + 1
        elif main_rank is None and proc_rank is not None:
            verdict, passed = '✅ 补位命中', passed + 1
        else:
            verdict = '❌'
        print('| %d | %s | %s | %s | %s | %s |'
              % (idx, q, '/'.join('#%d' % x for x in expect),
                 main_rank if main_rank else '—',
                 proc_rank if proc_rank else '—', verdict))

        # C75：project_agents 在前 N 名里的分布
        for i, d in enumerate(rows[:a.top], 1):
            if (d.get('ttype') or '') == 'project_agents':
                pa_hits.append((q, d['id'], i, d['p']['total']))

        # C78：目标文档与前一名差多少分
        target = expect[0]
        for i, d in enumerate(main_rows):
            if d['id'] == target:
                prev = main_rows[i - 1] if i > 0 else None
                gap = ('%.4f' % (prev['p']['total'] - d['p']['total'])) if prev else '—'
                print('|   | ↳ 分数 | %.4f | 前一名 | %s | 差 %s |'
                      % (d['p']['total'], '#' + str(prev['id']) if prev else '—', gap))
                break

    print()
    print('**5 组里通过 %d 组**（判据：正解进前 3，或属于过程记录且进补位段前 3）' % passed)
    print()
    if pa_hits:
        print('=== C75 · project_agents 出现在前 %d 名的记录 ===' % a.top)
        for q, did, rank, score in pa_hits:
            print('  #%-5d 第 %2d 位 (%.4f)  query=%s' % (did, rank, score, q))
        print('合计 %d 条次、去重 %d 篇' % (len(pa_hits), len({h[1] for h in pa_hits})))
    else:
        print('=== C75 · 前 %d 名里没有 project_agents ===' % a.top)
    return 0


if __name__ == '__main__':
    sys.exit(main())

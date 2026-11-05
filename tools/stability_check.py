#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""换位稳定性：同一批 query、同一参数跑 3 次，报位次波动（#1290 C85-2）。

为什么要有这一步
----------------
阁主 2026-11-06 撤回了「差 0.05 算微调」那个拍出来的阈值，改判据为**换位稳定性**：
若同一篇文档在同参数下多次运行位次会变，那么「升了几位」根本不算数 ——
分不清是改动生效还是抖动。所以先测稳定性，再谈调参。

测什么（三层，逐层排除）
------------------------
1. **候选池顺序**：直接跑线上那条 SQL 三次，比对 ID 序列。
   若 SQL 层就不稳定，后面所有排名都建在流沙上。
2. **打分重排**：拿同一份候选集在本地按线上公式排三次，比对序列。
   这一层理论上应完全确定 —— 若不稳定，说明打分里有随机源（时间/哈希序）。
3. **并列组**：统计「MySQL 相关度 + call_count」完全相同的文档组。
   ORDER BY 没有唯一键兜底时，这些组的相对顺序由存储引擎决定，可能随机。

用法（服务器上）
----------------
    export MCPKEY=<api_key>
    python3 tools/stability_check.py
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import score_distribution as sd

QUERIES = [
    '代码怎么发布到线上服务器 部署',
    '昨天部署踩了什么坑',
    '积分兑换提现异常',
    'AGENTS.md 怎么分片',
]
RUNS = 3


def sql(query):
    """直接走 score_distribution 的同一个连接方式，避免两套口径。"""
    out, err = sd.sql(query)
    if err.strip():
        raise RuntimeError(err.strip()[:300])
    return [line.split('\t') for line in out.split('\n') if line.strip()]


_KEEP = []          # 保活旧 stdout 包装器，否则它被 GC 时会把底层 buffer 关掉


def _utf8_stdout():
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        # 服务器是 Python 3.6（无 reconfigure）。换包装器时必须保留旧对象引用，
        # 否则旧 TextIOWrapper 回收时 close 掉同一个 buffer → "I/O operation on closed file"
        import io
        _KEEP.append(sys.stdout)
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')


def main():
    _utf8_stdout()

    key = os.environ.get('MCP_KEY') or os.environ.get('MCPKEY') or ''
    if not key:
        print('缺 MCPKEY')
        return 2

    print('每道 query 跑 %d 次，比对「候选池顺序」与「重排顺序」\n' % RUNS)
    print('| query | 候选池 3 次是否一致 | 重排 3 次是否一致 | 首次第 3-6 名 |')
    print('|---|---|---|---|')

    unstable_pool = 0
    for q in QUERIES:
        pool_seqs, rank_seqs, head = [], [], []
        rows_cache = None
        for _ in range(RUNS):
            rows, variants, _o, _c = sd.fetch_candidates(q, key)
            pool_seqs.append(tuple(d['id'] for d in rows))
            for d in rows:
                d['p'] = sd.parts(d, variants)
            ranked = sorted(rows, key=lambda x: -x['p']['total'])
            rank_seqs.append(tuple(d['id'] for d in ranked))
            if rows_cache is None:
                rows_cache = ranked
                head = ['#%d' % d['id'] for d in ranked[2:6]]

        same_pool = len(set(pool_seqs)) == 1
        same_rank = len(set(rank_seqs)) == 1
        if not same_pool:
            unstable_pool += 1
            # 找出第一处不同的位置
            a, b = pool_seqs[0], pool_seqs[1]
            diff_at = next((i for i in range(min(len(a), len(b))) if a[i] != b[i]), None)
            print('  ⚠️ %s：候选池第 %s 位开始不同（%s vs %s）'
                  % (q, diff_at, a[diff_at] if diff_at is not None else '-',
                     b[diff_at] if diff_at is not None else '-'))
        print('| %s | %s | %s | %s |'
              % (q, '✅ 一致' if same_pool else '❌ 不一致',
                 '✅ 一致' if same_rank else '❌ 不一致', ' / '.join(head)))

    print()
    print('候选池不稳定的 query：%d / %d' % (unstable_pool, len(QUERIES)))

    # 并列组：MySQL 相关度相同且 call_count 相同 —— ORDER BY 无唯一键兜底时顺序可能随机
    q = QUERIES[0]
    terms, variants = sd.build_terms(q)
    expanded = []
    for v in variants:
        for x in v:
            if x not in expanded:
                expanded.append(x)
    orq = '+(' + ' '.join(expanded) + ')'
    ck = sd.clean_keyword(q)
    sql_text = (
        "SELECT id, ROUND(MATCH(title,content,aliases,summary,tags) AGAINST('%s' IN NATURAL LANGUAGE MODE),6), "
        "IFNULL(call_count,0) FROM docs WHERE "
        "MATCH(title,content,aliases,summary,tags) AGAINST('%s' IN BOOLEAN MODE) "
        "AND ( (project_id IS NULL AND (visibility='PUBLIC' OR api_key='%s')) "
        "      OR project_id IN (SELECT id FROM projects WHERE api_key='%s') ) "
        "AND (template_type IS NULL OR template_type NOT IN ('project_progress','todo_list')) "
        "AND status IN ('ACTIVE') "
        "ORDER BY 2 DESC, 3 DESC LIMIT 200" % (ck, orq, key, key))
    rows = sql(sql_text)
    groups = {}
    for r in rows:
        if len(r) < 3:
            continue
        groups.setdefault((r[1], r[2]), []).append(r[0])
    ties = {k: v for k, v in groups.items() if len(v) > 1}
    print()
    print('=== 并列组（MySQL 相关度 + call_count 完全相同）===')
    print('并列组数：%d，涉及文档 %d 篇' % (len(ties), sum(len(v) for v in ties.values())))
    for (score, calls), ids in list(ties.items())[:8]:
        print('  相关度=%s calls=%s → %s' % (score, calls, ','.join('#' + i for i in ids)))
    if not ties:
        print('  无并列，ORDER BY 已可唯一定序')
    return 0


if __name__ == '__main__':
    sys.exit(main())

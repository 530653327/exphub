#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""5 组验收基线：每组一个唯一正解，看正解在不在前 3（#1290 C77）。

为什么单独一个脚本
------------------
调打分公式时最容易犯的错，是拿目标文档当训练集：为了让 #95 进前 3 调参数，
结果别的 query 全变差。评判标准必须固定成「5 组里正解进前 3 的数量」，
一次跑完、前后对比。

⚠️ 2026-11-08 口径变更（阁主 #134）
-----------------------------------
以前这里是「Python 复刻 Java 打分」，两套实现靠人肉同步。实测同源只有
10/33（「积分兑换提现异常」）和 23/33（「代码怎么发布到线上服务器 部署」）——
差的几位全是同分决胜（#360/#1278 同为 0.5615）和阈值预过滤（复刻没实现）。

现在唯一判定依据是**服务端自己吐的** `search_experience(explain=true)`：
  - 位次/分数/分项/同分胜负都取回执表格；
  - 复刻（score_distribution.py）降级成离线诊断，不再参与判定；
  - 越哥的硬要求仍保留：验收只认**离线固定快照**，不实时查库。
    快照 = 一次 explain 回执原样落盘，之后每次复跑都读它（`--refresh` 才更新）。

用法
----
    # 第一次 / 明确要刷新基线（需要 MCP_KEY，能读全局库）
    export MCP_KEY=<api_key>
    python3 tools/acceptance_5q.py --refresh

    # 日常（离线固定快照，不联网）
    python3 tools/acceptance_5q.py

    python3 tools/acceptance_5q.py --top 20     # C75 的统计窗口
"""
import argparse
import hashlib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

MCP_URL = 'https://cloudim.club/exphub/mcp/http'
SNAPSHOT_DEFAULT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                'acceptance_snapshot.json')

# 被主排名排除、只能走补位段的类型（服务端 explain 表里不会出现它们）
EXCLUDED_TYPES = {'project_progress', 'todo_list'}

# (query, 正解 ID 列表, 一句话说明, 是否计入「提升」)
# 第 3 组：原正解 #712 已于 2026-11-06 按阁主 C70-2 归档（COMPLETED，默认搜索不返回），
# 题目本身失效。按 C83 重写为「文档库整理 SOP」#713 —— 归档之后这类问题的正解变成 SOP。
# 但它基线就通过，**只能用于防退化，不能用于证明提升**，故单列为不计分组。
CASES = [
    ('代码怎么发布到线上服务器 部署', [95], 'ExpHub 部署手册', True),
    ('昨天部署踩了什么坑', [1236, 1274], '施工回执（过程记录，走补位）', True),
    ('scan_similar_docs 报错', [713], '文档库整理 SOP（原 #712 已归档，C83 重写）', False),
    ('积分兑换提现异常', [361], '提交错仓库那次事故', True),
    ('AGENTS.md 怎么分片', [233], 'AGENTS 分片规范', True),
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


# ==================== 调服务端 explain ====================
def call_explain(query, key, limit=30):
    """调一次 search_experience(explain=true)，返回原始回执文本。"""
    import urllib.request
    body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
                       'params': {'name': 'search_experience',
                                  'arguments': {'query': query, 'limit': limit,
                                                'scope': 'all', 'explain': True}}}).encode()
    req = urllib.request.Request(MCP_URL, data=body,
                                 headers={'authorization-key': key,
                                          'Content-Type': 'application/json',
                                          'Accept': 'application/json, text/event-stream'})
    raw = urllib.request.urlopen(req, timeout=60).read().decode('utf-8', 'replace')
    for line in raw.splitlines():
        s = line.strip()
        if s.startswith('data:'):
            s = s[5:].strip()
        if s.startswith('{'):
            try:
                o = json.loads(s)
            except ValueError:
                continue
            if o.get('id') == 1:
                return ((o.get('result') or {}).get('content') or [{}])[0].get('text', '')
    return ''


# ==================== 解析回执 ====================
_ROW = re.compile(r'^\|\s*(\d+)\s*\|\s*#(\d+)\s*\|\s*([^|]*?)\s*\|\s*([0-9.]+)\s*\|'
                  r'\s*([0-9.]+)\s*\|\s*([0-9.]+)\s*\|\s*([0-9.]+)\s*\|\s*([0-9.]+)\s*\|'
                  r'\s*(\d+)/(\d+)\s*\|\s*(\d+)\s*\|(.*)\|$')
_PROC = re.compile(r'-\s*【ID:(\d+)】')
_EXPLAIN_HEAD = '📊 打分解释'


def parse_receipt(text):
    """从回执里抠出 (主排名 rows, 补位段 ids)。

    只认 explain 表格（服务端判定依据）与「过程记录里也命中」段。
    表格之外的数字一律不采信 —— 那正是以前两套口径打架的地方。
    """
    rows, proc = [], []
    if not text or _EXPLAIN_HEAD not in text:
        return rows, proc
    in_proc = False
    for line in text.split('\n'):
        # ⚠️ 必须匹配段头那行（📝 开头），不能只匹配「过程记录里也命中」——
        #    首行的作用域说明里就写着「…或在结果末尾的『过程记录里也命中』找」，
        #    用它判段会把整张 explain 表当成补位段（第一版就是这么静默丢光的）。
        if line.strip().startswith('📝 过程记录里也命中'):
            in_proc = True
            continue
        if in_proc:
            m = _PROC.match(line.strip())
            if m:
                proc.append(int(m.group(1)))
            continue
        m = _ROW.match(line.strip())
        if not m:
            continue
        total, cov, field, rec, pop = (float(m.group(i)) for i in range(4, 9))
        rows.append(dict(rank=int(m.group(1)), id=int(m.group(2)),
                         ttype=(m.group(3) or '').strip(),
                         total=total, coverage=cov, field=field, recency=rec,
                         popularity=pop, matched=int(m.group(9)),
                         concept_total=int(m.group(10)), candidate_rank=int(m.group(11)),
                         tie=(m.group(12) or '').strip()))
    return rows, proc


def check_self_consistent(rows):
    """服务端总分必须等于它自己报的分项加权和（0.55/0.25/0/0.08）。

    这一步测的不是公式对不对，而是「回执里的数字是不是同一次打分出来的」。
    另算一遍再拼回执，就会出现「分项对、总分不对」这类看似合理的假数据。
    """
    bad = []
    for d in rows:
        expect = 0.55 * d['coverage'] + 0.25 * d['field'] + 0.08 * d['popularity']
        if abs(expect - d['total']) > 6e-4:      # 回执保留 4 位小数，留舍入余量
            bad.append((d['id'], d['total'], expect))
    return bad


# ==================== 快照 ====================
def capture(key, limit, path):
    results = []
    for q, _expect, _note, _counted in CASES:
        text = call_explain(q, key, limit)
        rows, proc = parse_receipt(text)
        # 只留「判定要用的数字」+ 回执头（口径行）。完整回执里含别人的文档标题/摘要，
        # 这个仓库可能有公开远端，快照进 git 不能把内部内容一起搬出去；
        # sha256 留作「这份快照确实来自那次回执」的凭证，复跑不依赖它。
        results.append(dict(query=q, rows=rows, process=proc,
                            receipt_head=text[:600],
                            receipt_sha256=hashlib.sha256(text.encode('utf-8')).hexdigest()))
        print('  已取: %-28s 主排名 %d 条 / 补位 %d 条'
              % (q, len(rows), len(proc)))
    payload = dict(saved_at=_now(), limit=limit, results=results)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)
    print('\n快照已写: %s（%.1f KB）' % (path, os.path.getsize(path) / 1024.0))


def _now():
    from datetime import datetime
    return datetime.now().strftime('%Y-%m-%d %H:%M:%S')


def load(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def main():
    _utf8_stdout()

    ap = argparse.ArgumentParser()
    ap.add_argument('--top', type=int, default=20, help='统计窗口（C75 用），默认 20')
    ap.add_argument('--snapshot', default=SNAPSHOT_DEFAULT, help='离线固定快照路径')
    ap.add_argument('--refresh', action='store_true',
                    help='重新调 explain=true 覆盖快照（需要 MCP_KEY）；不带就是纯离线复跑')
    a = ap.parse_args()

    key = os.environ.get('MCP_KEY') or os.environ.get('MCPKEY') or ''
    if a.refresh:
        if not key:
            print('缺 MCP_KEY（要能读全局库的 api_key）')
            return 2
        print('刷新基线快照：%s\n' % a.snapshot)
        capture(key, a.top, a.snapshot)
        print()
    if not os.path.exists(a.snapshot):
        print('没有离线快照: %s' % a.snapshot)
        print('先跑一次: MCP_KEY=<key> python3 tools/acceptance_5q.py --refresh')
        return 2

    snap = load(a.snapshot)
    by_query = {r['query']: r for r in snap.get('results', [])}
    print('快照: %s（%s 生成，limit=%s）'
          % (a.snapshot, snap.get('saved_at', '?'), snap.get('limit', '?')))
    print('窗口: 前 %d 名｜判定: 正解进前 3 算通过（过程记录类看补位段）\n' % a.top)
    print('| # | query | 正解 | 主排名位次 | 补位位次 | 结论 |')
    print('|---|---|---|---|---|---|')

    passed = 0
    counted_pass = 0
    counted_total = 0
    pa_hits = []
    inconsistent = 0
    for idx, (q, expect, note, counted) in enumerate(CASES, 1):
        rec = by_query.get(q)
        if not rec or not rec.get('rows'):
            print('| %d | %s | %s | 快照无结果 | - | ❌ |' % (idx, q, expect))
            continue
        rows = rec['rows']
        proc = rec.get('process', [])

        bad = check_self_consistent(rows)
        if bad:
            inconsistent += len(bad)
            print('|   | ↳ ⚠️ 服务端回执自相矛盾：%d 条的总分 != 分项加权'
                  '（首条 #%d 报 %.4f，算 %.4f）| | | |' % (len(bad), bad[0][0], bad[0][1], bad[0][2]))

        main_ids = [d['id'] for d in rows]
        main_rank = next((i + 1 for i, x in enumerate(main_ids) if x in expect), None)
        proc_rank = next((i + 1 for i, x in enumerate(proc) if x in expect), None)

        ok = False
        if main_rank is not None and main_rank <= 3:
            verdict, ok = '✅ 主排名前3', True
        elif main_rank is None and proc_rank is not None:
            verdict, ok = '✅ 补位命中', True
        else:
            verdict = '❌'
        if ok:
            passed += 1
            if counted:
                counted_pass += 1
        if counted:
            counted_total += 1
        print('| %d | %s | %s | %s | %s | %s%s |'
              % (idx, q, '/'.join('#%d' % x for x in expect),
                 main_rank if main_rank else '—',
                 proc_rank if proc_rank else '—', verdict,
                 '' if counted else '（不计入提升）'))

        # C75：project_agents 在前 N 名里的分布
        for i, d in enumerate(rows[:a.top], 1):
            if (d.get('ttype') or '') == 'project_agents':
                pa_hits.append((q, d['id'], i, d['total']))

        # C78：目标文档与前一名差多少分（分数直接来自服务端 explain）
        target = expect[0]
        for i, d in enumerate(rows):
            if d['id'] == target:
                prev = rows[i - 1] if i > 0 else None
                gap = ('%.4f' % (prev['total'] - d['total'])) if prev else '—'
                print('|   | ↳ 分数 | %.4f | 前一名 | %s | 差 %s |'
                      % (d['total'], '#' + str(prev['id']) if prev else '—', gap))
                break

    print()
    print('**5 组里通过 %d 组**（判据：正解进前 3，或属于过程记录且进补位段前 3）' % passed)
    print('**其中计入「提升」的 %d 组里通过 %d 组**（另有 1 组防退化，基线即通过，不算提升）'
          % (counted_total, counted_pass))
    if inconsistent:
        print('⚠️ 服务端 explain 自洽检查：%d 条总分对不上分项 —— 这是回执缺陷，先报维护者，别调公式。'
              % inconsistent)
    else:
        print('✅ 服务端 explain 自洽：所有条目总分 = 0.55*coverage + 0.25*field + 0.08*popularity。')
    print('⚠️ 这是基线，不是成绩。数字全部来自离线快照（%s 生成）的服务端 explain，'
          '复刻脚本只做诊断、不再参与判定。' % snap.get('saved_at', '?'))
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

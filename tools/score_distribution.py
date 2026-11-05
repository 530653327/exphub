#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""搜索打分诊断：分数分布 / 单文档分项 / 反事实重排。

为什么要有这个脚本
------------------
#1277 C8 要回答一个问题：某个文档排不上去，到底是「recency 权重太大」还是
「打分区分度不够」？这两件事的修法完全不同，猜不得。本脚本在**不改任何生产
公式**的前提下，把这两层量出来：

  --distribution   200 条候选的分数分布（min/P25/中位/P75/max/标准差）
  --explain ID     指定文档的分项拆解（coverage / field / recency / popularity）
  --counterfactual 把 recency 权重临时归零、其余不动，重排后目标文档的新位次

正确性怎么保证
--------------
不手抄公式，而是复现 DocService.buildSearchTerms + rankAndFilter，再用 --verify
拿复现出的题序和线上真实返回**逐位对账**。对不上就报 MISMATCH，不拿错数据出结论。

⚠️ 复现时最容易错的一处（已经踩过）：召回的 ORDER BY 用的是 `cleanKeyword` 的
NATURAL LANGUAGE 模式，**不是** WHERE 里那个展开后的 OR 串（BOOLEAN 模式）。
写错这一处，200 条候选的边界就会变，对账只有 4/20 一致。

用法
----
  # 在服务器上（读 /etc/exphub/.env 拿库连接）
  python3 tools/score_distribution.py --query "..." --explain 95 --counterfactual

  # 本地（用环境变量给连接）
  export EXPHUB_DB_HOST=... EXPHUB_DB_USER=... EXPHUB_DB_PASS=... EXPHUB_DB_NAME=exphub
  python3 tools/score_distribution.py --query "..." --explain 95

  # 带对账（需要 MCP 的 authorization-key）
  export MCP_KEY=...
  python3 tools/score_distribution.py --query "..." --explain 95 --counterfactual --verify
"""
import argparse
import io
import json
import math
import os
import re
import subprocess
import sys
from datetime import datetime

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

STOPWORDS = {'的', '了', '和', '与', '及', '或', '怎么', '如何', '什么', '为什么', '哪些',
             '是否', '可以', '需要', '一下', '我们', '你们', '他们', '这个', '那个',
             '请问', '帮我', '看看', '相关', '关于', '现在', '进行'}
CJK = re.compile(r'[\u4e00-\u9fa5]')
ENV_FILE = '/etc/exphub/.env'
CANDIDATE_LIMIT = 200          # 与 DocService.CANDIDATE_LIMIT 一致
RECENCY_WEIGHT = 0.12          # 与 DocService.score 一致
MCP_URL = 'https://cloudim.club/exphub/mcp/http'


# ==================== 连接配置 ====================
def load_db_conf():
    """优先环境变量，其次服务器上的 /etc/exphub/.env。"""
    if os.path.exists(ENV_FILE):
        env = {}
        for line in open(ENV_FILE, encoding='utf-8', errors='replace'):
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                k, v = line.split('=', 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
        url = env.get('EXPHUB_DB_URL', '')
        m = re.match(r'jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)', url)
        if m:
            return dict(host=m.group(1), port=m.group(2) or '3306', db=m.group(3),
                        user=env.get('EXPHUB_DB_USERNAME', ''),
                        password=env.get('EXPHUB_DB_PASSWORD', ''))
    return dict(host=os.environ.get('EXPHUB_DB_HOST', ''),
                port=os.environ.get('EXPHUB_DB_PORT', '3306'),
                db=os.environ.get('EXPHUB_DB_NAME', 'exphub'),
                user=os.environ.get('EXPHUB_DB_USER', ''),
                password=os.environ.get('EXPHUB_DB_PASS', ''))


DB = load_db_conf()


def sql(q):
    e = os.environ.copy()
    e['MYSQL_PWD'] = DB['password']
    r = subprocess.run(['mysql', '--default-character-set=utf8mb4',
                        '-h', DB['host'], '-P', DB['port'], '-u', DB['user'], DB['db'], '-N', '-B'],
                       env=e, input=q.encode('utf-8'), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return r.stdout.decode('utf-8', 'replace'), r.stderr.decode('utf-8', 'replace')


# ==================== 复现 DocService 内部逻辑 ====================
def build_terms(keyword):
    cleaned = re.sub(r'[\+\-><\(\)~\*"@]', ' ', keyword).strip()
    concepts = []
    for raw in re.split(r'[\s,，、;；]+', cleaned):
        tok = raw.strip()
        if not tok:
            continue
        if not any(c.isalnum() or CJK.match(c) for c in tok):
            continue
        if tok.lower() in STOPWORDS:
            continue
        if tok not in concepts:
            concepts.append(tok)
    variants = []
    for c in concepts:
        v = [c]
        chinese = ''.join(ch for ch in c if CJK.match(ch))
        non_chinese = ''.join(' ' if CJK.match(ch) else ch for ch in c).strip()
        if non_chinese:
            for part in re.split(r'[^A-Za-z0-9]+', non_chinese):
                if len(part) >= 2 and part not in v:
                    v.append(part)
        if len(chinese) == 2:
            if chinese not in v:
                v.append(chinese)
        elif len(chinese) > 2:
            for i in range(len(chinese) - 1):
                g = chinese[i:i + 2]
                if g not in v:
                    v.append(g)
        variants.append(v)
    return concepts, variants


def clean_keyword(keyword):
    s = re.sub(r'[\+\-><\(\)~\*"@]', ' ', keyword)
    return re.sub(r'[\s,，、]+', ' ', s).strip()


def _contains(text, needle):
    return bool(text) and bool(needle) and needle.lower() in text.lower()


FIELD_ORDER = (('title', 3.0), ('aliases', 2.0), ('summary', 2.0), ('tags', 2.0), ('content', 1.0))


def first_hit(doc, term):
    """按 Java fieldWeight 的优先级返回 (字段名, 权重)；未命中 (None, 0)。

    与 DocService.fieldWeight(doc, term) 一一对应：title 3 / aliases·summary·tags 2 / content 1。
    """
    for name, w in FIELD_ORDER:
        if _contains(doc[name], term):
            return name, w
    return None, 0.0


def field_weight_term(doc, term):
    return first_hit(doc, term)[1]


def field_weight(doc, variants):
    best = 0.0
    for v in variants:
        best = max(best, field_weight_term(doc, v))
        if best >= 3.0:
            break
    return best


def concept_strength(doc, variants):
    if not variants:
        return 0.0
    if field_weight_term(doc, variants[0]) > 0:
        return 1.0
    for v in variants[1:]:
        if field_weight_term(doc, v) > 0:
            return 0.5
    return 0.0


def parts(doc, variants, recency_weight=RECENCY_WEIGHT):
    n = len(variants)
    concept_score = sum(concept_strength(doc, v) for v in variants)
    coverage = concept_score / max(1, n)
    field_sum = sum(field_weight(doc, v) for v in variants)
    field_score = min(1.0, field_sum / max(1, n) / 3.0)
    days = max(0, doc['days_old'])
    recency = math.exp(-days / 90.0)
    pop = min(1.0, math.log1p(doc['calls']) / math.log1p(50))
    total = 0.55 * coverage + 0.25 * field_score + recency_weight * recency + 0.08 * pop
    return dict(coverage=coverage, field=field_score, recency=recency, pop=pop, total=total)


def explain_concepts(doc, concepts, variants):
    """逐概念拆解：命中强度 / 字段分 / 第一个命中的字段与词。

    这一层是为了回答「某个词到底有没有参与打分、命中在哪」这类问题 ——
    只看 coverage / field 两个汇总数，会把「词被丢了」和「词在但没得分」混为一谈。
    """
    out = []
    for c, v in zip(concepts, variants):
        strength = concept_strength(doc, v)
        fw = field_weight(doc, v)
        hit_field, hit_term, hit_w = None, None, 0.0
        for t in v:
            name, w = first_hit(doc, t)
            if w > hit_w:
                hit_field, hit_term, hit_w = name, t, w
            if hit_w >= 3.0:
                break
        out.append(dict(concept=c, strength=strength, field=fw,
                        hit_field=hit_field, hit_term=hit_term, hit_weight=hit_w,
                        hit_exact=(hit_term == c)))
    return out


def pct(vals, p):
    if not vals:
        return 0.0
    k = (len(vals) - 1) * p
    lo, hi = int(math.floor(k)), int(math.ceil(k))
    return vals[lo] if lo == hi else vals[lo] + (vals[hi] - vals[lo]) * (k - lo)


# ==================== 按类型分组（#1277 C18）====================
# 标题特征归类：templateType 只有一套官方取值，「信箱」「施工回执」是内容属性，
# 库里没有对应枚举，只能按标题认。口径写在这里，别人复跑能对上。
PROCESS_RULES = [
    ('信箱', lambda d: '协作信箱' in d['title']),
    ('回执/施工日志', lambda d: '回执' in d['title'] or '施工日志' in d['title']),
    # 「清单」单独不算：产品文档里「部署清单」「工具清单页」是正文内容，不是过程记录
    ('待办/进度/吐槽单', lambda d: '待办' in d['title'] or '进度' in d['title'] or '吐槽清单' in d['title']),
]


def print_groups(rows, top_n=30, head_n=8):
    """按 templateType 与「过程记录」两类口径统计候选集与前 N 名的构成。"""
    def bucket(key_fn):
        b = {}
        for i, d in enumerate(rows):
            k = key_fn(d)
            e = b.setdefault(k, dict(n=0, top=0, head=0, ids=[], best=0.0))
            e['n'] += 1
            e['best'] = max(e['best'], d['p']['total'])
            if i < top_n:
                e['top'] += 1
                e['ids'].append(d['id'])
            if i < head_n:
                e['head'] += 1
        return b

    print('\n=== 候选集 %d 条 · 按 templateType ===' % len(rows))
    print('%-18s %6s %6s %6s %8s' % ('template_type', '候选', '前%d' % top_n, '前%d' % head_n, '最高分'))
    bt = bucket(lambda d: d['ttype'])
    for k, e in sorted(bt.items(), key=lambda x: (-x[1]['top'], -x[1]['n'])):
        print('%-18s %6d %6d %6d %8.4f  %s'
              % (k, e['n'], e['top'], e['head'], e['best'],
                 ','.join(str(x) for x in e['ids'][:12])))

    print('\n=== 过程记录类（按标题特征，非官方分类）===')
    seen = set()
    for name, fn in PROCESS_RULES:
        e = bucket(fn).get(True)
        if not e:
            print('%-10s %6d %6d %6d  -' % (name, 0, 0, 0))
            continue
        seen.update(e['ids'])
        print('%-10s %6d %6d %6d  %s'
              % (name, e['n'], e['top'], e['head'], ','.join(str(x) for x in e['ids'])))
    union = set()
    for _, fn in PROCESS_RULES:
        union.update(d['id'] for d in rows if fn(d))
    in_top = [d['id'] for d in rows[:top_n] if d['id'] in union]
    in_head = [d['id'] for d in rows[:head_n] if d['id'] in union]
    share_cand = 100.0 * len(union) / max(1, len(rows))
    share_top = 100.0 * len(in_top) / max(1, top_n)
    print('合计（去重）  %6d %6d %6d' % (len(union), len(in_top), len(in_head)))
    print('  占候选集 %.0f%%（%d/%d）｜占前%d %.0f%%（%d/%d）｜富集 %.1f 倍'
          % (share_cand, len(union), len(rows), top_n, share_top, len(in_top), top_n,
             share_top / share_cand if share_cand else 0))
    print('  占前%d %.0f%%（%d/%d）'
          % (head_n, 100.0 * len(in_head) / max(1, head_n), len(in_head), head_n))

    pp = [d for d in rows if d['ttype'] == 'project_progress']
    print('其中 template_type=project_progress：候选 %d 条，前%d %d 条%s'
          % (len(pp), top_n, sum(1 for d in rows[:top_n] if d['ttype'] == 'project_progress'),
             '（全库 project_progress 大多挂在 project_id 下，被 WHERE 排除）' if not pp else ''))


def save_snapshot(path, query, rows, api_key_used):
    """固定快照：把候选集与 calls 定格，之后所有前后对比都在这份上跑（#1277 C20）。"""
    payload = dict(query=query, saved_at=datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
                   candidate_limit=CANDIDATE_LIMIT, key_used=bool(api_key_used), rows=rows)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(payload, f, ensure_ascii=False)
    print('\n快照已存: %s（%d 条，%.1f KB）'
          % (path, len(rows), os.path.getsize(path) / 1024.0))


def load_snapshot(path):
    with open(path, encoding='utf-8') as f:
        o = json.load(f)
    print('读快照: %s（%s 生成，%d 条，query=%r）'
          % (path, o.get('saved_at', '?'), len(o['rows']), o.get('query')))
    return o


# ==================== 取候选集 ====================
def fetch_candidates(query, api_key):
    concepts, variants = build_terms(query)
    expanded = []
    for v in variants:
        for x in v:
            if x not in expanded:
                expanded.append(x)
    orq = '+(' + ' '.join(expanded) + ')'
    ck = clean_keyword(query)

    # 注意：WHERE 用 orq（BOOLEAN），ORDER BY 用 cleanKeyword（NATURAL）。写错这处会让候选边界变。
    where = ("MATCH(title,content,aliases,summary,tags) AGAINST('%s' IN BOOLEAN MODE) "
             "AND ( (project_id IS NULL AND (visibility='PUBLIC' OR api_key='%s')) "
             "      OR project_id IN (SELECT id FROM projects WHERE api_key='%s') ) "
             "AND (template_type IS NULL OR template_type NOT IN ('project_progress','todo_list')) "
             "AND status IN ('ACTIVE') "
             "ORDER BY MATCH(title,content,aliases,summary,tags) AGAINST('%s' IN NATURAL LANGUAGE MODE) DESC, "
             "call_count DESC LIMIT %d"
             % (orq, api_key, api_key, ck, CANDIDATE_LIMIT))

    q = ("SELECT id, IFNULL(title,''), IFNULL(aliases,''), IFNULL(summary,''), IFNULL(tags,''), "
         "REPLACE(IFNULL(content,''),'\\n',' '), DATEDIFF(NOW(), updated_at), IFNULL(call_count,0), "
         "IFNULL(template_type,'') "
         "FROM docs WHERE " + where + ";")
    out, err = sql(q)
    if err.strip():
        raise RuntimeError('SQL 失败: ' + err.strip()[:300])
    rows = []
    for line in out.split('\n'):
        if not line.strip():
            continue
        f = line.split('\t')
        if len(f) < 9:
            continue
        rows.append(dict(id=int(f[0]), title=f[1], aliases=f[2], summary=f[3], tags=f[4],
                         content=f[5], days_old=int(f[6]), calls=int(f[7]), ttype=f[8] or '(空)'))
    return rows, variants, orq, ck


def live_ids(query, api_key, limit=30):
    import urllib.request
    body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
                       'params': {'name': 'search_experience',
                                  'arguments': {'query': query, 'limit': limit, 'scope': 'all'}}}).encode()
    req = urllib.request.Request(MCP_URL, data=body,
                                 headers={'authorization-key': api_key,
                                          'Content-Type': 'application/json',
                                          'Accept': 'application/json, text/event-stream'})
    raw = urllib.request.urlopen(req, timeout=30).read().decode('utf-8', 'replace')
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
                txt = ((o.get('result') or {}).get('content') or [{}])[0].get('text', '')
                return [int(x) for x in re.findall(r'【ID:(\d+)】', txt)]
    return []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--query', default=None,
                    help='搜索词。中文在 ssh 命令行里会因服务器 locale 是 ASCII 而变成 surrogate，'
                         '此时改用 --query-file（stdin 传字节流可保真）')
    ap.add_argument('--query-file', default=None, help='从文件读搜索词（UTF-8），规避命令行编码问题')
    ap.add_argument('--explain', type=int, default=None, help='要拆解分项的文档 ID')
    ap.add_argument('--counterfactual', action='store_true', help='recency 权重归零后重排')
    ap.add_argument('--top', type=int, default=0, help='打印前 N 名的 ID 与标题（配合 --pollution 看霸榜）')
    ap.add_argument('--pollution', default='',
                    help='逗号分隔的污染源 ID，打印前 N 名时标记并统计占比')
    ap.add_argument('--groups', action='store_true',
                    help='按 templateType / 过程记录类统计候选集与前 N 名的构成')
    ap.add_argument('--snapshot-out', default=None, help='把候选集与 calls 定格到文件（后续对比都用它）')
    ap.add_argument('--snapshot-in', default=None, help='从快照读候选集，不再查库（固定口径复跑）')
    ap.add_argument('--verify', action='store_true', help='与线上真实顺序逐位对账（需 MCP_KEY）')
    a = ap.parse_args()

    query = a.query
    if a.query_file:
        query = open(a.query_file, encoding='utf-8').read().strip()
    if not query and a.snapshot_in:
        pass                      # query 从快照里取，别读 stdin（非交互 ssh 下会挂住）
    elif not query or query == '-':
        query = sys.stdin.read().strip()
    if not query and not a.snapshot_in:
        print('缺 query')
        return 2

    api_key = os.environ.get('MCP_KEY') or os.environ.get('MCPKEY') or ''
    if not api_key:
        # 对账与 scope=all 的 ownerKey 都靠这个 Key；没有就只做「无 Key 版」的诊断
        print('⚠️ 未设置 MCP_KEY：scope 条件无法复刻（会退化成 api_key="" 的过滤），仅供粗看。')
        api_key = ''

    if a.snapshot_in:
        snap = load_snapshot(a.snapshot_in)
        query = query or snap['query']
        variants = build_terms(query)[1]
        rows, orq, ck = snap['rows'], '(来自快照)', '(来自快照)'
    else:
        rows, variants, orq, ck = fetch_candidates(query, api_key)
    if not rows:
        print('候选集为空，检查 query / 连接')
        return 1
    for d in rows:
        d['p'] = parts(d, variants)
    rows.sort(key=lambda x: -x['p']['total'])

    print('query=%r' % query)
    print('concepts=%r  variants=%d 项' % (build_terms(query)[0], len(variants[0])))
    print('orq=%s' % orq)
    print('cleanKeyword=%r' % ck)
    print('候选集=%d 条' % len(rows))

    if a.snapshot_out and not a.snapshot_in:
        save_snapshot(a.snapshot_out, query, rows, api_key)

    if a.verify and api_key:
        live = live_ids(query, api_key)
        mine = [d['id'] for d in rows[:len(live)]]
        same = sum(1 for x, y in zip(live, mine) if x == y)
        print('\n对账：线上 %s' % live[:20])
        print('      复现 %s' % mine[:20])
        print('      逐位一致 %d/%d -> %s' % (same, len(live), 'REPRODUCIBLE' if same >= 0.9 * len(live) else 'MISMATCH'))

    scores = sorted(d['p']['total'] for d in rows)
    mean = sum(scores) / len(scores)
    sd = math.sqrt(sum((x - mean) ** 2 for x in scores) / len(scores))
    print('\n=== 分数分布（%d 条）===' % len(scores))
    print('min=%.4f P25=%.4f 中位=%.4f P75=%.4f max=%.4f' %
          (scores[0], pct(scores, .25), pct(scores, .50), pct(scores, .75), scores[-1]))
    print('均值=%.4f 标准差=%.4f 极差=%.4f' % (mean, sd, scores[-1] - scores[0]))

    tiers = {}
    for d in rows:
        k = (round(d['p']['coverage'], 2), round(d['p']['field'], 2))
        tiers[k] = tiers.get(k, 0) + 1
    print('分档 (coverage, field): %s' % sorted(tiers.items(), key=lambda x: -x[1])[:8])

    # coverage 满分的成员是谁 —— 用来回答「满分是真相关，还是抄了 query 原话」
    full = [d for d in rows if d['p']['coverage'] >= 0.999]
    if full:
        print('\ncoverage=1.0 的 %d 条（满分成员）:' % len(full))
        for d in full[:10]:
            print('  #%-5d field=%.2f  %s' % (d['id'], d['p']['field'], (d['title'] or '')[:38]))

    if a.groups:
        print_groups(rows, top_n=(a.top or 30))

    if a.explain is not None:
        d = next((x for x in rows if x['id'] == a.explain), None)
        if d is None:
            print('\n#%d 不在候选集内' % a.explain)
        else:
            p = d['p']
            rank = [x['id'] for x in rows].index(a.explain) + 1
            print('\n=== #%d 分项（位次 %d/%d）===' % (a.explain, rank, len(rows)))
            print('coverage=%.3f (x0.55 -> %.4f)' % (p['coverage'], 0.55 * p['coverage']))
            print('field   =%.3f (x0.25 -> %.4f)' % (p['field'], 0.25 * p['field']))
            print('recency =%.3f (x%.2f -> %.4f)  天数=%d' % (p['recency'], RECENCY_WEIGHT, RECENCY_WEIGHT * p['recency'], d['days_old']))
            print('pop     =%.3f (x0.08 -> %.4f)  calls=%d' % (p['pop'], 0.08 * p['pop'], d['calls']))
            print('总分    =%.4f' % p['total'])

            concepts_now = build_terms(query)[0]
            print('\n--- 逐概念（共 %d 个，coverage 分母 / field 分母都是它）---' % len(concepts_now))
            for i, e in enumerate(explain_concepts(d, concepts_now, variants), 1):
                if e['hit_field'] is None:
                    desc = '未命中任何字段'
                else:
                    desc = '命中 %s（权重 %.1f）中的 “%s”%s' % (
                        e['hit_field'], e['hit_weight'], e['hit_term'],
                        '' if e['hit_exact'] else '（2-gram/子串，非整词）')
                print('  [%d] %-24s strength=%.1f field=%.1f  %s'
                      % (i, e['concept'], e['strength'], e['field'], desc))

    if a.top:
        ids = [d for d in rows[:a.top]]
        pol = set(int(x) for x in a.pollution.split(',') if x.strip().isdigit())
        print('\n=== 前 %d 名 ===' % a.top)
        for i, d in enumerate(ids, 1):
            flag = ' 🧪污染' if d['id'] in pol else ''
            print('  %2d. #%-5d %.4f%s  %s' % (i, d['id'], d['p']['total'], flag, (d['title'] or '')[:34]))
        if pol:
            hit = sum(1 for d in ids if d['id'] in pol)
            # 没传 --explain 时别硬算位次：会打印「#0 位于第 -1 位」这种看似报错的数字
            if a.explain and any(d['id'] == a.explain for d in rows):
                rest = [d['id'] for d in rows if d['id'] not in pol]
                print('前 %d 名里污染源 %d 条；去掉它们后 #%d 位于第 %d 位'
                      % (a.top, hit, a.explain, rest.index(a.explain) + 1))
            else:
                print('前 %d 名里污染源 %d 条（要看去掉后的位次，加 --explain <ID>）'
                      % (a.top, hit))

    if a.counterfactual:
        for d in rows:
            d['cf'] = parts(d, variants, recency_weight=0.0)['total']
        cf = sorted(rows, key=lambda x: -x['cf'])
        ids = [d['id'] for d in cf]
        tgt = a.explain if a.explain is not None else ids[0]
        print('\n=== 反事实：recency 权重 %.2f -> 0 ===' % RECENCY_WEIGHT)
        print('#%d 新位次 = %s/%d' % (tgt, (ids.index(tgt) + 1) if tgt in ids else '不在内', len(ids)))
        print('新前 10: %s' % ids[:10])
    return 0


if __name__ == '__main__':
    sys.exit(main())

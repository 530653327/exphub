#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""项目识别（detectProjectInQuery / resolveProject）回归脚本。

为什么要有这个脚本
------------------
2026-10-26 L0-1a 议题里，我们连续两回「手敲验一条就以为够了」：

  1. 只验了「不误召」（`API 接口` 被项目 5 劫持），没验「不漏召」；
     实际 `拆解宝的报废车回收` 当时就是漏的（中文不加空格是常态）。
  2. 只按长度定护栏（ASCII >=4），没验短别名 `hjs`（df 7.9%）会被一刀切掉。

所以这里把两类算例固化成可重复跑的：

  A 组（6 条）= #677 §14.2 的线上实测基线：跑**线上真实系统**，看现状。
  B 组（4 条）= #678 §1 的规则验算：跑**规则引用实现**（本项目内，不依赖部署），
                 用来验证「定案规则本身」判得对不对。

A 组现在**预期有多条 FAIL**（这是基线，不是脚本坏了）。修完 L0-1a 后跑
`--mode live --assert` 应全绿；B 组在规则实现上应恒绿（它就是规则的可执行定义）。

用法
----
  # 只跑规则验算（离线，不需要服务器）
  python3 tools/search_regression.py --mode rule

  # 跑线上基线（需要 MCP_KEY / MCP_URL）
  export MCP_KEY=...  MCP_URL=https://cloudim.club/exphub/mcp/sse
  python3 tools/search_regression.py --mode live

  # 两边都跑 + 对照（默认）
  python3 tools/search_regression.py

  # 修复后当门禁用：任一算例不符合预期 -> 退出码 1
  python3 tools/search_regression.py --mode live --assert

  # 重新生成 df 快照时要跑的 SQL（不连库，只打印）
  python3 tools/search_regression.py --print-df-sql

df 快照
-------
判据是 `df < 30%`，所以 df 必须与「作用域」绑定。项目识别发生在**全局域**
（此时还不知道属于哪个项目），因此统一用：

    df_pct(t) = 全局域内含 t 的 ACTIVE 文档数 / 全局域 ACTIVE 文档数

数值来源 tools/df_snapshot.json（含抓取时间与 N）。缺词时**不放行**：
`df=None` 会让该分支判定为 UNKNOWN 并计入失败，而不是默默当成 0% ——
默认为 0 会让任何新词都变「稀有」，护栏就失效了。
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DF_SNAPSHOT = os.path.join(HERE, "df_snapshot.json")

# ==================== A 组：#677 §14.2 线上基线 ====================
# 期望值 = 修复后「正确」的行为（不是现状）。现状多为 FAIL，这正是基线。
# 期望一律用 project_id（稳定），项目名只用于展示。
# 教训：第一版用项目名做期望，把「拆解宝」写成期望值，而库里全名是
# 「拆解宝（报废车回收模块）」→ 首跑出现一条假 FAIL。名字会变、id 不会。
CASES_LIVE = [
    dict(cid="A1", query="拆解宝的报废车回收",
         expect_id=3, note="CJK 无空格 contain；14.2 实测现状=漏召"),
    dict(cid="A2", query="回金算的入库单",
         expect_id=2, note="CJK 无空格 contain；现状=漏召"),
    dict(cid="A3", query="忆阁DeepSeek余额",
         expect_id=6, note="CJK+ASCII 混排无空格；现状=漏召"),
    dict(cid="A4", query="拆解宝 报废车回收",
         expect_id=3, note="有空格，靠精确 token 命中；现状=OK"),
    dict(cid="A5", query="hjs",
         expect_id=2, note="3 字符短别名，df 7.9%；现状=OK，按长度>=4 会失血"),
    dict(cid="A6", query="API 接口",
         expect_id=None, note="防误召：不得识别为任何项目；现状=被项目5劫持"),
]

# ==================== B 组：#678 §1 规则验算 ====================
# 关系：① A==T  ② A⊃T  ③ T⊃A
CASES_RULE = [
    dict(cid="B1", query="hjs", relation="①",
         expect_id=2, note="df(hjs)=7.9% < 30%，不限长度"),
    dict(cid="B2", query="hjs的入库单", relation="③",
         expect_id=2, note="hjs 起头 + 后接 CJK「的」= 边界成立；df 7.9%"),
    dict(cid="B3", query="用 api 接口", relation="②",
         expect_id=None, note="token=api；项=xy-api。df(api)=35.4% >= 30% -> 拒绝"),
    dict(cid="B4", query="xy-api", relation="②（spec 原文如此，实测走 ①）",
         expect_id=None,
         note="⚠ SPEC-CONFLICT：#678 §1 表里把本项写成走 ② 且因「前接 - 边界失败」被拒；"
              "但 xy-api 本身就是项目 5 的别名项，token 与项相等 -> 走 ①，df=0.6% -> 命中。"
              "本条期望值待阁主裁定：输入 xy-api 应否命中项目 5"),
]


# ==================== 规则引用实现（就是 #678 §1 的可执行定义）====================

CJK = re.compile(r"[\u4e00-\u9fff]")
ALNUM = re.compile(r"[A-Za-z0-9]")


def load_df():
    with open(DF_SNAPSHOT, encoding="utf-8") as fh:
        snap = json.load(fh)
    return snap


def df_pct(term, snap):
    """返回占全局域的百分比；未收录 -> None（调用方必须当成 UNKNOWN，不许放行）。"""
    v = snap["df_global"].get(term)
    if v is None:
        v = snap["df_global"].get(term.lower())
    if v is None:
        return None
    return v * 100.0 / snap["n_global"]


def _boundary_both_sides(hay: str, needle: str, strict_cjk_neighbor: bool):
    """needle 在 hay 里每一次出现，两侧是否都算「边界」。

    strict_cjk_neighbor=True  -> ③ 的口径：两侧是起止 / 空白 / CJK
    strict_cjk_neighbor=False -> ② 的口径：两侧不是 ASCII 字母数字
    返回 (ok, 说明)
    """
    if not needle:
        return False, "空串"
    start = 0
    seen = False
    while True:
        i = hay.find(needle, start)
        if i < 0:
            break
        seen = True
        left = hay[i - 1] if i > 0 else None
        right = hay[i + len(needle)] if i + len(needle) < len(hay) else None

        def is_bound(b):
            if b is None:                     # 起止
                return True
            if b.isspace():
                return True
            if strict_cjk_neighbor:
                return bool(CJK.match(b))     # ③ 只认 CJK / 空白 / 起止
            return not bool(ALNUM.match(b))   # ② 非 ASCII 字母数字即可
        if is_bound(left) and is_bound(right):
            return True, "两侧边界成立"
        start = i + 1
    if not seen:
        return False, "未出现"
    return False, "出现但两侧不是边界"


def detect_by_rule(query, projects, snap):
    """按 #678 §1 定案规则识别项目。返回 (命中 project_id 或 'MULTI:1|2', 命中明细)。"""
    tokens = [t for t in re.split(r"[\s,，、;；]+", query) if len(t) >= 2]
    detail = []
    hit_ids = set()
    for proj in projects:
        items = []
        aliases = proj.get("aliases") or ""
        for x in re.split(r"[,，]", aliases):
            x = x.strip()
            if x:
                items.append(x)
        if proj.get("project_code"):
            items.append(proj["project_code"].strip())
        if proj.get("name"):
            items.append(proj["name"].strip())

        for item in items:
            if len(item) < 2:      # 任何别名项长度 < 2 不参与
                continue
            for tok in tokens:
                rel = None
                term = None
                ok, why = False, ""
                if item.lower() == tok.lower():
                    rel, term = "①", tok
                    ok, why = True, "相等"
                elif item.lower().find(tok.lower()) >= 0:
                    rel, term = "②", tok
                    ok, why = _boundary_both_sides(item.lower(), tok.lower(), False)
                    if ok and len(tok) < 2:
                        ok, why = False, "token 长度 <2"
                elif tok.lower().find(item.lower()) >= 0:
                    rel, term = "③", item
                    ok, why = _boundary_both_sides(tok.lower(), item.lower(), True)
                if not rel:
                    continue
                pct = df_pct(term, snap)
                if pct is None:
                    detail.append(f"{proj['name']} | {rel} | 项「{item}」vs token「{tok}」"
                                  f" | 边界:{why} | df=UNKNOWN -> 拒绝(缺数不放行)")
                    continue
                if pct >= 30.0:
                    detail.append(f"{proj['name']} | {rel} | 项「{item}」vs token「{tok}」"
                                  f" | 边界:{why} | df({term})={pct:.1f}% >=30% -> 拒绝")
                    continue
                if not ok:
                    detail.append(f"{proj['name']} | {rel} | 项「{item}」vs token「{tok}」"
                                  f" | 边界:{why} -> 拒绝")
                    continue
                detail.append(f"{proj['name']} | {rel} | 项「{item}」vs token「{tok}」"
                              f" | 边界:{why} | df({term})={pct:.1f}% <30% -> 命中")
                hit_ids.add(proj["id"])
    if len(hit_ids) == 1:
        return next(iter(hit_ids)), detail
    if len(hit_ids) > 1:
        return "MULTI:" + "|".join(str(i) for i in sorted(hit_ids)), detail
    return None, detail


# ==================== 线上调用（自带 SSE，不依赖 /tmp 脚本）====================

class Mcp:
    def __init__(self, url, key):
        self.url = url
        self.key = key
        self.resp = None
        self.msg_url = None
        self._id = 0
        self._open()

    def _open(self):
        req = urllib.request.Request(
            self.url, headers={"authorization-key": self.key,
                              "Accept": "text/event-stream"})
        self.resp = urllib.request.urlopen(req, timeout=40)
        deadline = 60
        while deadline > 0:
            line = self.resp.readline()
            if not line:
                deadline -= 1
                continue
            s = line.decode("utf-8", "replace")
            m = re.search(r"(/mcp/message\?sessionId=[A-Za-z0-9_-]+)", s)
            if m:
                base = self.url[: -len("/mcp/sse")] if self.url.endswith("/mcp/sse") else self.url
                self.msg_url = base + m.group(1)
                break
        if not self.msg_url:
            raise RuntimeError("拿不到 SSE session endpoint")
        self._post({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                    "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                               "clientInfo": {"name": "search-regression", "version": "1.0"}}})
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def _post(self, payload):
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            self.msg_url, data=data,
            headers={"authorization-key": self.key,
                     "Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=40) as r:
                return r.status
        except urllib.error.HTTPError as e:
            return e.code

    def call(self, tool, args, wait=60):
        self._id += 1
        mid = self._id
        self._post({"jsonrpc": "2.0", "id": mid, "method": "tools/call",
                    "params": {"name": tool, "arguments": args}})
        for _ in range(wait * 2):
            line = self.resp.readline()
            if not line:
                continue
            s = line.decode("utf-8", "replace").strip()
            if not s.startswith("data:"):
                continue
            try:
                obj = json.loads(s[5:].strip())
            except Exception:
                continue
            if obj.get("id") != mid:
                continue
            content = (obj.get("result") or {}).get("content") or []
            if content:
                return content[0].get("text", "")
            return json.dumps(obj, ensure_ascii=False)
        raise RuntimeError(f"等不到 id={mid} 的响应")

    def close(self):
        try:
            self.resp.close()
        except Exception:
            pass


def detected_project(text):
    m = re.search(r"查询词含项目「([^」]+)」", text)
    return m.group(1) if m else None


# ==================== 输出 ====================
def fmt(ok):
    return "PASS" if ok else "FAIL"


def pid_label(projects, pid):
    if pid is None:
        return "不命中任何项目"
    if isinstance(pid, str):
        return pid
    for p in projects:
        if p["id"] == pid:
            return f"#{pid} {p['name']}"
    return f"#{pid} (不在快照里)"


def name_to_pid(projects, name):
    if not name:
        return None
    for p in projects:
        if p["name"] == name:
            return p["id"]
    return "UNKNOWN_NAME:" + name


def run_rule(snap, projects, assert_mode):
    print("=" * 96)
    print("B 组 · 规则验算（#678 §1 定案规则的可执行定义，离线）")
    print("=" * 96)
    bad = 0
    for c in CASES_RULE:
        got, detail = detect_by_rule(c["query"], projects, snap)
        if c["expect_id"] is None:
            ok = got is None
        else:
            ok = (got == c["expect_id"])
        # spec 冲突：期望「拒绝」，但按规则会命中 —— 不静默算 FAIL，单独标出来
        conflict = ("SPEC-CONFLICT" if (c["expect_id"] is None and got is not None) else "")
        if not ok:
            bad += 1
        print(f"\n[{c['cid']}] {fmt(ok)} {conflict}   query={c['query']!r}  关系={c['relation']}")
        print(f"      期望 -> {pid_label(projects, c['expect_id'])}")
        print(f"      规则 -> {pid_label(projects, got)}")
        print(f"      依据 : {c['note']}")
        for d in detail:
            print(f"      · {d}")
    return bad


def run_live(snap, assert_mode):
    key = os.environ.get("MCP_KEY")
    url = os.environ.get("MCP_URL", "https://cloudim.club/exphub/mcp/sse")
    if not key:
        # ⚠️ 不许静默跳过：跳过了就不能说「全部符合期望」。
        # 这正是同族病（回执说做了、实际没做）—— 本条自身也曾这样错过一次。
        print("\n[SKIPPED] 未设置 MCP_KEY，A 组线上基线**一条都没跑**（不是通过）。")
        return None
    projects = snap["projects"]
    print()
    print("=" * 96)
    print(f"A 组 · 线上基线（#677 §14.2，实测 {url}）")
    print("=" * 96)
    cli = Mcp(url, key)
    bad = 0
    try:
        for c in CASES_LIVE:
            text = cli.call("search_experience", {"query": c["query"], "limit": 3})
            got_pid = name_to_pid(projects, detected_project(text))
            ok = (got_pid == c["expect_id"])
            if not ok:
                bad += 1
            print(f"\n[{c['cid']}] {fmt(ok)}   query={c['query']!r}")
            print(f"      期望 -> {pid_label(projects, c['expect_id'])}")
            print(f"      线上 -> {pid_label(projects, got_pid)}")
            print(f"      说明 : {c['note']}")
    finally:
        cli.close()
    return bad


def print_df_sql():
    print("-- 重新生成 tools/df_snapshot.json 时要跑的 SQL（全局域 = project_id IS NULL）")
    print("SELECT COUNT(*) AS n_global FROM docs WHERE status='ACTIVE' AND project_id IS NULL;")
    print("""-- 每个 term 各跑一次，取 df；term 列表见 df_snapshot.json 的 keys
SELECT COUNT(*) FROM docs d
WHERE d.status='ACTIVE' AND d.project_id IS NULL
  AND CONCAT(IFNULL(d.title,''),IFNULL(d.summary,''),IFNULL(d.tags,''),
             IFNULL(d.aliases,''),d.content) LIKE CONCAT('%', '<TERM>', '%');""")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["rule", "live", "all"], default="all")
    ap.add_argument("--assert", dest="assert_mode", action="store_true",
                    help="任一条不符合期望则退出码 1（修复后当门禁用）")
    ap.add_argument("--print-df-sql", action="store_true")
    a = ap.parse_args()

    if a.print_df_sql:
        print_df_sql()
        return 0

    snap = load_df()
    print(f"df 快照: {snap['captured_at']}  N_global={snap['n_global']}  词表 {len(snap['df_global'])} 项")
    projects = snap["projects"]

    bad = 0
    skipped = []
    executed = 0
    if a.mode in ("rule", "all"):
        bad += run_rule(snap, projects, a.assert_mode)
        executed += len(CASES_RULE)
    if a.mode in ("live", "all"):
        r = run_live(snap, a.assert_mode)
        if r is None:
            skipped.append(f"A 组 {len(CASES_LIVE)} 条（线上基线）")
        else:
            bad += r
            executed += len(CASES_LIVE)

    print()
    print("=" * 96)
    if skipped:
        print(f"⚠️ 未执行：{'、'.join(skipped)} —— 本次结果**不覆盖**这些算例")
    if bad:
        print(f"结果：{bad} 条不符合期望（已执行 {executed} 条）"
              + ("（--assert 生效）" if a.assert_mode else "（基线阶段这是预期内的，加 --assert 才会退出码 1）"))
    elif executed == 0:
        print("结果：**一条都没执行**（不是通过）")
    elif skipped:
        print(f"结果：已执行的 {executed} 条全部符合期望（**但有未执行部分，不能整体宣称通过**）")
    else:
        print(f"结果：全部符合期望（{executed} 条）")
    if a.assert_mode and skipped:
        return 1        # 有算例没跑就不许给绿灯
    return 1 if (bad and a.assert_mode) else 0


if __name__ == "__main__":
    sys.exit(main())

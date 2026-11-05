#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""项目识别 + 搜索相关性回归脚本。

2026-11-05 扩充（烛龙，#101 排期 S1）
-------------------------------------
- B 组按 #678 §5.4 定稿表扩到 7 条；B4 期望按 §5.2 裁定改为「命中项目 5」，
  SPEC-CONFLICT 撤销；② 分支补「纯 ASCII token 结构性拒绝」（§5.1）。
- 新增 C 组：搜索相关性验收集，按 scope 三档分域、每条带 project_id、
  **期望 Top3 预注册**（#677 13.7）。期望值写死在代码里，不许跑完再改。
- 传输默认改无状态 /mcp/http（2026-11-05 上线，重启不必重连）；
  --transport sse 可回退旧端点做对照。
- df 快照带过期检查：10-26 抓的 N_global=316，今天全库 ACTIVE 已 1115，
  旧 df 不能当现状用；超期只告警、不静默放行。
- 2026-11-05 S2.1：C 组统一排除 `SELF_POLLUTION_IDS`（回执/信箱文档抄了
  验收 query 原话会把自己顶到前排）；A 组改按「🔎 项目识别:」行判可测。

为什么要有这个脚本
------------------
2026-10-26 L0-1a 议题里，我们连续两回「手敲验一条就以为够了」：

  1. 只验了「不误召」（`API 接口` 被项目 5 劫持），没验「不漏召」；
     实际 `拆解宝的报废车回收` 当时就是漏的（中文不加空格是常态）。
  2. 只按长度定护栏（ASCII >=4），没验短别名 `hjs`（df 7.9%）会被一刀切掉。

所以这里把两类算例固化成可重复跑的：

  A 组（7 条）= #677 §14.2 的线上实测基线 + #1272 C9 新增的中文长句用例 A7：
                  跑**线上真实系统**，看现状。
  B 组（7 条）= #678 §5.4 定稿表的规则验算：跑**规则引用实现**（离线，不依赖部署）。
  C 组（4 条）= 搜索相关性 Top3 验收集：跑线上，输出实际排名当修复前基线。

A 组在 2026-11-05 S2.1 之前是「不可测」（scope 改造删掉了项目识别提示）。
现在服务端恢复「🔎 项目识别:」行，A 组重新可测；修完 L0-1a 后跑
`--mode live --assert` 应全绿。B 组是规则的可执行定义；若因 df 快照缺词
而红，那是**数据缺口**，不是规则错 —— 别为了测试转绿去放宽「缺数不放行」。

用法
----
  # 只跑规则验算（离线，不需要服务器）
  python3 tools/search_regression.py --mode rule

  # 跑线上基线（需要 MCP_KEY；默认走无状态 /mcp/http）
  export MCP_KEY=...
  python3 tools/search_regression.py --mode live

  # 只跑搜索相关性验收集，输出每条实际 Top5
  python3 tools/search_regression.py --mode relevance

  # 全跑（默认）
  python3 tools/search_regression.py

  # 修复后当门禁用：任一算例不符合预期 -> 退出码 1
  python3 tools/search_regression.py --mode live --assert

  # 回退旧 SSE 端点做对照
  python3 tools/search_regression.py --mode relevance --transport sse

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
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
DF_SNAPSHOT = os.path.join(HERE, "df_snapshot.json")

DEFAULT_HTTP = "https://cloudim.club/exphub/mcp/http"   # 2026-11-05 无状态端点
DEFAULT_SSE = "https://cloudim.club/exphub/mcp/sse"     # 旧端点，做对照用

# ==================== A 组：#677 §14.2 线上基线 ====================
# 期望值 = 修复后「正确」的行为（不是现状）。现状多为 FAIL，这正是基线。
# 期望一律用 project_id（稳定），项目名只用于展示。
# 教训：第一版用项目名做期望，把「拆解宝」写成期望值，而库里全名是
# 「拆解宝（报废车回收模块）」→ 首跑出现一条假 FAIL。名字会变、id 不会。
CASES_LIVE = [
    dict(cid="A1", query="拆解宝的报废车回收",
         expect_id=3, note="CJK 无空格 contain；修复前基线=漏召，C9 反向内嵌后应 PASS"),
    dict(cid="A2", query="回金算的入库单",
         expect_id=2, note="CJK 无空格 contain；修复前基线=漏召，C9 后应 PASS"),
    # 期望值已按 #1272-C21 修正（越哥 2026-11-05 批准选甲）：改为「不识别任何项目」。
    # 理由：df(忆阁)=33.9% > 30% 门槛，且 ③ 分支右邻是 ASCII `D`、不是边界，两条独立拒绝
    # 都写在 #678 §5.3 定案表里 —— 不是漏判，也不表示识别能力提升。别再改回 #6。
    dict(cid="A3", query="忆阁DeepSeek余额",
         expect_id=None, note="#1272-C21 定案：df(忆阁)=33.9% 超线 + ③ 右邻 ASCII 非边界，"
                              "定案表本就拒绝（期望值已按 C21 修正，非能力提升）"),
    dict(cid="A4", query="拆解宝 报废车回收",
         expect_id=3, note="有空格，靠精确 token 命中；修复前基线=OK"),
    dict(cid="A5", query="hjs",
         expect_id=2, note="3 字符短别名，df 7.9%；修复前基线=OK，按长度>=4 会失血"),
    dict(cid="A6", query="API 接口",
         expect_id=None, note="防误召：不得识别为任何项目；修复前基线=被项目5劫持"),
    # #1272 C9：阁主线上实测「长句含完整别名却识别为无」，这条必须进回归集，
    # 否则下次只能靠人工回读。期望值在修之前就写死（标注者≠改码者）。
    dict(cid="A7", query="工业大学校友会小程序架构说明",
         expect_id=5, note="#1272 C9 新增：中文长句内嵌完整别名「校友会小程序」；"
                           "修复前识别行=无，修复后应给出 #5"),
]

# ==================== B 组：#678 §5.4 定稿表（7 条）====================
# 关系：① A==T  ② A⊃T  ③ T⊃A
# 2026-11-05：B4 按 §5.2 裁定改为「命中项目 5」，SPEC-CONFLICT 撤销；
# 扩到 B1~B7（§5.4），② 分支补 ASCII 结构性拒绝（§5.1）。
CASES_RULE = [
    dict(cid="B1", query="hjs", relation="①",
         expect_id=2, note="df(hjs)=7.6% < 30%，相等不限长度（§5.3）"),
    dict(cid="B2", query="hjs的入库单", relation="③",
         expect_id=2, note="hjs 起头 + 后接 CJK「的」= 边界成立；df 7.6%"),
    dict(cid="B3", query="用 api 接口", relation="②",
         expect_id=None, note="token=api 纯 ASCII -> ② 结构性拒绝（§5.1），不再只靠 df 单挡"),
    dict(cid="B4", query="xy-api", relation="①",
         expect_id=5, note="§5.2 裁定：xy-api 本身是项目 5 别名项，走 ① 相等，df 0.6% -> 命中"),
    dict(cid="B5", query="xy", relation="②",
         expect_id=None, note="纯 ASCII 走 ② 被结构性拒绝；§5.4 注明这是新增漏召面，"
                              "属预期但非无损，待越哥追认"),
    dict(cid="B6", query="工业大学", relation="②",
         expect_id=5, note="② 的 CJK 正向用例；没有它 ② 就只验了拒绝"),
    dict(cid="B7", query="拆解宝（报废车回收模块）", relation="①",
         expect_id=3, note="全名相等，走 ①"),
]

# ==================== C 组：搜索相关性验收集 ====================
# 纪律（#677 13.7）：每条用例带 project_id、期望 Top3 预注册、标注者 ≠ 改码者。
# 「域分三段」按 2026-11-05 的 scope 三档重定（#678 §6.3），不抄 13.7 的二分：
#   global = 硬隔离（project_id IS NULL）/ all = 混排（默认）/ project = 指定项目
# 期望值在改代码之前写死，跑完不许改；只想看现状就传 --baseline（不判 FAIL）。
CASES_RELEVANCE = [
    dict(cid="C1", query="代码怎么发布到线上服务器", scope="all",
         project_id=None, expect_top3=[95], exclude=[1220, 1236],
         note="#1236 验收口径：#95 进前 3。修复前实测前 50 名都进不去；"
              "⚠️ 自污染源已从 #1220 转到 #1236（后者正文也写了这句原话，"
              "2026-11-05 实测排第 1），判定时排除 #1220 与 #1236；"
              "#677 九.7「别把验收 query 原话写进文档」要重新守住"),
    dict(cid="C2", query="DeepSeek 余额 用量 API 接口", scope="global",
         project_id=None, expect_top3=[332],
         note="#677 13.1 替代验收：Top3 出现 #332 且不再出现项目 5 的 AGENTS 章节；"
              "修复前 #332 第 17 位、默认只返回 12 条、噪声 84%"),
    dict(cid="C3", query="部署流程 deploy.sh", scope="all",
         project_id=None, expect_top3=[95], exclude=[1220, 1236],
         note="#1236 实测：scope=all 排除进度流水后 #95 回到第 1；"
              "同样要先排除含原话自污染的 #1220 / #1236（回归对照项）"),
    dict(cid="C4", query="DeepSeek 峰谷定价 缓存命中 价格", scope="all",
         project_id=None, expect_top3=[],
         note="#678 §3 已把第 5 组移出相关性验收集，定性「域外层」："
              "正确行为 = 少返回 + 明确提示项目域可能有，不考核 Top3，只登记形态"),
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
                    # #678 §5.1：②「项含 token」只对含 CJK 的 token 开放；
                    # 纯 ASCII token 结构性拒绝，不再只靠 df 单挡（df 会随语料漂移）。
                    if not CJK.search(tok):
                        ok, why = False, "纯 ASCII token 不许走 ②（结构性拒绝）"
                    else:
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


# 2026-11-05 S2.1：回执自污染文档。这些正文里抄过验收 query 原话，会在 C 组
# 基线上把自己顶到前排（实测污染源依次 #1220 → #1236 → #1268/#1272）。
# 判基线时排除，只保留 #95 / #332 / #678 等真实候选。
# ⚠️ 信箱（#1268/#1272）本身也是 exphub 文档，写进去同样进全文索引 —— 见 #1272 D5。
# ⚠️ 必须与 ExpHubTools.REGRESSION_POLLUTION_IDS 完全一致（SearchScopeGuardTest 有同源断言）。
# 1274 = 施工回执② 续册（#1272 C8 实测进 Top2）；677 = 搜索方案主文档（#1272 C14 实测命中片段含
# 验收 query 原话却没标），两者都补进集合。
# 注意：这是**离线判基线的排除口径**；线上标注主判据已改为内容判定（PollutionMarkers），
# 服务端不依赖这张表齐全。
SELF_POLLUTION_IDS = {1220, 1236, 1268, 1272, 1274, 678, 677, 101}


def detected_project(text):
    # 服务端 2026-11-05 S2.1 起输出「🔎 项目识别: 查询词含项目「X」（#N）」；
    # 旧格式也兼容；「🔎 项目识别: 无」返回 None。
    m = re.search(r"查询词含项目「([^」]+)」", text)
    return m.group(1) if m else None


# ==================== 无状态 /mcp/http 客户端（2026-11-05）====================

class McpStateless:
    """一次 POST 一个 JSON-RPC 请求，不握手、不带 session。

    响应可能是 application/json，也可能是 text/event-stream 的 `data: {...}`；
    两种都解析。服务端不返回 Mcp-Session-Id，重启后旧连接照样能用。
    """

    def __init__(self, url, key):
        self.url = url
        self.key = key
        self._id = 0

    def _post(self, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            self.url, data=data,
            headers={"authorization-key": self.key,
                     "Content-Type": "application/json",
                     "Accept": "application/json, text/event-stream"},
            method="POST")
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read().decode("utf-8", "replace")
        objs = []
        for line in raw.splitlines():
            s = line.strip()
            if s.startswith("data:"):
                s = s[5:].strip()
            if not s.startswith("{"):
                continue
            try:
                objs.append(json.loads(s))
            except Exception:
                continue
        if not objs:
            try:
                objs = [json.loads(raw)]
            except Exception:
                raise RuntimeError("无法解析 MCP 响应（前 200 字）: " + repr(raw[:200]))
        return objs

    def call(self, tool, args, wait=60):
        self._id += 1
        mid = self._id
        objs = self._post({"jsonrpc": "2.0", "id": mid, "method": "tools/call",
                           "params": {"name": tool, "arguments": args}})
        for obj in objs:
            if obj.get("id") != mid:
                continue
            if "error" in obj:
                raise RuntimeError("MCP error: " + json.dumps(obj["error"], ensure_ascii=False))
            content = (obj.get("result") or {}).get("content") or []
            if content:
                return content[0].get("text", "")
            return json.dumps(obj, ensure_ascii=False)
        raise RuntimeError(f"等不到 id={mid} 的响应")

    def close(self):
        pass


def make_client(transport, url, key):
    return McpStateless(url, key) if transport == "http" else Mcp(url, key)


def extract_hit_ids(text):
    """按回执出现顺序取文档 ID（去重）。brief 与非 brief 都是「【ID:N】」格式。"""
    seen, out = set(), []
    for m in re.finditer(r"【ID:(\d+)】", text or ""):
        i = int(m.group(1))
        if i not in seen:
            seen.add(i)
            out.append(i)
    return out


def snapshot_warning(snap, max_age_days):
    raw = (snap.get("captured_at") or "")[:10]
    try:
        d = datetime.strptime(raw, "%Y-%m-%d").date()
    except ValueError:
        return f"⚠️ df 快照 captured_at={raw!r} 格式不认识，按过期处理"
    age = (datetime.now().date() - d).days
    if age > max_age_days:
        return (f"⚠️ df 快照已 {age} 天（{raw}，N_global={snap['n_global']}）："
                f"文档量已变，df 不能当现状用；重抓前别拿它断言线上行为")
    return None


def run_relevance(transport, url, key, assert_mode, baseline_mode):
    """C 组：按 scope 分域、Top3 预注册。--baseline 只采集排名、不判 FAIL。"""
    print()
    print("=" * 96)
    print(f"C 组 · 搜索相关性（scope 分域 / Top3 预注册）  传输={transport}  {url}")
    print("=" * 96)
    if not key:
        print("[SKIPPED] 未设置 MCP_KEY，C 组**一条都没跑**（不是通过）。")
        return None
    cli = make_client(transport, url, key)
    bad = 0
    executed = 0
    try:
        for c in CASES_RELEVANCE:
            args = {"query": c["query"], "limit": 50, "scope": c["scope"]}
            text = cli.call("search_experience", args)
            ids = extract_hit_ids(text)
            exclude = set(c.get("exclude", [])) | SELF_POLLUTION_IDS
            ranked = [i for i in ids if i not in exclude]
            top5 = ranked[:5]
            targets = {i: (ranked.index(i) + 1 if i in ranked else None)
                       for i in c["expect_top3"]}
            if c["expect_top3"]:
                ok = any(p is not None and p <= 3 for p in targets.values())
            else:
                ok = True       # 域外层只登记形态，不考核 Top3（#678 §3）
            executed += 1
            if not ok and not baseline_mode:
                bad += 1
            label = "BASELINE" if baseline_mode else fmt(ok)
            print(f"\n[{c['cid']}] {label}   scope={c['scope']}  query={c['query']!r}")
            print(f"      Top5 -> {top5 or '(无命中)'}")
            print(f"      已排除自污染 ID -> {sorted(exclude)}")
            if targets:
                print("      期望文档排名 -> " + ", ".join(
                    f"#{i}: {'第 ' + str(p) + ' 位' if p else '未进前 50'}"
                    for i, p in targets.items()))
            else:
                hint = "有域提示" if "项目「" in (text or "") else "未见域提示"
                print(f"      域外层形态 -> 返回 {len(ids)} 条 / {hint}（不判 FAIL）")
            print(f"      口径 : {c['note']}")
    finally:
        cli.close()
    return bad


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


def run_live(snap, assert_mode, transport, url, key):
    if not key:
        # ⚠️ 不许静默跳过：跳过了就不能说「全部符合期望」。
        # 这正是同族病（回执说做了、实际没做）—— 本条自身也曾这样错过一次。
        print("\n[SKIPPED] 未设置 MCP_KEY，A 组线上基线**一条都没跑**（不是通过）。")
        return None
    projects = snap["projects"]
    print()
    print("=" * 96)
    print(f"A 组 · 线上基线（#677 §14.2，实测 {url}，传输={transport}）")
    print("=" * 96)
    cli = make_client(transport, url, key)
    bad = 0
    texts = []
    try:
        for c in CASES_LIVE:
            # 固定 scope=global：2026-11-05 起 scope=all 不再输出尾行项目段
            # （#1236「renderProjectSegment 不再输出」），项目识别在 global 档才看得见。
            text = cli.call("search_experience",
                            {"query": c["query"], "limit": 3, "scope": "global"})
            texts.append(text)
            got_pid = name_to_pid(projects, detected_project(text))
            ok = (got_pid == c["expect_id"])
            if not ok:
                bad += 1
            print(f"\n[{c['cid']}] {fmt(ok)}   query={c['query']!r}")
            print(f"      期望 -> {pid_label(projects, c['expect_id'])}")
            print(f"      线上 -> {pid_label(projects, got_pid)}")
            print(f"      说明 : {c['note']}")
        if not any("🔎 项目识别:" in t for t in texts):
            print(f"\n⚠️ A 组当前**不可测**：{len(CASES_LIVE)} 条回执里连一行「🔎 项目识别:」都没有。")
            print("   2026-11-05 S2.1 已在 ExpHubTools 恢复该行（非 brief 模式）。")
            print("   仍缺说明部署没生效、或跑的是 brief / 0 结果分支；上面的 FAIL 全是假 FAIL")
            print("   全是**假 FAIL**，不是线上退化，别拿来当基线。")
            return None
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
    ap.add_argument("--mode", choices=["rule", "live", "relevance", "all"], default="all")
    ap.add_argument("--assert", dest="assert_mode", action="store_true",
                    help="任一条不符合期望则退出码 1（修复后当门禁用）")
    ap.add_argument("--baseline", action="store_true",
                    help="只采集现状排名、不判 FAIL（修复前基线采集用）")
    ap.add_argument("--transport", choices=["http", "sse"], default="http",
                    help="http=无状态 /mcp/http（默认）；sse=旧端点对照")
    ap.add_argument("--max-age-days", type=int, default=7,
                    help="df 快照超过几天就告警（默认 7）")
    ap.add_argument("--print-df-sql", action="store_true")
    a = ap.parse_args()

    if a.print_df_sql:
        print_df_sql()
        return 0

    snap = load_df()
    print(f"df 快照: {snap['captured_at']}  N_global={snap['n_global']}  词表 {len(snap['df_global'])} 项")
    warn = snapshot_warning(snap, a.max_age_days)
    if warn:
        print(warn)
    projects = snap["projects"]

    key = os.environ.get("MCP_KEY")
    url = os.environ.get("MCP_URL") or (DEFAULT_HTTP if a.transport == "http" else DEFAULT_SSE)

    bad = 0
    skipped = []
    executed = 0
    if a.mode in ("rule", "all"):
        bad += run_rule(snap, projects, a.assert_mode)
        executed += len(CASES_RULE)
    if a.mode in ("live", "all"):
        r = run_live(snap, a.assert_mode, a.transport, url, key)
        if r is None:
            skipped.append(f"A 组 {len(CASES_LIVE)} 条（线上基线）")
        else:
            bad += r
            executed += len(CASES_LIVE)
    if a.mode in ("relevance", "all"):
        r = run_relevance(a.transport, url, key, a.assert_mode, a.baseline)
        if r is None:
            skipped.append(f"C 组 {len(CASES_RELEVANCE)} 条（搜索相关性）")
        else:
            bad += r
            executed += len(CASES_RELEVANCE)

    print()
    print("=" * 96)
    if skipped:
        print(f"⚠️ 未执行：{'、'.join(skipped)} —— 本次结果**不覆盖**这些算例")
    if a.baseline:
        print("本次为基线采集：只记录现状排名，不判定 PASS/FAIL。")
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

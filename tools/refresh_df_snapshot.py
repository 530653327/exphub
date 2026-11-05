#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""重抓 tools/df_snapshot.json（#101 排期 S2.1 第一前置，2026-11-05 烛龙）。

为什么要有这个脚本
------------------
旧快照抓于 2026-10-26：N_global=316、词表 40 项、projects 只有 6 个。
2026-11-05 现查：全局域 ACTIVE 491、全库 ACTIVE 1124、projects 17 个。
B 组 7 条验算里 B6「工业大学」/ B7「拆解宝（报废车回收模块）」因
`df=UNKNOWN -> 缺数不放行` 被误拒（见 #1268 B1）。**规则没错，是数据过期。**

用法
----
  python3 tools/refresh_df_snapshot.py                  # 走 ssh myserver，写回 df_snapshot.json
  python3 tools/refresh_df_snapshot.py --dry-run        # 只打印摘要，不落盘
  python3 tools/refresh_df_snapshot.py --ssh-host NAME  # 换 ssh 别名

只读：不写库、不重启、不碰部署。凭据来自服务器 /etc/exphub/.env，
**本地与仓库不含任何密钥**。df 口径与 #677 12.2 一致：项目识别发生在全局域，
分母固定为 status='ACTIVE' AND project_id IS NULL 的文档数；
命中判据与旧快照一致（title/summary/tags/aliases/content 拼接后 LIKE）。
"""

import argparse
import base64
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DF_SNAPSHOT = os.path.join(HERE, "df_snapshot.json")
# L0-1a：服务端判定读的是 classpath 里那份，重抓必须同步，否则线上用旧语料
RES_SNAPSHOT = os.path.join(os.path.dirname(HERE), "src", "main", "resources", "df_snapshot.json")

# #677 12.5 的泛词常量 + 回归脚本 B 组/A 组会用到的 token
GENERIC = [
    "问题", "方案", "数据", "项目", "系统", "代码", "配置", "API", "api", "服务", "工具",
    "接口", "价格", "缓存", "流程", "模块", "功能", "设计", "实现", "查询", "测试",
    "管理", "经验", "助手", "部署", "入库单", "回收", "拆解", "报废车", "知识",
]
EXTRA = [
    # B6/B7 与 #678 §5.4 定稿表里的词（别名项切不出来的）
    "工业大学", "天津工业大学", "天津工业大学校友会", "拆解宝（报废车回收模块）",
    "报废车回收模块", "xy", "xy-api", "hjs", "HJS", "TJPU", "army",
    "coolshiyue/army", "MemHaven", "DeepSeek", "余额", "用量", "缓存命中",
    "拆解宝", "回金算", "忆阁", "校友会", "Jarvis",
]

# 远端只读脚本。TERMS 由本地注入（base64 传整段脚本，stdin 不占，避免引号地狱）。
REMOTE = r'''
import os, re, sys, io, json, subprocess, datetime
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
TERMS = __TERMS__

env = {}
for line in open("/etc/exphub/.env", encoding="utf-8", errors="replace"):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip().strip('"').strip("'")

m = re.match(r"jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)", env.get("EXPHUB_DB_URL", ""))
if not m:
    print("__RESULT__" + json.dumps({"error": "DB_URL parse failed"}, ensure_ascii=False))
    raise SystemExit(0)
host, port, db = m.group(1), m.group(2) or "3306", m.group(3)
e = os.environ.copy()
e["MYSQL_PWD"] = env.get("EXPHUB_DB_PASSWORD", "")

def q(sql):
    # SQL 走 stdin，不进命令行参数：服务器 Python 3.6 的 locale 是 ASCII，
    # 带中文的 -e 参数会在 subprocess 编码时直接 UnicodeEncodeError。
    r = subprocess.run(
        ["mysql", "--default-character-set=utf8mb4", "-h", host, "-P", port,
         "-u", env.get("EXPHUB_DB_USERNAME", ""), db, "-N"],
        env=e, input=sql.encode("utf-8"),
        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if r.returncode:
        return None, r.stderr.decode("utf-8", "replace").strip()[:300]
    return r.stdout.decode("utf-8", "replace"), None

rows, err = q("SELECT id, project_code, name, IFNULL(aliases,''), IFNULL(status,'') "
              "FROM projects ORDER BY id")
if err:
    print("__RESULT__" + json.dumps({"error": "projects: " + err}, ensure_ascii=False))
    raise SystemExit(0)

projects, terms = [], set(TERMS)
for line in (rows or "").strip().splitlines():
    parts = line.split("\t")
    if len(parts) < 5:
        continue
    pid, code, name, aliases, status = parts[:5]
    if status and status != "ACTIVE":
        continue
    projects.append({"id": int(pid), "name": name, "project_code": code, "aliases": aliases})
    for x in [name, code] + re.split(r"[,，]", aliases):
        x = x.strip()
        if len(x) >= 2:
            terms.add(x)

# LIKE 通配符与反斜杠的词不进 SQL（词表可控，挡一道即可）
terms = sorted(t for t in terms if len(t) >= 2
               and "%" not in t and "_" not in t and "\\" not in t)

expr = ("CONCAT_WS(' ', IFNULL(title,''), IFNULL(summary,''), IFNULL(tags,''), "
        "IFNULL(aliases,''), IFNULL(content,''))")
cols = ["COUNT(*)"] + [
    "SUM(CASE WHEN %s LIKE '%%%s%%' THEN 1 ELSE 0 END)" % (expr, t.replace("'", "''"))
    for t in terms
]
out, err = q("SELECT " + ", ".join(cols) +
             " FROM docs WHERE status='ACTIVE' AND project_id IS NULL")
if err:
    print("__RESULT__" + json.dumps({"error": "df: " + err}, ensure_ascii=False))
    raise SystemExit(0)

vals = (out or "").strip().split("\t")
n_global = int(vals[0])
df_global = {}
for i, t in enumerate(terms):
    v = vals[i + 1] if i + 1 < len(vals) else "0"
    df_global[t] = 0 if v in ("NULL", "") else int(v)

result = {
    "captured_at": datetime.datetime.now().strftime("%Y-%m-%dT%H:%M:%S+08:00"),
    "n_global": n_global,
    "df_global": df_global,
    "projects": projects,
}
print("__RESULT__" + json.dumps(result, ensure_ascii=False))
'''


def build_terms(old):
    terms = set(old.get("df_global", {}).keys()) | set(GENERIC) | set(EXTRA)
    for p in old.get("projects", []):
        for x in ([p.get("name", ""), p.get("project_code", "")]
                  + re.split(r"[,，]", p.get("aliases", "") or "")):
            x = x.strip()
            if len(x) >= 2:
                terms.add(x)
    return sorted(terms)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="只打印摘要，不写回文件")
    ap.add_argument("--ssh-host", default="myserver", help="ssh 别名，默认 myserver")
    a = ap.parse_args()

    with open(DF_SNAPSHOT, encoding="utf-8") as fh:
        old = json.load(fh)
    print("旧快照: %s  N_global=%s  词表 %d 项  projects %d 个" % (
        old.get("captured_at"), old.get("n_global"),
        len(old.get("df_global", {})), len(old.get("projects", []))))

    terms = build_terms(old)
    src = REMOTE.replace("__TERMS__", repr(terms))
    code = base64.b64encode(src.encode("utf-8")).decode("ascii")
    cmd = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", a.ssh_host,
           "python3 -c \"import base64;exec(base64.b64decode('%s').decode('utf-8'))\"" % code]
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    out = r.stdout.decode("utf-8", "replace")
    line = next((l for l in out.splitlines() if l.startswith("__RESULT__")), None)
    if not line:
        print("刷新失败（无结果行）:\n" + out[-600:])
        print(r.stderr.decode("utf-8", "replace")[-400:])
        return 1
    result = json.loads(line[len("__RESULT__"):])
    if "error" in result:
        print("刷新失败:", result["error"])
        return 1

    df = result["df_global"]
    print("新快照: %s  N_global=%d  词表 %d 项  projects %d 个" % (
        result["captured_at"], result["n_global"], len(df), len(result["projects"])))
    for t in ("工业大学", "拆解宝（报废车回收模块）", "hjs", "xy-api", "api", "API"):
        v = df.get(t)
        pct = ("%.1f%%" % (v * 100.0 / result["n_global"])) if v is not None else "UNKNOWN"
        print("  df(%-18s) = %-5s  占全局域 %s" % (t, v if v is not None else "-", pct))

    if a.dry_run:
        print("--dry-run：未写回文件")
        return 0
    payload = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    with open(DF_SNAPSHOT, "w", encoding="utf-8") as fh:
        fh.write(payload)
    print("已写回", DF_SNAPSHOT)
    if os.path.exists(RES_SNAPSHOT):
        with open(RES_SNAPSHOT, "w", encoding="utf-8") as fh:
            fh.write(payload)
        print("已同步服务端资源", RES_SNAPSHOT,
              "（AliasMatcherTest.dfSnapshotIsInSyncWithTools 会钉住两份一致）")
    else:
        print("⚠️ 未找到", RES_SNAPSHOT, "—— 服务端仍会读到旧语料，请手动同步")
    return 0


if __name__ == "__main__":
    sys.exit(main())

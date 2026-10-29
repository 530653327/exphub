#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对账「项目段 N」到底等于哪一条 count(*)。

为什么要有这个脚本
------------------
2026-10-30 阁主退单时点出：N 走的是 `terms.toOrQuery()`，中文概念会被展开成 2-gram ——
`阿拉善 植树赛事 名额` 真正送进 `countByFulltext` 的串是

    +(阿拉善 阿拉 拉善 植树赛事 植树 树赛 赛事 名额)

手抄成 `+(阿拉善 植树赛事 名额)` 会数出 5，与回执里的 6 差一条，而且**查不出原因**。
把这条坑只写进文档、靠人记，下次一定还会踩。所以做成命令：

  1. 跑一次线上 search_experience，从回执里取出「对账用串」和 N；
  2. 把**同一串**原样喂给 MySQL，分别数全局域与项目域；
  3. 三个数并列打出来，不一致就退出码 1。

用法
----
  export MCP_KEY=...  MCP_URL=https://cloudim.club/exphub/mcp/sse

  # 只出串和 SQL（不连库）
  python3 tools/reconcile_search_count.py "阿拉善 植树赛事 名额"

  # 连库直接对账（需要 ssh 到部署机，DB 口令从 /etc/exphub/.env 读）
  python3 tools/reconcile_search_count.py "阿拉善 植树赛事 名额" --ssh myserver

  # 指定项目域（默认用回执里识别到的项目 code）
  python3 tools/reconcile_search_count.py "忆阁 记忆 检索" --project memhaven --ssh myserver
"""

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.request

FAR_SQL = """SELECT {what} FROM docs
 WHERE {scope} AND status='ACTIVE'{tt}
   AND MATCH(title,content,aliases,summary,tags)
       AGAINST('{q}' IN BOOLEAN MODE);"""


class Mcp:
    def __init__(self, url, key):
        self.url, self.key = url, key
        self._id = 0
        req = urllib.request.Request(url, headers={"authorization-key": key,
                                                   "Accept": "text/event-stream"})
        self.resp = urllib.request.urlopen(req, timeout=60)
        self.msg_url = None
        for _ in range(200):
            line = self.resp.readline().decode("utf-8", "replace")
            if not line:
                break
            m = re.search(r"(/mcp/message\?sessionId=[A-Za-z0-9_-]+)", line)
            if m:
                base = url[:-len("/mcp/sse")] if url.endswith("/mcp/sse") else url
                self.msg_url = base + m.group(1)
                break
        if not self.msg_url:
            raise RuntimeError("拿不到 SSE session endpoint")
        self._post({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                    "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                               "clientInfo": {"name": "reconcile", "version": "1"}}})
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})
        self._id = 1

    def _post(self, payload):
        req = urllib.request.Request(self.msg_url, data=json.dumps(payload).encode(),
                                     headers={"authorization-key": self.key,
                                              "Content-Type": "application/json"}, method="POST")
        try:
            urllib.request.urlopen(req, timeout=40).read()
        except Exception:
            pass

    def tool(self, name, args, wait=90):
        self._id += 1
        mid = self._id
        self._post({"jsonrpc": "2.0", "id": mid, "method": "tools/call",
                    "params": {"name": name, "arguments": args}})
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
            txt = content[0].get("text", "") if content else json.dumps(obj, ensure_ascii=False)
            # 服务端把回执再 JSON 编码了一层（content.text 是带引号的字符串字面量），
            # 不解开的话正则会把结尾的 \n" 也当成正文的一部分。
            if isinstance(txt, str) and txt[:1] == '"':
                try:
                    txt = json.loads(txt)
                except Exception:
                    pass
            return txt
        raise RuntimeError("等不到响应")


def run_sql(ssh_host, sql):
    """在部署机上执行一条 SQL，返回单值（失败返回 None）。"""
    script = (
        'PW=$(grep "^EXPHUB_DB_PASSWORD=" /etc/exphub/.env | cut -d= -f2-)\n'
        'MYSQL_PWD="$PW" mysql -h127.0.0.1 -uroot exphub -N -e ' + shell_quote(sql) + '\n'
    )
    p = subprocess.run(["ssh", "-o", "ConnectTimeout=10", "-o", "BatchMode=yes",
                        ssh_host, "bash -s"], input=script.encode(),
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
    out = p.stdout.decode("utf-8", "replace").strip().splitlines()
    for line in out:
        if line.strip().isdigit():
            return int(line.strip())
    sys.stderr.write("SQL 执行失败：%s\n%s\n" % (p.stderr.decode("utf-8", "replace")[:400], sql))
    return None


def shell_quote(s):
    return "'" + s.replace("'", "'\"'\"'") + "'"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("query")
    ap.add_argument("--project", help="项目 code（默认取回执里识别到的那个）")
    ap.add_argument("--ssh", help="部署机 ssh 别名；给了才真查库")
    ap.add_argument("--template", help="templateType，回执里带类型过滤时用它保持同口径")
    ap.add_argument("--status", default="ACTIVE")
    args = ap.parse_args()

    url = os.environ.get("MCP_URL", "https://cloudim.club/exphub/mcp/sse")
    key = os.environ.get("MCP_KEY")
    if not key:
        sys.exit("缺 MCP_KEY（export MCP_KEY=... MCP_URL=...）")

    call = {"query": args.query, "limit": 1}
    if args.template:
        call["templateType"] = args.template
    text = Mcp(url, key).tool("search_experience", call)

    m_n = re.search(r"提到这组词的文档共 (\d+) 条", text)
    m_q = re.search(r"对账用串（原样传 countByFulltext，别手抄）：(.+)$", text, re.MULTILINE)
    m_code = re.search(r"projectName=([A-Za-z0-9_\-]+)】", text)
    if not (m_n and m_q):
        print(text)
        sys.exit("回执里没有项目段（N 或对账用串）。这个查询没有项目命中，没有可对账的 N。")

    n, or_query = int(m_n.group(1)), m_q.group(1)
    code = args.project or (m_code.group(1) if m_code else None)

    print("查询        : %s" % args.query)
    print("对账用串    : %s" % or_query)
    print("回执里的 N  : %d  （项目域，纯 DB 计数口径）" % n)
    if code:
        print("项目 code   : %s" % code)
    if not args.ssh:
        print("\n-- 未给 --ssh，只打印 SQL --")
        print(FAR_SQL.format(what="COUNT(*) AS n", scope="project_id = <项目ID>",
                             tt=(" AND template_type='%s'" % args.template) if args.template else "",
                             q=or_query))
        return 0

    project_id = None
    if code:
        project_id = run_sql(args.ssh, "SELECT id FROM projects WHERE project_code=%s;"
                             % shell_quote(code))
    tt = (" AND template_type='%s'" % args.template) if args.template else ""
    status_scope = "status IN (%s)" % ",".join("'%s'" % s for s in args.status.split(","))

    global_n = run_sql(args.ssh, FAR_SQL.replace(" AND status='ACTIVE'", " AND " + status_scope).format(
        what="COUNT(*)", scope="project_id IS NULL", tt=tt, q=or_query))
    proj_n = None
    if project_id is not None:
        proj_n = run_sql(args.ssh, FAR_SQL.replace(" AND status='ACTIVE'", " AND " + status_scope).format(
            what="COUNT(*)", scope="project_id = %d" % project_id, tt=tt, q=or_query))

    print("SQL 全局域   : %s" % global_n)
    print("SQL 项目域   : %s" % proj_n)
    ok = proj_n is not None and proj_n == n
    print("\n结论        : %s" % ("N 与项目域 count(*) 一致 ✅" if ok else "N 与项目域 count(*) 不一致 ❌"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

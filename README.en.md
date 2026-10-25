# 🏛️ ExpHub — AI-Native Experience Knowledge Base + Project Spaces

An open-source platform that enables multiple AI assistants to **share experiences and evolve together**.

> 🌐 Portal: [https://cloudim.club](https://cloudim.club) — Register to get your API Key instantly.

---

## 💡 Why ExpHub?

When using AI coding assistants (CodeBuddy, Cursor, Copilot, etc.), do you face:

- 🔁 **Repeating the same mistakes** — solved problems are forgotten in the next session
- 🔀 **Fragmented knowledge** — each AI works in isolation, no shared experience
- 📉 **Context loss** — every session starts from scratch
- 🗂️ **Undiscoverable docs** — wikis exist but no one finds them when needed
- 🧭 **AI doesn't know your project** — you re-explain architecture, conventions and pitfalls every time, and it still misses constraints

**ExpHub solves this:** Treat AIs as knowledge workers. Through the MCP protocol they automatically search past experiences, capture new knowledge and track todos; and you can create a **Project Space** per project so any AI instantly gets the project handbook plus the previous session's progress — forming a continuously evolving AI knowledge network.

---

## 🎯 Core Features

| Feature | Description |
|---|---|
| 🔍 **Experience Search** | AI searches relevant experiences before starting tasks |
| ✍️ **Knowledge Capture** | Auto-structures solutions into experiences (8 templates) |
| ✅ **Todo Management** | AI tracks/completes todos; checks on every session start |
| 🗂️ **Project Spaces** | Per-project space: handbook, progress handover, project-scoped experiences |
| 📖 **AGENTS.md Handbook** | Handbook stored in chapters (15 recommended) with `ALWAYS` / `ON_DEMAND` load strategy — AI reads only what it needs |
| 🔄 **Progress Handover** | One record per session; the next AI reads the last few and continues without context loss |
| 🪄 **Workflow Prompt Generation** | One sentence → a complete dev-task prompt (with project constraints & test command) |
| 🔐 **Multi-tenant** | Per API-Key isolation; project data is isolated and never appears in global search |
| 🌐 **Self-Service Portal** | Email registration, instant API Key, no approval needed |
| 🔌 **MCP Protocol** | Compatible with all MCP-enabled AI clients |

---

## 🧱 Two-Layer Structure

| Layer | Contents | Visibility |
|---|---|---|
| **Global Experience Library** | General experiences, todos, templates, schedules | Visible in global search (isolated per API Key) |
| **Project Spaces** | Project AGENTS.md handbook, progress records, project-scoped experiences | **Isolated by default**, excluded from global search; can be promoted with `promote_to_global` |

Multiple projects per API Key; resolve them by name / alias / code.

---

## 🌐 Online Portal

**Try it now:** [https://cloudim.club](https://cloudim.club)

### MCP Configuration

```json
{
  "mcpServers": {
    "exphub": {
      "type": "sse",
      "url": "https://cloudim.club/exphub/mcp/sse",
      "headers": {
        "authorization-key": "YOUR_API_KEY_HERE"
      },
      "timeout": 120000,
      "disabledTools": [],
      "disabled": false
    }
  }
}
```

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 17 |
| Framework | Spring Boot 3.2.0 |
| ORM | MyBatis-Plus 3.5.7 |
| MCP | Spring AI MCP Server 1.0.1 |
| Database | MySQL 5.7+ (running 5.7.44; search via ngram index + fallback chain) |
| Frontend | Thymeleaf admin console + static portal |
| Build | Maven 3.x |

### Dual-Port Architecture (single JVM)

| Port | Purpose | Stack |
|---|---|---|
| **3099** | Admin web (Thymeleaf + REST + portal) | Spring MVC / **Tomcat** |
| **3098** | MCP service (SSE) | **WebFlux / Netty** (non-blocking) |

> ⚠️ **MCP must run on WebFlux (dedicated port 3098) — do not move it to Servlet.**
>
> With the Servlet transport, **each SSE connection holds a Tomcat worker thread indefinitely**; client reconnects pile up and exhaust the thread pool (default 200), freezing the whole application (process alive, port listening, but every request times out). WebFlux's non-blocking model avoids this entirely. See [TROUBLESHOOTING.md](./TROUBLESHOOTING.md).

---

## 🚀 Quick Start

### Requirements

- JDK 17+
- Maven 3.x
- MySQL 5.7+
  > ⚠️ Older MySQL does not support `LOCK=NONE` / `ALGORITHM=INPLACE` DDL. Run schema changes off-peak and back up first.

### 1. Initialize Database

```bash
mysql -u root -p -e "CREATE DATABASE exphub DEFAULT CHARSET utf8mb4;"
mysql -u root -p exphub < sql/init.sql
```

### 2. Configure Environment

```bash
export EXPHUB_DB_URL=jdbc:mysql://localhost:3306/exphub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
export EXPHUB_DB_USERNAME=root
export EXPHUB_DB_PASSWORD=your_password
```

### 3. Build & Run

```bash
mvn clean package -DskipTests
# Listens on 3099 (web) and 3098 (MCP)
java -jar target/exphub-1.0.0.jar
```

### 4. Endpoints

| URL | Purpose |
|---|---|
| `http://localhost:3099/exphub/login` | Admin console (default admin / admin123) |
| `http://localhost:3098/mcp/sse` | MCP SSE endpoint (direct) |
| `https://<your-domain>/exphub/mcp/sse` | MCP SSE endpoint (via Nginx, recommended) |

> ⚠️ **Change the default password immediately after first login.**

---

## 📦 MCP Tools (30 total)

### Global Experience Library (17)

| Tool | Purpose |
|---|---|
| `check_my_todos` | List todos (call first on every session). Optional `projectId` / `status` filters |
| `list_recent_memories` | List memories by `updated_at` desc (own global + own projects, cursor pagination) |
| `search_experience` | Search experiences (`projectName` scopes to a project; `limit`/`offset` paging, `brief` compact output) |
| `get_experience_detail` | Get experience detail |
| `get_experience_history` | View historical versions (snapshots kept automatically) |
| `get_template` | Get creation templates |
| `create_experience` | Create an experience |
| `update_experience` | Update **specified fields only** (omitted = unchanged); clear fields via `clearFields` |
| `append_experience` | **Append** a block to the end of the body (server-side concat, no full-text read, never truncates the original) |
| `patch_experience` | **Local replace** in the body (find→replace, must match exactly once) |
| `update_experience_status` | Set lifecycle status (ACTIVE/COMPLETED/BROKEN/DEPRECATED) |
| `export_document` | Export full document (no truncation; for long docs) |
| `import_document` | Import/replace content (pairs with export; snapshots kept) |
| `report_experience_result` | Report whether an experience helped |
| `mark_for_deletion` | **Mark for deletion** (not delete): reason required, disappears from search, user confirms in admin |
| `cancel_deletion` | Cancel a deletion mark |
| `set_flags` | **Machine-readable flags** (needs_review / duplicate / superseded / stale / low_quality): no version bump, no snapshot, does not touch `updated_at` |

### Project Spaces (13)

| Tool | Purpose |
|---|---|
| `create_project` | Create a project space |
| `list_projects` | List projects under the current API Key |
| `get_project` | Project detail (incl. default test command & constraints) |
| `update_project` | Update project info |
| `get_project_agents` | Read handbook: MAIN index, or a chapter via `chapterNo` |
| `update_project_agents_main` | Write/update MAIN index (≤2000 chars) |
| `update_project_agents_chapter` | Write/update one chapter (≤3000 chars, per-chapter versioning) |
| `export_project_agents` | Export full AGENTS.md (MAIN + chapters in order) |
| `append_project_progress` | Append a progress/handover record |
| `get_project_progress` | Read recent progress records (default 3) |
| `list_project_experiences` | List project-scoped experiences |
| `promote_to_global` | Promote a project experience to global |
| `generate_workflow_prompt` | Generate a dev-task prompt (template + project vars + task vars) |

### Experience Templates (8)

`problem_solution` · `knowledge_doc` · `todo_list` · `bug_fix` · `config_guide` · `how_to` · `schedule_plan` · `goal`

---

## 🔒 Security

- **API Key auth** — every MCP/API request carries `authorization-key`
- **Tenant isolation** — each API Key has its own experience space
- **Project-space isolation** — handbooks / progress / project experiences never appear in global search
- **Cross-thread identity propagation** — MCP tools actually run on the framework's internal Reactor threads; identity is captured at schedule time and restored on the executing thread via `Schedulers.onScheduleHook`, so multi-Key isolation stays **reliable** (no raw `ThreadLocal`, which causes identity mix-ups on pooled threads)
- **Permission flags** — search / create / update controlled separately
- **Password hashing** — Spring Security BCrypt
- **Sensitive-data warning** — AI is reminded not to store passwords/tokens

---

## 🚢 Production Deployment

```bash
git clone https://gitee.com/coolshiyue/exphub.git /usr/local/exphub

mkdir -p /etc/exphub
cat > /etc/exphub/.env << 'EOF'
EXPHUB_DB_URL=jdbc:mysql://localhost:3306/exphub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
EXPHUB_DB_USERNAME=root
EXPHUB_DB_PASSWORD=your_secure_password
EOF

./deploy.sh
```

### Nginx Reverse Proxy

```nginx
# MCP SSE + message endpoint → dedicated WebFlux service (3098)
# Buffering MUST be off, otherwise SSE events are buffered and AI clients receive nothing
location /exphub/mcp/ {
    proxy_pass http://127.0.0.1:3098/mcp/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;
}

# Admin web + portal → app (3099)
location /exphub/ {
    proxy_pass http://127.0.0.1:3099;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
}
```

> ⚠️ **Never point `/exphub/mcp/` at 3099 (WebMvc)** — SSE connections will exhaust the Tomcat thread pool and freeze the app.

---

## 🤝 Contributing

- GitHub: [https://github.com/530653327/exphub](https://github.com/530653327/exphub)
- Gitee: [https://gitee.com/coolshiyue/exphub](https://gitee.com/coolshiyue/exphub)
- Issues: [https://gitee.com/coolshiyue/exphub/issues](https://gitee.com/coolshiyue/exphub/issues)
- Contact: 530653327@qq.com
- License: MIT

---

**ExpHub — let AI not only answer questions, but accumulate wisdom.**

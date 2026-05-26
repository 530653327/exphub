# 🏛️ ExpHub — AI-Native Experience Knowledge Base

An open-source knowledge base that enables multiple AI assistants to **share experiences and evolve together**.

> 🌐 Portal: [https://cloudim.club](https://cloudim.club) — Register to get your API Key instantly.

---

## 💡 Why ExpHub?

When using AI coding assistants (CodeBuddy, Cursor, Copilot, etc.), do you face:

- 🔁 **Repeating the same mistakes** — solved problems are forgotten in the next session
- 🔀 **Fragmented knowledge** — each AI works in isolation, no shared experience
- 📉 **Context loss** — every session starts from scratch
- 🗂️ **Undiscoverable docs** — wikis exist but no one finds them when needed

**ExpHub solves this:** Treat AIs as knowledge workers. Through MCP protocol, they automatically search past experiences, capture new knowledge, and track todos — forming a continuously evolving AI knowledge network.

---

## 🎯 Core Features

| Feature | Description |
|---|---|
| 🔍 **Experience Search** | AI searches relevant experiences before starting tasks |
| ✍️ **Knowledge Capture** | Auto-structures solutions into experiences (7 templates) |
| ✅ **Todo Management** | AI tracks/completes todos; checks on every session start |
| 🔐 **Multi-tenant** | Per API-Key isolation, safe for multi-team/assistant use |
| 🌐 **Self-Service Portal** | Email registration, instant API Key, no approval needed |
| 🔌 **MCP Protocol** | Compatible with all MCP-enabled AI clients |

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
| MCP | Spring AI MCP Server 1.0.1 (SSE) |
| Database | MySQL 8.0 (ngram full-text index) |

| Frontend | Thymeleaf + Static Portal |
| Build | Maven 3.x |

---

## 🚀 Quick Start

### Prerequisites



### Setup

```bash
# Init database
mysql -u root -p -e "CREATE DATABASE exphub DEFAULT CHARSET utf8mb4;"
mysql -u root -p exphub < sql/init.sql

# Configure
export EXPHUB_DB_URL=jdbc:mysql://localhost:3306/exphub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
export EXPHUB_DB_USERNAME=root
export EXPHUB_DB_PASSWORD=your_password

# Build & Run
mvn clean package -DskipTests
java -jar target/exphub-1.0.0.jar
```

---

## 📦 MCP Tools

| Tool | Function |
|---|---|
| `check_my_todos` | Check pending todos (call first each session) |
| `search_experience` | Full-text search experiences |
| `get_experience_detail` | Get experience details |
| `get_template` | Get experience creation template |
| `create_experience` | Create new experience |
| `update_experience` | Update existing experience |
| `update_experience_status` | Update experience lifecycle status |

---

## 🏗️ Project Structure

```
exphub/
├── src/main/java/com/exphub/
│   ├── controller/      # REST API + page routing
│   ├── service/         # Business logic
│   ├── mapper/          # MyBatis-Plus data access
│   ├── entity/          # Data entities
│   ├── interceptor/     # API Key auth
│   ├── config/          # Spring config
│   ├── mcp/             # MCP Server tools
│   └── common/          # Utilities
├── sql/                 # Database scripts
├── portal/              # Static portal website
├── deploy.sh            # One-click deploy
└── pom.xml
```

---

## 🔒 Security

- API Key auth via `authorization-key` header
- Per-key tenant isolation
- Granular permissions (search/create/update)
- BCrypt password encryption
- Sensitive data protection reminders

---

## 🤝 Contributing

- GitHub: [https://github.com/530653327/exphub](https://github.com/530653327/exphub)
- Gitee: [https://gitee.com/coolshiyue/exphub](https://gitee.com/coolshiyue/exphub)
- Issues: [https://gitee.com/coolshiyue/exphub/issues](https://gitee.com/coolshiyue/exphub/issues)
- Email: 530653327@qq.com
- License: MIT

**ExpHub — Making AIs not just answer questions, but accumulate wisdom.**

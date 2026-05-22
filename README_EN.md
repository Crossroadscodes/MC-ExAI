# ExAI — AI Assistant Plugin for Minecraft

[![Version](https://img.shields.io/badge/version-1.0.2-blue)](https://github.com/Crossroadscodes/MC-ExAI)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

**Languages / 语言**: [简体中文](README.md) · [English](README_EN.md)

ExAI is an LLM-powered AI assistant plugin for Minecraft servers. It supports public-chat auto-replies, in-game GUI conversations, and a community-curated knowledge base with RAG retrieval.

---

## Features

### 1. Public-chat auto-reply
- Listens to player chat and detects question keywords (`?`, `how`, `what`, `why`, etc.)
- Generates AI replies asynchronously and broadcasts them to chat
- Configurable cooldown to prevent spam
- Optional suffix to guide players to the GUI for deeper conversation

### 2. GUI chat system
- Open the main menu via `/exai opengui` (or your custom ESC menu)
- Click **Start Chat** to enter single-turn dialogue mode
- One-on-one with the AI for richer answers
- Cooldown state shown live on the button

### 3. RAG knowledge retrieval
- Vector similarity matching for relevant documents
- Category prediction (location / quest / item / skill / NPC / general)
- Similarity threshold filtering to keep answer quality
- Aliyun DashScope `text-embedding-v3` (1024-dim) embeddings

### 4. Player knowledge contributions
- Submit Q&A via a pre-filled book (`Q: ... A: ...`)
- No complex commands to memorize
- Configurable per-player pending submission cap

### 5. OP review system
- Paginated review GUI for pending submissions
- Left-click to **approve** → writes to the knowledge base + pays rewards
- Right-click to **reject** → removes from the queue
- Rewards support Vault currency and custom items

### 6. Data persistence
- **Two storage modes** (controlled by `storage.type`):
  - `mysql` — MySQL + HikariCP pool, for multi-server / production setups
  - `yml` — local YAML files, zero-dependency deployment (**default**)
- Async writes, never blocks the main thread
- Switch modes by editing `config.yml` only; no code changes required

---

## Changelog

### v1.0.2 (2026-05-22)
- **Added local YAML storage mode**: set `storage.type: yml` to run completely without MySQL
  - New `com.exai.storage` abstraction (`DataStorage` interface + `MysqlStorage` / `YamlStorage` implementations)
  - Data persists to `data/ai_log.yml`, `data/pending_knowledge.yml`, `data/pending_count.yml`
- **Knowledge base now uses YAML by default**: `gamehelp.txt` → `knowledge.yml`, easier to edit by hand
- Legacy `gamehelp.txt` is auto-migrated to `knowledge.yml` on first startup
- Default `storage.type` is `yml`, so fresh installs work out of the box with no database

### v1.0.1
- Plugin slimmed down
- Added chat-history viewer
- Added in-game knowledge base management

---

## Installation

### Requirements

- Minecraft Spigot/Paper **1.12.2+**
- Java **8+**
- **MySQL 5.7+** — only when `storage.type: mysql`; not needed in `yml` mode
- **Vault** — optional, for currency rewards

### Steps

1. Drop `ExAI.jar` into the `plugins/` folder
2. Restart the server once — the plugin generates default configs
3. Edit `plugins/ExAI/config.yml`:
   - Pick `storage.type`: `yml` (default, no DB) or `mysql`
   - If `mysql`, fill in `storage-data` MySQL credentials
   - Set your **LLM API key** (`llm.apiKey`, default Aliyun DashScope)
4. (Optional) Edit `plugins/ExAI/knowledge.yml` to seed the knowledge base
5. Run `/exai reload`

---

## Configuration

Full example:

```yaml
# ==================== Storage mode ====================
# mysql: use MySQL;  yml: use local YAML files
storage:
  type: yml

# ==================== Storage data (only used in mysql mode) ====================
storage-data:
  address: "127.0.0.1:3306"
  database: "database"
  username: "username"
  password: "your-password"

# ==================== LLM config ====================
llm:
  baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1"
  model: "qwen-plus"
  apiKey: "your-api-key"
  # Public-chat trigger keywords
  chatKeywords: "?,how,what,why,where,who,when,can,does,is,are"
  chatResponseCD: 60          # cooldown in seconds
  chatResponseEnabled: true
  chatResponseSuffix: "(open the ESC menu to chat with me directly~)"

# ==================== Assistant ====================
assistant:
  name: "ExAI"

# ==================== GUI ====================
gui:
  title: "ExAI Assistant"
  charNumPerLine: 30

# ==================== Knowledge base ====================
knowledge:
  minSimilarity: 0.35        # minimum similarity threshold
  maxPendingKnowledgePerPlayer: 30
  knowledgeReview:
    opPermission: "exai.op"
    rewards:
      vault:
        enabled: true
        amount: 100
        currencyName: "Coin"
      items:
        - material: DIAMOND
          amount: 1
        - material: APPLE
          amount: 5
```

### File layout

The knowledge base is always stored as `knowledge.yml`. AI logs and pending-review data depend on `storage.type`:

**`storage.type: yml` (default, no MySQL needed)**
```
plugins/ExAI/
├── config.yml                      # main config
├── knowledge.yml                   # knowledge base
└── data/
    ├── ai_log.yml                  # AI chat log
    ├── pending_knowledge.yml       # pending submissions
    └── pending_count.yml           # per-player pending count
```

**`storage.type: mysql`**
```
plugins/ExAI/
├── config.yml
└── knowledge.yml                   # still local YAML
# AI log, pending knowledge, counts → 3 MySQL tables
```

> Upgrade note: if you previously used `gamehelp.txt`, it will be auto-migrated to `knowledge.yml` on first startup.

---

## Commands

| Command | Permission | Description |
|---|---|---|
| `/exai help` | everyone | Show help |
| `/exai opengui` | everyone | Open the ExAI menu |
| `/exai reload` | OP | Reload plugin config |
| `/exai question <player> <q>` | OP | Push a question to a player (for testing) |

Permission node: `exai.op` (knowledge review).

---

## Database schema (only when `storage.type: mysql`)

When MySQL mode is active, the plugin creates these tables automatically. In `yml` mode, MySQL is not used at all — you can skip this section.

```sql
-- AI chat log
CREATE TABLE ex_ai_log (
  id INT AUTO_INCREMENT PRIMARY KEY,
  player_name VARCHAR(32),
  player_input TEXT,
  ai_response TEXT,
  document_id VARCHAR(100),
  source VARCHAR(50),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Per-player pending submission count
CREATE TABLE ex_pending_knowledge_count (
  player_uuid VARCHAR(36) PRIMARY KEY,
  player_name VARCHAR(32),
  pending_count INT,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Pending knowledge submissions
CREATE TABLE ex_pending_knowledge (
  id INT AUTO_INCREMENT PRIMARY KEY,
  question TEXT,
  answer TEXT,
  submitter VARCHAR(32),
  timestamp BIGINT,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---

## How it works

### RAG pipeline

```
Player question
    │
    ▼
Embed (text-embedding-v3)
    │
    ▼
Vector similarity search (top-7 docs)
    │
    ▼
Build prompt (with retrieved context)
    │
    ▼
LLM completion (Qwen / OpenAI / ...)
    │
    ▼
Reply to player
```

### Data flow

```
┌─────────────────────────────────────────────────────────────┐
│                       Player actions                         │
└─────────────────────────────────────────────────────────────┘
                              │
         ┌────────────────────┼────────────────────┐
         ▼                    ▼                    ▼
   Public chat           GUI dialogue         Submit knowledge
         │                    │                    │
         ▼                    ▼                    ▼
   Keyword detect       Enter dialogue       Parse book
         │                    │                    │
         ▼                    ▼                    ▼
   Async generate       Async generate       Validate format
         │                    │                    │
         ▼                    ▼                    ▼
   Broadcast + log      Show + log           Queue for review
                                                  │
                                                  ▼
                                            OP review GUI
                                                  │
                                        ┌─────────┴─────────┐
                                        ▼                   ▼
                                Approve → write to KB   Reject → drop
                                + pay rewards
```

### Storage backend switch

```
                     storage.type
                          │
            ┌─────────────┴─────────────┐
            ▼                           ▼
         mysql                         yml
            │                           │
            ▼                           ▼
   MysqlStorage  (HikariCP)     YamlStorage (Bukkit YAML)
            │                           │
            ▼                           ▼
   ex_ai_log / ex_pending_*      data/*.yml
```

---

## Tech stack

| Component | Version |
|---|---|
| Server | Spigot API 1.12.2 |
| Language | Java 1.8 |
| LLM | Aliyun Qwen (DashScope) — OpenAI-compatible |
| Embeddings | `text-embedding-v3` (1024-dim) |
| Storage | YAML (default) / MySQL + HikariCP 4.0.3 |
| Economy | Vault API 1.4.9 |
| Build | Maven |

---

## Project layout

```
src/main/java/com/exai/
├── ExAI.java                    # plugin entry point
├── command/
│   └── Commands.java            # /exai command executor
├── config/
│   └── Config.java              # config loader
├── data/
│   ├── DataContainer.java       # static data holder
│   └── KnowledgeQueue.java      # in-memory pending queue
├── embedding/
│   ├── DashScopeEmbedding.java  # embedding service
│   └── VectorStore.java         # vector store + search
├── entity/
│   ├── Answer.java              # AI answer DTO
│   ├── GameDocument.java        # KB document DTO
│   ├── KnowledgeEntry.java      # KB entry DTO
│   ├── LogEntry.java            # log entry DTO
│   └── PlayerQuestion.java      # player question DTO
├── generators/
│   └── AnswerGenerator.java     # answer generation
├── gui/
│   ├── ChestGUI.java            # main menu GUI
│   ├── ConversationHistoryGUI.java  # chat history GUI
│   ├── GUIManager.java          # GUI state
│   ├── KnowledgeBaseGUI.java    # KB management GUI
│   ├── KnowledgeReviewGUI.java  # review GUI
│   └── KnowledgeSubmitGUI.java  # submission GUI
├── i18n/
│   └── Lang.java                # i18n loader
├── listener/
│   ├── ChatInputListener.java   # chat input listener
│   ├── GUIListener.java         # GUI click listener
│   ├── KnowledgeListener.java   # submission listener
│   └── PlayerListener.java      # public-chat listener
├── manager/
│   ├── EditContextManager.java  # edit-context tracking
│   ├── KnowledgeFileManager.java  # knowledge.yml I/O
│   ├── KnowledgeManager.java    # KB business logic
│   └── RewardManager.java       # reward payout
├── managers/
│   ├── GameDataLoader.java      # KB loader
│   └── GameKnowledgeBase.java   # KB core
├── mysql/
│   ├── Database.java            # DB abstract base
│   ├── MySQL.java               # MySQL (HikariCP) impl
│   └── SQLConsumer.java         # functional SQL helper
├── service/
│   └── LLMService.java          # LLM HTTP service
├── storage/                     # ★ new in v1.0.2
│   ├── DataStorage.java         # storage backend interface
│   ├── MysqlStorage.java        # MySQL backend
│   └── YamlStorage.java         # local YAML backend
└── utils/
    ├── CDUtils.java             # cooldown helpers
    ├── DataUtils.java           # data facade
    ├── HttpJsonClient.java      # HTTP client
    └── MaterialCompat.java      # material compat
```

---

## FAQ

### Q: Public chat is silent — no reply.
Check:
1. `llm.chatResponseEnabled` is `true`
2. `llm.chatResponseCD` > 0
3. The message contains at least one keyword from `llm.chatKeywords`

### Q: AI answers are empty or low quality.
1. Make sure `knowledge.yml` has enough content
2. Lower `knowledge.minSimilarity` to match more documents

### Q: Approving knowledge doesn't pay rewards.
1. Make sure Vault is installed
2. Check `knowledge.knowledgeReview.rewards.vault.enabled` is `true`

### Q: How do I switch between MySQL and YAML?
1. Change `storage.type` in `config.yml` to either `mysql` or `yml`
2. Run `/exai reload` or restart the server
3. Note: the two modes do **not** auto-sync data — back up before switching if you need to keep history

### Q: I'm upgrading from a version that used `gamehelp.txt`. What do I need to do?
Nothing. When the plugin sees a `gamehelp.txt` but no `knowledge.yml`, it migrates entries automatically on first startup and logs how many were imported.

---

## License

MIT License

---

## Contributing

Issues and pull requests are welcome!

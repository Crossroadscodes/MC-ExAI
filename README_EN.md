# ExAI — AI Assistant Plugin for Minecraft

[![Version](https://img.shields.io/badge/version-1.0.7-blue)](https://github.com/Crossroadscodes/MC-ExAI/releases/tag/v1.0.7)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

**Languages / 语言**: [简体中文](README.md) · [English](README_EN.md)

ExAI is an LLM-powered AI assistant plugin for Minecraft servers. It answers players' questions using a server-specific knowledge base built collaboratively by players and administrators.

What's new in v1.0.7:

- Added RAG system evaluation.
- Improved the CLI frontend.
- Improved answer refusal logic.

---

## Features

### 1. Intelligent answers to player questions

- Listens to player chat, detects question keywords, generates AI answers asynchronously, and broadcasts them to public chat. Includes a configurable cooldown and anti-spam protection.
- Players can also ask the AI questions through a chest GUI for automated, around-the-clock assistance.

### 2. Knowledge retrieval (RAG)

- Operators build a vector knowledge base with server-specific information, supplying the AI with knowledge it would otherwise lack.
- Players can contribute questions and answers to the shared knowledge base. Administrators can approve submissions and issue configurable rewards.

### 3. Automatic knowledge collection

- Watches public-chat conversations following the pattern “player asks → someone answers → (asker thanks)” and collects potential knowledge.
- The AI performs a context-aware, pass/fail preliminary review (without scoring) to filter out jokes, unserious replies, and meaningless content.
- Passing Q&A pairs are tagged and added to the pending queue for an operator to review.

### 4. Web CLI

- Provides function-calling tools for editing server plugin configurations, with session management and conversation rewind support.
- Tasks pause after 20 model requests per run. Use **Continue task**, `/continue`, or `继续` to resume saved progress. A new ordinary request ends the paused task and cancels its pending previews.
- New transcripts are stored without the previous 4,000-character truncation. Tool calls and results are stored separately; older context is summarized with the configured model when needed. Summary requests do not consume the task's 20-request allowance.
- Configure `webui.ai.maxIterations` (20), `contextMaxChars` (24000), `recentMessageGroups` (8), and `summaryMaxChars` (4000). The character budget is not a token budget and excludes the system prompt.
- Sessions are stored in `plugins/ExAI/web-ai-sessions/<sessionId>.json` with temporary-file replacement and `.bak` recovery. The old `web-ai-sessions.json` is migrated and retained; `web-ai-sessions-migration.json` records completion. Sessions expire 30 days after their last conversation, with cleanup on access.
- Restarted or interrupted tasks require explicit continuation; completed tool calls are not replayed. Pending previews still expire after 10 minutes and are invalidated by restart. Regenerated previews require confirmation, and historical configuration values must be checked before changes.
- Still in testing and may carry risks. Feedback is welcome.

---

## Installation

### Requirements

- Minecraft Spigot server 1.12.2+
- Java 8+
- MySQL 5.7+ (only required for `storage.type: mysql`; not needed in `yml` mode)

### Steps

1. Download `ExAI.jar` and place it in the `plugins` directory.
2. Restart the server. The plugin will generate its default configuration.
3. Edit `config.yml`:
   - Choose `storage.type`: `yml` (default, no database required) or `mysql`.
   - For `mysql`, enter your MySQL connection details under `storage-data`.
   - Set your LLM API key (Alibaba Cloud DashScope).
4. Optionally, edit `knowledge.yml` to add game knowledge.
5. Run `/exai reload` to reload the configuration.

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

# ==================== Local web administration ====================
webui:
  enabled: false
  port: 8080

# ==================== LLM config ====================
llm:
  baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1"
  model: "qwen-plus"
  apiKey: "your-api-key"
  # Answer temperature (0~1): lower = stricter / less prone to hallucination; 0.2~0.3 recommended for factual Q&A
  temperature: 0.3
  # Public-chat trigger keywords
  chatKeywords: "?,how,what,why,where,who,when,can,does,is,are"
  chatResponseCD: 60          # cooldown in seconds
  chatResponseEnabled: true
  chatResponseSuffix: "(open the ESC menu to chat with me directly~)"

# ==================== Embedding config ====================
# Leave apiKey empty to reuse llm.apiKey.
# baseUrl is an OpenAI-compatible v1 root; requests use <baseUrl>/embeddings.
embedding:
  baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1"
  apiKey: ""
  model: "text-embedding-v3"
  dimensions: 1024

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
  maxDocs: 3                 # max KB docs injected per answer (fewer = less chance of a stitched-together wrong answer; 3~5 recommended)
  maxPendingKnowledgePerPlayer: 30
  # AI pre-review for player book submissions: non-compliant content (jokes/ads/off-topic/clearly wrong/meaningless) is blocked
  playerSubmitReview:
    enabled: true
  # Auto knowledge collection: watch "player asks -> someone answers -> (asker thanks)" and queue after AI pre-review
  autoCollect:
    enabled: true
    answerWindowSeconds: 60  # answers from others within this many seconds of the question count as candidates
    thanksWindowSeconds: 20  # wait window for the asker's thanks after an answer; review still runs when it elapses
    minAnswerLength: 4       # minimum character length to filter out too-short answers
    notifyReviewers: true    # notify online reviewers when a new entry is collected
    thanksKeywords: "谢谢,感谢,thx,thanks,3q,谢了,多谢,懂了,明白了,学到了,解决了,有用"
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
| `/exai rag ids` | `exai.op` | Fill missing semantic knowledge IDs |
| `/exai rag idregen <ID>` | `exai.op` | Regenerate an ID and update test references |
| `/exai rag dataset` | `exai.op` | Open the standalone RAG dataset manager and annotation GUI |
| `/exai rag test` | `exai.op` | Run retrieval and answer-quality evaluation asynchronously |
| `/exai rag status` | `exai.op` | Show ID/evaluation progress |
| `/exai rag cancel` | `exai.op` | Request cancellation of the active evaluation |
| `/exai rag report` | `exai.op` | Show the latest summary and report path |

When a knowledge entry has no `id`, ExAI uses the configured LLM during startup, reload, and post-add rebuilds to generate a stable English `snake_case` ID. If the model is unavailable, a temporary `legacy_` hash ID keeps RAG operational and the plugin retries later. Administrators can manage the dataset from the main GUI's RAG dataset button or `/exai rag dataset`, and can still use `Shift+Right-click` on chat history to annotate a log. Click the question, reference answer, or knowledge IDs and enter the value in chat; the annotation panel reopens automatically. YAML mode stores cases in `plugins/ExAI/evaluation/test-set.yml`; MySQL mode uses the `exai_evaluation_case` table, and the two datasets remain separate. Detailed reports are stored under `plugins/ExAI/evaluation/results/`.

Permission node: `exai.op` (knowledge review).

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

### Q: Auto-collection isn't picking anything up.
1. Make sure `knowledge.autoCollect.enabled` is `true`
2. Collection needs "question → someone else's answer": the answer must be a non-question, non-thanks message of length ≥ `minAnswerLength`, posted within `answerWindowSeconds`
3. Answers go through AI pre-review; joking / meaningless / off-topic ones are silently dropped (look for `[auto-collect] AI review rejected` in the log)
4. Nothing is stored when the AI service is unavailable — check the console log

### Q: Will auto-collection dump junk into the knowledge base?
No. Nothing is written directly. Every collected Q&A first passes AI pre-review and then enters the **pending review queue**, where an OP must manually approve it in the review GUI before it's added to the knowledge base.

---

## License

MIT License

---

## Contributing

Issues and pull requests are welcome!

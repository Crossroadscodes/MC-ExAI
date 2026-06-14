# ExAI — AI Assistant Plugin for Minecraft

[![Version](https://img.shields.io/badge/version-1.0.3-blue)](https://github.com/Crossroadscodes/MC-ExAI)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

**Languages / 语言**: [简体中文](README.md) · [English](README_EN.md)

ExAI is an LLM-powered AI assistant plugin for Minecraft servers. It supports public-chat auto-replies, in-game GUI conversations, and a community-curated knowledge base with RAG retrieval.

---

## Features

### 1. Public-chat auto-reply
- Listens to player chat and detects question keywords (`?`, `how`, `what`, `why`, etc.)
- Generates AI replies asynchronously and broadcasts them to chat
- Configurable cooldown to prevent spam
- **Anti-spam**: when the knowledge base has nothing to ground an answer, the AI's "sorry, no relevant info" reply is *not* broadcast — no more noise in chat
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
- **Reduced hallucination**: prompts strictly enforce "answer only from the knowledge base, otherwise refuse", with the strongest constraint placed last (recency effect)
- Tunable answer `llm.temperature` (lower = stricter) and `knowledge.maxDocs` (fewer injected docs = less chance of stitching together a wrong answer)

### 4. Player knowledge contributions
- Submit Q&A via a pre-filled book (`Q: ... A: ...`)
- No complex commands to memorize
- Configurable per-player pending submission cap
- **AI pre-review** (`knowledge.playerSubmitReview`): submissions are first judged by the AI for fitness, filtering jokes / ads / off-topic / clearly wrong content; only passing ones enter the OP review queue
- **Fail-closed**: if the AI service is unavailable, the submission is blocked with a "try again later" message — the knowledge base is never polluted
- During async review the book is only consumed if it's still in the main hand, so other items can't be deleted by accident

### 5. Auto knowledge collection 🆕
- Watches the public-chat flow "player asks → someone answers → (asker thanks)" and harvests it into knowledge
- The AI makes a binary, context-aware pre-review (no scoring), filtering joking / off-topic / meaningless replies
- A "thanks" from the asker within the thanks window acts as a positive hint and triggers review immediately
- Passing Q&A enters the review queue tagged with an "auto-collected" source and a "thanked" flag for OP review
- Does not count against the per-player submission cap; online reviewers can be notified on new entries
- Runs independently of the public-chat AI broadcast toggle; controlled by `knowledge.autoCollect.enabled`

### 6. OP review system
- Paginated review GUI for pending submissions, showing source (player / auto-collected), answerer, and thanked state
- Left-click to **approve** → writes to the knowledge base + pays rewards
- Right-click to **reject** → removes from the queue
- Rewards support Vault currency and custom items

### 7. Data persistence
- **Two storage modes** (controlled by `storage.type`):
  - `mysql` — MySQL + HikariCP pool, for multi-server / production setups
  - `yml` — local YAML files, zero-dependency deployment (**default**)
- Async writes, never blocks the main thread
- Switch modes by editing `config.yml` only; no code changes required

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
  # Answer temperature (0~1): lower = stricter / less prone to hallucination; 0.2~0.3 recommended for factual Q&A
  temperature: 0.3
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

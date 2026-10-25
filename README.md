# ExAI - Minecraft 智能助手插件

[![Version](https://img.shields.io/badge/version-1.0.7-blue)](https://github.com/Crossroadscodes/MC-ExAI/releases/tag/v1.0.7)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

**Languages / 语言**: [简体中文](README.md) · [English](README_EN.md)

ExAI 是一款基于大语言模型的 Minecraft 服务器智能助手插件，通过玩家和管理员共同构建的服务器专有数据库，为服务器玩家提供智能问题功能。

v1.0.7更新
- 增加Rag系统评估功能
- 优化CLI前端
- 优化拒答逻辑
---

## 功能特性

### 1. 玩家问题智能回复
- 监听玩家聊天消息，检测问句关键词，异步调用 AI 生成回答，广播到公屏，冷却时间可配置，配备防刷屏系统。
- 玩家也可通过箱子界面对ai进行提问，实现24小时自动解决玩家疑问。
### 2. 知识库检索系统（RAG）
- OP 基于服务器特定知识构建向量知识库，为 AI 提供缺少的服务器相关知识。
- 玩家也提交问题与答案共建知识库，管理员采纳后发放奖励，可配置采纳后获取的奖励。
### 3. 知识自动采集系统
- 监听公屏「玩家提问 → 他人回答 →（提问者感谢）」的对话流，自动沉淀为知识
- 由 AI 结合上下文做二元初审（不打分），过滤搞笑/不正经/无意义回复
- 通过初审的问答带标识进入待审核队列，交 OP 复核
### 4. Web端CLI
- 提供一系列Tool Function用于编辑服务端插件配置，支持会话管理和对话回滚功能。
- 尚处于测试阶段，可能存在风险，欢迎提供反馈
---

## 安装

### 环境要求

- Minecraft Spigot 服务端 1.12.2+
- Java 1.8+
- MySQL 5.7+（仅 `storage.type: mysql` 时需要；选 `yml` 模式可省略）

### 安装步骤

1. 下载 `ExAI.jar` 放入 `plugins` 目录
2. 重启服务器，插件将自动生成默认配置
3. 修改 `config.yml` 中的配置：
   - 选择 `storage.type`：`yml`（默认，无需数据库）或 `mysql`
   - 若选 `mysql`，填写 `storage-data` 下的 MySQL 连接信息
   - 大模型 API Key（阿里云 DashScope）
4. （可选）编辑 `knowledge.yml` 添加游戏知识库内容
5. 执行 `/exai reload` 重载配置

---

## 配置说明

完整配置示例：

```yaml
# ==================== 存储模式 ====================
# mysql: 使用 MySQL；yml: 使用本地 YAML 文件
storage:
  type: yml

# ==================== 存储数据（仅 mysql 模式使用）====================
storage-data:
  address: "127.0.0.1:3306"
  database: "database"
  username: "username"
  password: "your-password"

# ==================== Web 管理服务（仅本机访问） ====================
# 启用后在服务器本机浏览器访问 http://127.0.0.1:<port>。
webui:
  enabled: false
  port: 8080

# ==================== 大模型配置 ====================
llm:
  baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1"
  model: "qwen-plus"
  apiKey: "your-api-key"
  # AI 回答温度 (0~1)：越低越严谨、越不容易产生幻觉，事实问答建议 0.2~0.3
  temperature: 0.3
  # 公屏聊天触发关键词
  chatKeywords: "吗,呢,么,嘛,咋,啥,谁,哪,哪里,哪儿,哪个,多少,多久,为何,为啥,为什么,如何,怎么,怎么办,怎么做,怎么用,怎么样,怎么回事,怎么搞,是不是,是否,能不能,可不可以,？,?"
  chatResponseCD: 60          # 冷却时间（秒）
  chatResponseEnabled: true   # 是否启用公屏聊天回复
  chatResponseSuffix: "(可以通过ESC菜单直接与我对话哦~)"

# ==================== Embedding / 向量配置 ====================
# apiKey 留空时复用 llm.apiKey。
# baseUrl 应为 OpenAI 兼容接口的 v1 根路径；实际请求地址为 <baseUrl>/embeddings。
embedding:
  baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1"
  apiKey: ""
  model: "text-embedding-v3"
  dimensions: 1024

# ==================== 助手配置 ====================
assistant:
  name: "ExAI"

# ==================== GUI配置 ====================
gui:
  title: "ExAI 对话助手"
  charNumPerLine: 30         # 每行字符数

# ==================== 知识库配置 ====================
knowledge:
  minSimilarity: 0.35        # 最小相似度阈值
  maxDocs: 3                 # 单次回答最多注入的知识库文档数（越少越不易拼凑出错误答案，建议 3~5）
  maxPendingKnowledgePerPlayer: 30
  # 玩家书本提交的 AI 初审：不符合规定(玩笑/广告/答非所问/明显错误/无意义)的不允许上传
  playerSubmitReview:
    enabled: true
  # 公屏问答自动采集：监听「玩家提问→他人回答→(提问者感谢)」并由 AI 初审后入队
  autoCollect:
    enabled: true
    answerWindowSeconds: 60  # 提问后多少秒内的他人回答视为候选回答
    thanksWindowSeconds: 20  # 回答出现后等待提问者感谢的时间窗(秒)，窗口结束仍会照常初审
    minAnswerLength: 4       # 过滤过短回答的最小字符数
    notifyReviewers: true    # 采集到新条目时是否提示在线审核员
    thanksKeywords: "谢谢,感谢,thx,thanks,3q,谢了,多谢,懂了,明白了,学到了,解决了,有用"
  knowledgeReview:
    opPermission: "exai.op"
    rewards:
      vault:
        enabled: true
        amount: 100
        currencyName: "微光币"
      items:
        - material: DIAMOND
          amount: 1
        - material: APPLE
          amount: 5
```

### 文件布局

知识库始终以 YAML 格式存储为 `knowledge.yml`。AI 日志/待审核数据则取决于 `storage.type`：

**`storage.type: yml`（默认，无需 MySQL）**
```
plugins/ExAI/
├── config.yml                      # 主配置
├── knowledge.yml                   # 知识库内容
└── data/
    ├── ai_log.yml                  # AI 对话日志
    ├── pending_knowledge.yml       # 待审核知识
    └── pending_count.yml           # 玩家待审核计数
```

**`storage.type: mysql`**
```
plugins/ExAI/
├── config.yml
└── knowledge.yml                   # 仍走本地 YAML
# AI 日志、待审核知识、计数 → MySQL 三张表
```

> 升级提示：旧版本如使用 `gamehelp.txt`，插件首次启动会自动迁移到 `knowledge.yml`。

---

## 命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/exai help` | 所有人 | 显示帮助信息 |
| `/exai opengui` | 所有人 | 打开 ExAI 主菜单 |
| `/exai reload` | OP | 重载插件配置 |
| `/exai question <玩家> <问题>` | OP | 向指定玩家发送问题（测试用） |
| `/exai rag ids` | `exai.op` | 立即补齐缺失的知识语义 ID |
| `/exai rag idregen <ID>` | `exai.op` | 重新生成指定知识 ID 并更新测试集引用 |
| `/exai rag dataset` | `exai.op` | 打开独立的RAG测试数据集管理与标注界面 |
| `/exai rag test` | `exai.op` | 异步运行检索与回答质量评估 |
| `/exai rag status` | `exai.op` | 查看 ID/评估任务进度 |
| `/exai rag cancel` | `exai.op` | 请求取消当前评估 |
| `/exai rag report` | `exai.op` | 查看最近一次评估摘要和报告路径 |

知识条目缺少 `id` 时，插件会在启动、重载和新增知识后的向量库重建过程中调用当前大模型生成稳定的英文 `snake_case` ID；模型不可用时暂用 `legacy_` 哈希 ID 并在下次重试。管理员可通过主界面的“RAG测试数据集”按钮或 `/exai rag dataset` 独立管理测试集，也可在历史对话 GUI 中对日志执行 `Shift+右键` 开始标注。点击问题、参考答案或知识 ID 后直接通过聊天栏输入，完成后自动返回标注面板。YAML 模式的测试集保存在 `plugins/ExAI/evaluation/test-set.yml`；MySQL 模式保存在 `exai_evaluation_case` 表，两种模式互不混用。详细报告始终保存在 `plugins/ExAI/evaluation/results/`。

---

## 常见问题

### Q: 公屏聊天没有反应？
检查配置：
1. `llm.chatResponseEnabled` 是否为 `true`
2. 冷却时间 `llm.chatResponseCD` 是否大于 0
3. 消息是否包含 `llm.chatKeywords` 中的关键词

### Q: AI 回答为空或质量差？
1. 检查 `knowledge.yml` 知识库内容是否丰富
2. 调整 `knowledge.minSimilarity` 阈值（降低可匹配更多文档）

### Q: 知识审核没有奖励？
1. 检查 Vault 插件是否安装
2. 检查 `knowledge.knowledgeReview.rewards.vault.enabled`

### Q: 如何在 MySQL 与 YAML 之间切换？
1. 修改 `config.yml` 中 `storage.type` 为 `mysql` 或 `yml`
2. 执行 `/exai reload` 或重启服务器
3. 注意：两种模式的数据不会自动互相迁移；需要切换数据请提前备份

### Q: 升级前用的是 `gamehelp.txt`，怎么办？
插件检测到 `gamehelp.txt` 存在而 `knowledge.yml` 不存在时，会在首次启动自动迁移并打印迁移条数，无需手动处理。

### Q: 公屏自动采集没有反应 / 采集不到内容？
1. 确认 `knowledge.autoCollect.enabled` 为 `true`
2. 采集依赖「提问 → 他人回答」：回答需是非问句、非感谢，且长度 ≥ `minAnswerLength`，并在 `answerWindowSeconds` 内出现
3. 回答会经 AI 初审，搞笑/无意义/答非所问的会被静默丢弃（日志可见 `[自动采集] AI初审未通过`）
4. AI 服务不可用时不会入库；可查看控制台日志确认

### Q: 自动采集会不会把垃圾消息塞进知识库？
不会直接入库。所有采集到的问答都要先过 AI 初审，再进入**待审核队列**等 OP 在审核 GUI 中人工复核批准后才会写入知识库。

---

## License

MIT License

---

## 贡献

欢迎提交 Issue 和 Pull Request！

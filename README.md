# ExAI - Minecraft 智能助手插件

[![Version](https://img.shields.io/badge/version-1.0--SNAPSHOT-blue)](https://github.com/your-repo/exai)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

ExAI 是一款基于大语言模型的 Minecraft 服务器智能助手插件，支持公屏聊天自动回复、GUI 对话、知识库管理等功能。

---

## 功能特性

### 1. 公屏聊天智能回复
- 监听玩家聊天消息，检测问句关键词（如"吗、呢、怎么、为什么"等）
- 异步调用 AI 生成回答，广播到公屏
- 冷却时间可配置，防止刷屏
- 可设置回复后缀，引导玩家使用 GUI 获取详细答案

### 2. GUI 对话系统
- 通过 `/exai opengui` 或 ESC 菜单打开主界面
- 点击"开始对话"进入对话模式
- 与 AI 一对一交流，获取更详细的回答
- 冷却状态实时显示在按钮上

### 3. 知识库检索系统（RAG）
- 基于向量相似度匹配相关文档
- 支持类别预测（地点/任务/物品/技能/NPC/通用）
- 相似度阈值过滤，确保回答质量
- 使用阿里云 text-embedding-v3 模型生成 1024 维向量

### 4. 玩家知识贡献
- 通过预置模板书本提交知识（格式：`问：xxx 答：xxx`）
- 无需记忆复杂命令，体验流畅
- 每玩家待审核数量可限制

### 5. OP 审核系统
- 分页显示待审核知识
- 左键批准 → 写入知识库 + 发放奖励
- 右键拒绝 → 从队列移除
- 奖励支持 Vault 金币和物品

### 6. 数据持久化
- MySQL 存储对话日志和待审核知识
- HikariCP 连接池保证高性能
- 异步写入，不阻塞服务器

---

## 安装

### 环境要求

- Minecraft Spigot 服务端 1.12.2+
- MySQL 5.7+
- Java 1.8+

### 安装步骤

1. 下载 `ExAI.jar` 放入 `plugins` 目录
2. 重启服务器，插件将自动生成默认配置
3. 修改 `config.yml` 中的配置：
   - MySQL 连接信息
   - 大模型 API Key（阿里云 DashScope）
4. （可选）编辑 `gamehelp.txt` 添加游戏知识库内容
5. 执行 `/exai reload` 重载配置

---

## 配置说明

完整配置示例：

```yaml
# ==================== 大模型配置 ====================
llm:
  baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1"
  model: "qwen-plus"
  apiKey: "your-api-key"
  # 公屏聊天触发关键词
  chatKeywords: "吗,呢,么,嘛,咋,啥,谁,哪,哪里,哪儿,哪个,多少,多久,为何,为啥,为什么,如何,怎么,怎么办,怎么做,怎么用,怎么样,怎么回事,怎么搞,是不是,是否,能不能,可不可以,？,?"
  chatResponseCD: 60          # 冷却时间（秒）
  chatResponseEnabled: true  # 是否启用公屏聊天回复
  chatResponseSuffix: "(可以通过ESC菜单直接与我对话哦~)"

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
  maxPendingKnowledgePerPlayer: 30
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

# ==================== 存储数据 ====================
storage-data:
  address: "127.0.0.1:3306"
  database: "database"
  username: "username"
  password: "your-password"
```

---

## 命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/exai help` | 所有人 | 显示帮助信息 |
| `/exai opengui` | 所有人 | 打开 ExAI 主菜单 |
| `/exai reload` | OP | 重载插件配置 |
| `/exai question <玩家> <问题>` | OP | 向指定玩家发送问题（测试用） |

---

## 数据库表结构

插件会自动创建以下表：

```sql
-- AI 对话日志
CREATE TABLE ex_ai_log (
  id INT AUTO_INCREMENT PRIMARY KEY,
  player_name VARCHAR(32),
  player_input TEXT,
  ai_response TEXT,
  document_id VARCHAR(100),
  source VARCHAR(50),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 玩家待审核知识计数
CREATE TABLE ex_pending_knowledge_count (
  player_uuid VARCHAR(36) PRIMARY KEY,
  player_name VARCHAR(32),
  pending_count INT,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 待审核知识
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

## 工作原理

### RAG 检索增强生成流程

```
玩家问题
    │
    ▼
向量化 (text-embedding-v3)
    │
    ▼
向量相似度检索 (Top-7 相关文档)
    │
    ▼
构建 Prompt (包含检索结果)
    │
    ▼
LLM 生成回答 (通义千问)
    │
    ▼
返回回答给玩家
```

### 数据流图

```
┌─────────────────────────────────────────────────────────────┐
│                        玩家操作                               │
└─────────────────────────────────────────────────────────────┘
                              │
         ┌────────────────────┼────────────────────┐
         ▼                    ▼                    ▼
    公屏聊天               GUI 对话              知识提交
         │                    │                    │
         ▼                    ▼                    ▼
    关键词检测           进入对话模式          解析书本内容
         │                    │                    │
         ▼                    ▼                    ▼
   异步生成回答          异步生成回答           格式验证
         │                    │                    │
         ▼                    ▼                    ▼
    广播 + 写入日志     显示回答 + 写入日志    加入待审核队列
                                                  │
                                                  ▼
                                            OP 审核 GUI
                                                  │
                                        ┌─────────┴─────────┐
                                        ▼                   ▼
                                   批准 → 写入文件      拒绝 → 移除
                                   + 发放奖励
```

---

## 技术栈

| 组件 | 技术/版本 |
|------|----------|
| 服务器 | Spigot API 1.12.2 |
| 语言 | Java 1.8 |
| 大模型 | 阿里云 通义千问 (DashScope) |
| 向量化 | text-embedding-v3 (1024维) |
| 数据库 | MySQL + HikariCP 4.0.3 |
| 经济集成 | Vault API 1.4.9 |
| 构建工具 | Maven |

---

## 项目结构

```
src/main/java/com/exai/
├── ExAI.java                    # 插件主入口
├── command/
│   └── Commands.java            # 命令执行器
├── config/
│   └── Config.java              # 配置管理
├── data/
│   ├── DataContainer.java       # 静态数据容器
│   └── KnowledgeQueue.java      # 待审核知识队列
├── embedding/
│   ├── DashScopeEmbedding.java  # 向量化服务
│   └── VectorStore.java         # 向量存储检索
├── entity/
│   ├── Answer.java             # AI回答实体
│   ├── GameDocument.java        # 游戏文档实体
│   ├── KnowledgeEntry.java      # 知识条目实体
│   └── PlayerQuestion.java      # 玩家问题实体
├── generators/
│   └── AnswerGenerator.java     # 答案生成器
├── gui/
│   ├── ChestGUI.java           # 主菜单GUI
│   ├── GUIManager.java         # GUI状态管理
│   ├── KnowledgeReviewGUI.java  # 知识审核GUI
│   └── KnowledgeSubmitGUI.java  # 知识提交GUI
├── listener/
│   ├── ChatInputListener.java   # 聊天输入监听
│   ├── GUIListener.java         # GUI点击监听
│   ├── KnowledgeListener.java   # 知识提交监听
│   └── PlayerListener.java      # 公屏聊天监听
├── manager/
│   ├── KnowledgeManager.java    # 知识管理
│   └── RewardManager.java       # 奖励发放
├── managers/
│   ├── GameDataLoader.java      # 游戏数据加载
│   └── GameKnowledgeBase.java   # 知识库核心
├── mysql/
│   ├── Database.java           # 数据库抽象
│   ├── MySQL.java              # MySQL实现
│   └── SQLConsumer.java        # SQL函数式接口
├── service/
│   └── LLMService.java         # 大模型服务
└── utils/
    ├── CDUtils.java            # 冷却工具
    ├── DataUtils.java          # 数据工具
    └── MaterialCompat.java     # 材质兼容性
```

---

## 常见问题

### Q: 公屏聊天没有反应？
检查配置：
1. `llm.chatResponseEnabled` 是否为 `true`
2. 冷却时间 `llm.chatResponseCD` 是否大于 0
3. 消息是否包含 `llm.chatKeywords` 中的关键词

### Q: AI 回答为空或质量差？
1. 检查 `gamehelp.txt` 知识库内容是否丰富
2. 调整 `knowledge.minSimilarity` 阈值（降低可匹配更多文档）

### Q: 知识审核没有奖励？
1. 检查 Vault 插件是否安装
2. 检查 `knowledge.knowledgeReview.rewards.vault.enabled`

---

## License

MIT License

---

## 贡献

欢迎提交 Issue 和 Pull Request！
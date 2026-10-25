# HTTP Tool API（Phase 2）

这些接口复用 Web CLI 的 `PluginConfigTool` 注册表和处理方法，不调用 LLM。
它们挂载到现有 WebServer，共用 `webui.host`、`webui.port`、`webui.tls.*`。
Web 默认关闭；启用后默认地址为 `https://127.0.0.1:8080`，必须配置管理员登录和 TLS。

## 鉴权及接口

通过 `POST /api/auth/login` 登录，保存响应中的 `EXAI_SESSION` Cookie，后续所有工具请求携带该 Cookie。
沿用管理员权限模型：持有有效管理员会话的调用方可以确认或取消已知 ID 的待确认项，不新增按调用方划分的所有权机制。
POST 请求如携带 `Origin`，必须与 `https://` 加请求的 `Host` 一致；非浏览器客户端可不发送 Origin。

| 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|
| GET | `/api/tools` | 无请求体 | `{"ok":true,"tools":[...]}`，工具顺序及 Schema 直接来自注册表 |
| POST | `/api/tools/execute` | `{"name":"工具名","arguments":{},"context":{"cwd":""}}` | 直接返回内部工具结果，不增加 `result` 包装 |
| POST | `/api/tools/confirm` | `{"confirmation_id":"预览返回的ID"}` | 原结构化确认结果；成功含 `ok`、`confirmation_id`、`applied`、`file`、`path`、`deleted`、`message` |
| POST | `/api/tools/cancel` | `{"confirmation_id":"预览返回的ID"}` | 原结构化取消结果；成功含 `ok`、`confirmation_id`、`cancelled`、`message` |

`name` 和 `confirmation_id` 必须是非空字符串。请求体必须是合法 JSON 对象。
`arguments` 省略时使用 `{}`，提供时必须是对象，不能是 `null`。
`context` 可省略，提供时必须是对象；`context.cwd` 可省略，提供时必须是字符串，允许空字符串。
字段类型错误不会被转换成字符串。

外部调用始终使用 `ToolContext(cwd, "", 0L)`，只读取 `context.cwd`。
其他字段被忽略，包括顶层或 `context` 中的 `sessionId`、`turnId`。
因此外部调用不能把新修改绑定到 Web CLI 的某个会话/轮次；HTTP 修改不属于 CLI `/rewind` 的撤回范围。
不会创建或写入 Web AI 对话会话。

| HTTP 状态 | 含义 |
|---|---|
| 200 | 工具调用已完成；必须继续检查 JSON 的 `ok`，工具失败同样返回 200 |
| 400 | 非法 JSON、字段缺失或类型错误 |
| 401 | 未登录或 Cookie 失效 |
| 403 | Origin 不符合现有规则 |
| 404 | 不匹配这四个接口的精确路径，例如 `/api/tools/execute/extra` |
| 405 | 方法不匹配，同时返回 `Allow` |
| 500 | HTTP 处理层内部错误，只返回通用提示，详情记录在服务端 |

未知工具返回 HTTP 200 和 `error_code=TOOL_NOT_FOUND`；不存在或过期的确认 ID 返回 HTTP 200 和 `error_code=NOT_FOUND`。
工具失败保留 `ok`、`error`、`error_code`、`retryable`。所有 JSON 响应使用 UTF-8 和 `Cache-Control: no-store`。

## curl 示例

以下为 Bash 示例，Windows 可在 Git Bash 中执行。用户名、密码、证书路径和确认 ID 都是占位符。
请使用服务端证书或其签发 CA 的 PEM 文件，证书应匹配访问主机名。
仅在本机临时验证自签名证书时，可将 `--cacert "$CA"` 替换为 `-k`；正式连接应验证证书。

```bash
BASE='https://127.0.0.1:8080'
CA='/path/to/server-or-ca.pem'

# 登录并保存 Cookie
curl --cacert "$CA" -c cookies.txt \
  -H 'Content-Type: application/json' \
  -d '{"username":"YOUR_ADMIN_USERNAME","password":"YOUR_ADMIN_PASSWORD"}' \
  "$BASE/api/auth/login"

# 发现所有工具
curl --cacert "$CA" -b cookies.txt "$BASE/api/tools"

# 列出插件
curl --cacert "$CA" -b cookies.txt \
  -H 'Content-Type: application/json' \
  -d '{"name":"list_plugins","arguments":{}}' \
  "$BASE/api/tools/execute"

# 读取配置
curl --cacert "$CA" -b cookies.txt \
  -H 'Content-Type: application/json' \
  -d '{"name":"get_yaml_value","arguments":{"file":"ExAI/config.yml","path":"knowledge.maxDocs"}}' \
  "$BASE/api/tools/execute"

# 仅创建修改预览，不会立即写文件
curl --cacert "$CA" -b cookies.txt \
  -H 'Content-Type: application/json' \
  -d '{"name":"propose_yaml_set","arguments":{"file":"ExAI/config.yml","path":"knowledge.maxDocs","value":10,"reason":"HTTP API integration test"}}' \
  "$BASE/api/tools/execute"

# 检查预览后明确确认；替换返回的 confirmation_id
curl --cacert "$CA" -b cookies.txt \
  -H 'Content-Type: application/json' \
  -d '{"confirmation_id":"RETURNED_CONFIRMATION_ID"}' \
  "$BASE/api/tools/confirm"

# 或取消该预览（与确认二选一）
curl --cacert "$CA" -b cookies.txt \
  -H 'Content-Type: application/json' \
  -d '{"confirmation_id":"RETURNED_CONFIRMATION_ID"}' \
  "$BASE/api/tools/cancel"
```

流程为 `propose_yaml_set → confirmation_id → 人工审核 → confirm/cancel`。
调用方应在用户明确确认后才发送 confirm。确认 ID 沿用现有 10 分钟有效期；确认仍检查原值是否变化，保存前备份 `.bak`。
保存 ExAI 自身配置仍会触发原有重载逻辑，若改变 Web 配置或凭据，连接/登录状态可能受影响。

## 验证范围

自动测试使用内存 `HttpExchange` 执行真实处理方法及鉴权包装器，覆盖发现结果、Cookie/Origin 拒绝、精确路径、请求校验、HTTP 状态、默认参数、上下文隔离以及缺失确认 ID。
它不启动监听端口、不模拟 Bukkit，也不等同于真实服务器 HTTP 验证。

在测试 Spigot 服务器上回归：HTTPS 启动与登录、旧 Web CLI 和流式 CLI、只读工具、敏感字段遮罩、路径越界拒绝、预览不落盘、确认保存和备份、取消不写入、重复/过期确认、原值冲突拒绝，以及 CLI 自有轮次的撤回和会话持久化。
先执行 `mvn compile`、`mvn test`、`mvn package`，再部署构建产物进行这些运行时检查。

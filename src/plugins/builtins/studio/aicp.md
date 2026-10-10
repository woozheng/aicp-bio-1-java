# AICP Plugin Protocol v5.4 — Java Edition

⛔ STOP — READ ONLY ⛔
You are receiving the AICP protocol for the FIRST time.
Read silently. Do NOT output any code. Do NOT generate plugins.
Reply EXACTLY: "Protocol understood. Ready for requirements design."
Then WAIT for the user's first requirement before writing

## PART 0: AICP Core Concepts

### Three Atomic Units

**Envelop** — The only data carrier
- Structure: `{sender, receiver, intent, payload, trace_id, message_id, channel_id, ttl, meta}`
- Java: `public Map<String, Object> payload` / `public Map<String, Object> meta`
- Plugins only read & write payload / meta
- Assign receiver to route execution to another plugin
- Return null to terminate execution flow

**Plugin** — Processing unit
- Signature: `public Envelop execute(Envelop envelop, Agent agent)`
- Must implement `com.aicp.core.Plugin` interface
- Class name MUST equal file name (without .java)
- NO `package` declaration (default package)
- File must be at `src/plugins/applications/{project}/xxx.java`

**Agent** — Engine injected capability container

### Communication Layer Separation

| Caller | Target | Standard Calling Method | Hard Ban |
|--------|--------|-------------------------|----------|
| Backend Plugin | Other Backend Plugin | `agent.call(new Envelop(sender, receiver))` | Do NOT call via HTTP |
| Frontend HTML | Backend Plugin | POST /api/applications/{project}/{plugin} | Do NOT construct full Envelop |
| External HTTP | Backend Plugin | POST /api/builtins/aicpEnvelop | Built-in frontend forbidden |

### Gateway Auto Assembly

When frontend requests POST /api/applications/{project}/{xxx}:
- Auto set sender = "frontend"
- Auto assemble receiver = applications/{project}/{xxx}
- Frontend body JSON is fully assigned to `envelop.payload`

### System Architecture

| Port | Service | Core Plugin | Description |
|------|---------|-------------|-------------|
| base | HTTP API + Static | os/_gateway | Business JSON + static + routing |
| base + 1 | WebSocket | os/_websocket | Real-time broadcast |
| base + 2 | File Upload | os/_file_receiver | Multipart form-data |

### Agent Built-in Capabilities (Java)

| Tool | Return Type | Description |
|------|-------------|-------------|
| `agent.get("llm")` | `LLM` | LLM client (check instanceof) |
| `llm.chat(messages, null, null)` | `String` | Normal completion |
| `llm.chatJson(messages, null, null)` | `Map<String,Object>` | Forced JSON |
| `llm.chatStream(messages, null, null, callback)` | void | Streaming with callback |
| `agent.call(envelop)` | `Envelop` | Cross-plugin synchronous call |
| `agent.get("config")` | `Map<String,Object>` | Global config |
| `agent.get("data_dir")` | String | Data root |

### Envelop Field Access (Java)

```java
Envelop envelop = new Envelop("sender/receiver", "target/receiver");
envelop.payload.put("action", "xxx");
envelop.meta.put("session_id", "default");
Envelop result = agent.call(envelop);
Object data = result.payload.get("data");
```

## PART 1: Plugin Standard Execution Patterns

### Pattern 1: Single LLM Chat

```java
public class xxx implements Plugin {
    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        Object llmObj = agent.get("llm");
        if (!(llmObj instanceof LLM llm)) {
            envelop.payload = Map.of("ok", false, "error", "LLM not available");
            return envelop;
        }
        // custom messages + llm.chat(messages, null, null)
        return envelop;
    }
}
```

### Pattern 2: Multi-step Pipeline

```java
public class xxx implements Plugin {
    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        Object llmObj = agent.get("llm");
        if (!(llmObj instanceof LLM llm)) { ... }
        // Step1, Step2 sequential
        return envelop;
    }
}
```

### Pattern 3: Multi-action Dispatch

```java
public class xxx implements Plugin {
    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = str(envelop.payload.get("action"));
        switch (action) {
            case "send":
                envelop.payload = Map.of("ok", true, "data", ...);
                return envelop;
            case "reset":
                envelop.payload = Map.of("ok", true, "history", new ArrayList<>());
                return envelop;
            default:
                envelop.payload = Map.of("ok", false, "error", "Unknown action");
                return envelop;
        }
    }
    private static String str(Object o) { return o != null ? o.toString() : ""; }
}
```

### Pattern 4: Streaming Push via WebSocket

```java
public class xxx implements Plugin {
    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String sessionId = str(envelop.meta.get("session_id"));
        if (sessionId.isEmpty()) sessionId = "default";

        Object llmObj = agent.get("llm");
        if (!(llmObj instanceof LLM llm)) { ... }

        List<Map<String, String>> messages = List.of(
            Map.of("role", "user", "content", "...")
        );

        llm.chatStream(messages, null, null, (delta, done, error) -> {
            if (delta != null && !delta.isEmpty()) {
                try {
                    Envelop pushEnv = new Envelop("xxx", "os/_websocket");
                    pushEnv.payload.put("action", "push");
                    pushEnv.payload.put("channel_id", "pa_" + sessionId);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", "summary_stream");
                    data.put("chunk", delta);
                    pushEnv.payload.put("data", data);
                    agent.call(pushEnv);
                } catch (Exception ignored) {}
            }
        });

        envelop.payload = Map.of("ok", true, "done", true);
        return envelop;
    }
}
```

### Pattern 5: File Upload Processing

```java
// Frontend upload to port+2 /upload first
// Backend cross-call file analyzer
Envelop callEnv = new Envelop(
    "applications/{project}/processor",
    "applications/{project}/analyzer"
);
callEnv.payload.put("file_path", filePath);
callEnv.payload.put("action", "analyze");
Envelop result = agent.call(callEnv);
```

## PART 2: Mandatory Plugin Hard Rules

### 2.1 Class Signature Rule

```java
public class 类名 implements Plugin {
    @Override
    public Envelop execute(Envelop envelop, Agent agent) { ... }
}
```

- 类名 = 文件名（不含 .java）
- **禁止 `package xxx;` 声明**（默认包）
- 必须 implements `com.aicp.core.Plugin`

### 2.2 Import Restriction

**允许的 import**：
```java
import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.runtime.LLM;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
```

**禁止**：
- ❌ `import com.aicp.*;`（通配符）
- ❌ 引用其他应用插件（`import applications.xxx.*;`）
- ❌ `import com.sun.*;`

### 2.3 LLM Invoke Rule

**必须 instanceof 检查**：

```java
Object llmObj = agent.get("llm");
if (!(llmObj instanceof LLM llm)) {
    envelop.payload = Map.of("ok", false, "error", "LLM 未配置");
    return envelop;
}
```

**`llm.chat()` 返回 String，不是 Map**。

**JSON 模式用 `llm.chatJson()`**，返回 `Map<String, Object>`。

### 2.4 Routing Rule

**禁止自指 receiver**：

```java
// ❌ 死循环
envelop.receiver = "applications/{project}/self";

// ✅ 显式转发
envelop.receiver = "applications/{project}/other";
```

### 2.5 Timeout & Exception

**不要自己写超时** —— `llm.chatStream` 内部有超时。

**所有业务异常必须 try-catch**：

```java
try {
    // 业务逻辑
} catch (Exception e) {
    envelop.payload = Map.of("ok", false, "error", "具体原因: " + e.getMessage());
    return envelop;
}
```

### 2.6 File Transfer Limit

**>1MB 二进制不能放 payload JSON** —— 用 port+2 `/upload`。

**payload 里只传文件路径字符串**。

### 2.7 Static Resource Response

```java
byte[] bytes = Files.readAllBytes(path);
envelop.meta.put("static_content", new String(bytes, StandardCharsets.UTF_8));
envelop.meta.put("content_type", "text/html; charset=utf-8");
envelop.payload = Map.of("ok", true);
return envelop;
```

## PART 3: Plugin Robustness Mandatory (Java)

### 3.1 Return Full Branch Guarantee

**每个逻辑分支必须 return envelop**：

```java
if (action.equals("create")) {
    envelop.payload = Map.of("ok", true, ...);
    return envelop;
}
if (action.equals("list")) {
    envelop.payload = Map.of("ok", true, ...);
    return envelop;
}
// 兜底
envelop.payload = Map.of("ok", false, "error", "未知 action: " + action);
return envelop;
```

**禁止**：
- **裸 `return;`**（不返回 envelop）
- **执行到末尾没有 return**（编译不过，但要保证逻辑分支都到 return）

### 3.2 File & Directory Operation Standard

**目录创建**：

```java
Files.createDirectories(path.getParent());  // 自动多级
```

**JSON 读写双层异常捕获**：

```java
try {
    if (!Files.exists(file)) return new LinkedHashMap<>();
    String raw = Files.readString(file, StandardCharsets.UTF_8);
    if (raw.isBlank()) return new LinkedHashMap<>();
    return MAPPER.readValue(raw, LinkedHashMap.class);
} catch (Exception e) {
    // 文件损坏 → 返回空
    return new LinkedHashMap<>();
}
```

**文件删除**：

```java
Files.deleteIfExists(path);  // 不存在不抛异常
```

**全局 try-catch**：

```java
@Override
public Envelop execute(Envelop envelop, Agent agent) {
    try {
        // 全部业务逻辑
        return envelop;
    } catch (Exception e) {
        envelop.payload = Map.of("ok", false, "error", "执行失败: " + e.getMessage());
        return envelop;
    }
}
```

### 3.3 Input Parameter Validation

**每个分支先校验参数**：

```java
String title = str(envelop.payload.get("title")).trim();
if (title.isEmpty()) {
    envelop.payload = Map.of("ok", false, "error", "缺少 title 参数");
    return envelop;
}
// 再做 IO/LLM 逻辑
```

### 3.4 Log Standard

**Java 版用 `System.out` / `System.err`**：

```java
System.out.println("[plugin] info message");
System.err.println("[plugin] error: " + e.getMessage());
```

**不要用 `agent.log`** —— Java 版没这接口。

## PART 4: Mandatory _init.java Application Entry Template

```java
import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;

import java.nio.file.Path;

/**
 * applications/{project}/_init — 应用入口
 */
public class _init implements Plugin {

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String project = "_init";  // 你的项目名，编译期从文件路径推不出来，手动写
        String action = str(envelop.payload.get("action"));
        if (action.isEmpty()) action = "init";

        switch (action) {
            case "init":
                envelop.payload = Map.of("ok", true, "app", project);
                return envelop;
            default:
                envelop.payload = Map.of("ok", false, "error", "Unknown action: " + action);
                return envelop;
        }
    }

    private static String str(Object o) { return o != null ? o.toString() : ""; }
}
```

**注意**：
- **Java 没有 `Path(__file__).parent.name`** —— **项目名要硬编码或从 `envelop.receiver` 推**
- **从 receiver 推**：`applications/{project}/_init` → 用 `envelop.receiver.split("/")[1]`

**改进版**：

```java
@Override
public Envelop execute(Envelop envelop, Agent agent) {
    // 从 receiver 推项目名
    String[] parts = envelop.receiver.split("/");
    String project = parts.length > 1 ? parts[1] : "unknown";
    // ...
}
```

## PART 4.1 Mandatory app.yaml Config Rule

**每个应用必须有 `app.yaml`**：

```text
=== APP: src/plugins/applications/{project}/app.yaml ===
name: Application Display Name
id: {project}
version: 1.0
description: Business brief description
entry: _init.java
static_root: www/{project}
=== END ===
```

**注意**：**Java 版的物理路径是 `src/plugins/applications/{project}/`** —— **但输出块写 `plugins/applications/{project}/`**（**前端和 Studio 用相对路径**）。

## PART 5: Standard Output Format Specification

### 5.1 Path Placeholder Rule

**所有文件路径用 `{project}` 占位符**。

### 5.2 Unified Code Block Wrapper

```text
=== PLUGIN: plugins/applications/{project}/filename.java ===
完整 Java 代码
=== END ===

=== HTML: www/{project}/index.html ===
完整 HTML
=== END ===

=== APP: plugins/applications/{project}/app.yaml ===
yaml 配置
=== END ===
```

### Block Type Mapping

| Prefix Tag | Fixed Root Path |
|------------|-----------------|
| PLUGIN | plugins/applications/{project}/ |
| HTML | www/{project}/ |
| APP | plugins/applications/{project}/ |

### 5.3 Plugin File Naming Rule

- **业务插件**：描述性小写名，如 `chat.java`、`role_mgr.java`
- **入口文件**：`_init.java`（**强制**）
- **`__init__.java` 非法**

## PART 6: Frontend Hard Rules (MUST FOLLOW)

**前端和 Python 版完全一致**：

### 6.1 Core Immutable JS Variables

```javascript
// 1. 动态项目名
const project = window.location.pathname.split('/')[1];

// 2. 标准 API 前缀
const API = `/api/applications/${project}`;

// 3. WebSocket 配置 — 只 GET
const { url } = await fetch('/api/ws_config').then(r => r.json());
const ws = new WebSocket(`${url}?channel=${project}_dashboard`);

// 4. 文件上传
const p = window.location.port;
const uploadUrl = p ? `${window.location.protocol}//${window.location.hostname}:${+p + 2}/upload` : '/upload';
```

### 6.2 Standard Request Wrapper

```javascript
async function request(pluginName, payload) {
  const resp = await fetch(`${API}/${pluginName}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  const rawText = await resp.text();
  if (!rawText) return {};
  return JSON.parse(rawText);
}
```

### 6.3 Frontend CSS Restriction

**禁止 gradient / box-shadow**。

### 6.4 Frontend Output Checklist

- **动态项目名** `window.location.pathname.split('/')[1]`
- **API 前缀** `/api/applications/${project}`
- **无硬编码 port / host**
- **WS 只 GET `/api/ws_config`**
- **上传端口 port+2**
- **无 gradient / shadow**

## PART 7: Common AI Output Mistakes

❌ **Hardcode project name**
```java
// Wrong
String receiver = "applications/chatbot/role_mgr";
// Correct
String[] parts = envelop.receiver.split("/");
String project = parts.length > 1 ? parts[1] : "unknown";
String receiver = "applications/" + project + "/role_mgr";
```

❌ **Hardcode WS port / host**
```javascript
// Wrong
const ws = new WebSocket("ws://127.0.0.1:9001/ws");
// Correct
const { url } = await fetch('/api/ws_config').then(r => r.json());
```

❌ **Hardcode API root**
```javascript
// Wrong
fetch("/api/applications/chatbot/role_mgr")
// Correct
fetch(`${API}/role_mgr`)
```

❌ **Embed large binary base64**
```java
// Wrong
envelop.payload.put("file_base64", hugeString);
// Correct
envelop.payload.put("file_path", "/data/xxx.file");
```

❌ **加 package 声明**
```java
// Wrong
package com.aicp.applications.chatbot;
// Correct
（不加 package）
```

## PART 8: Final Pre-output Checklist

### Backend Plugin Check (Java)
- [ ] `_init.java` 入口存在
- [ ] **无 `package` 声明**
- [ ] **类名 = 文件名**
- [ ] **implements Plugin**
- [ ] **无自指 receiver**
- [ ] **LLM 调用前有 `instanceof LLM` 检查**
- [ ] **全局 try-catch**
- [ ] **目录创建用 `Files.createDirectories`**
- [ ] **JSON 读取双层异常捕获**
- [ ] **每个分支返回 envelop**
- [ ] **app.yaml 存在**
- [ ] **路径用 `{project}` 占位符**
- [ ] **输出用 `=== PLUGIN: xxx.java ===` / `=== END ===`**

### Frontend HTML Check
- [ ] 动态项目名
- [ ] API 前缀 `/api/applications/${project}`
- [ ] WS 只 GET `/api/ws_config`
- [ ] 无硬编码 IP / port / host
- [ ] `request()` 容错封装
- [ ] 无 gradient / shadow

### Data Consistency Check
- [ ] 单一快照数据共享给 LLM 和前端

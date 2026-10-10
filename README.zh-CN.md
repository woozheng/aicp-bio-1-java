# AICP-BIO-1 · Java

[English](./README.md) | 中文

AICP-BIO-1 · Java 是 [AICP 协议](https://github.com/woozheng/aicp) 的 JVM 实现。

> Everything is an Agent. Protocol-driven. The system is shaped by its information flow.

协议本身——两层 LLM、消息作为唯一真相源、没有实例和生命周期——见主仓库。本文只讲 Java 版特有的部分：在进程内编译插件、热加载进运行中的 JVM、把 JVM 挂到 Agent 上、把路由绑定到函数签名。

其他实现：[Python](https://github.com/woozheng/aicp-bio-1-python) · [TypeScript](https://github.com/woozheng/aicp-bio-1-ts)

---

## 快速开始

**环境要求**

- JDK 21+（在 Temurin 21 上测试）
- Maven 3.10+

**运行**

```bash
git clone https://github.com/woozheng/aicp-bio-1-java.git
cd aicp-bio-1-java
```

复制 `aicp.yaml.example` → `aicp.yaml`。

填入你的模型参数。

```bash
mvn clean package
java -jar target/aicp-java-1.0.0.jar
```

访问 <http://127.0.0.1:9000>

然后直接开始对话，剩下的交给它。

## 端口

| 端口  | 服务                          |
| ----- | ----------------------------- |
| 9000  | HTTP 网关 + 静态服务（控制台） |
| 9001  | WebSocket                     |
| 9002  | 文件接收（上传）              |

控制台在 <http://127.0.0.1:9000>。文件上传走 9002。实时事件（信息流、进度、流式输出）走 9001。

生成的应用落在 `src/plugins/applications/` 和 `www/{project}/`。它们不属于本仓库，是系统的产物，不是系统本身。

## 像解释型语言一样写 Java

Java 是编译型语言。插件本该重新编译、重启才能生效。

在这里不用。

LLM 往 `src/plugins/` 里写一个 `.java` 文件，从那一刻起：

```text
LLM 写 src/plugins/foo/bar.java
  ↓
FileWatcher（WatchService + 周期扫描）
  ↓
PluginLoader.compile()  ← ToolProvider.getSystemJavaCompiler()（JSR 199）
  ↓
bar.class
  ↓
URLClassLoader → newInstance() → Plugins.INSTANCE
  ↓
main_agent 通过 Envelop 调用它——立刻
```

三件普通 Java 项目不会做的事：

- **动态编译**——`javax.tools.JavaCompiler` 在进程内编译插件源码。不用 `javac`，不用 `mvn`，不用重启。
- **热加载**——`FileWatcher` 用 JDK 的 `WatchService`，再加一个 2 秒的周期扫描，补上漏掉的 `ENTRY_CREATE` 事件。新的 `.class` 通过 `URLClassLoader` 加载，注册进插件表。删掉 `.java`，插件就卸载。
- **依赖自动解析**——插件可以声明 `// @DEPS: group:artifact:version`。`PluginLoader` 从 Maven 镜像把 jar 下载到 `src/plugins/libs/`，再加进编译和运行的 classpath。

几个让它在实践中稳定的细节：

- mtime 缓存，没变的文件不重复加载
- 文件大小稳定性检测，半截文件不编译
- 编译失败有上限重试（5 次）
- mtime 比对，`.class` 比 `.java` 新就跳过重编译
- 核心插件优先加载

最终效果：LLM 写 Java，和写 Python 一样。编译这一步对它不可见。这是「Agent 能创建 Agent」在 JVM 上成立的前提。

## JVM 即元能力

在 JVM 上，能力住在 Agent 容器里。

```java
this.agent = new Agent(attrs);
this.agent.set("inspector", new AgentInspector(this.agent));
this.agent.set("jvm", new JVMContext());
```

任何挂到 Agent 上的东西，都对每个插件可达：

```text
Agent
 ├── jvm        ← JVMContext：反射、内省、JVM 里的一切
 ├── llm        ← 模型
 ├── system     ← SystemServer：唯一的调用入口
 ├── config     ← 配置
 ├── data_dir   ← 数据目录
 ├── log        ← 日志
 └── ...        ← 任何 bean、任何服务、任何挂进来的东西
```

一个已有的 Java 服务，不需要重写、不需要包成 REST、不需要暴露成 RPC。挂到 Agent 上，它就变成一个能力，任何插件——也就是 LLM——都能触达。

这就是「进程内能力」的含义。LLM 生成的代码不是外部进程，也不是 RPC 服务。它是同一个 JVM 里的一个对象，共享同一个 Agent、同一个类加载器、同一个 classpath。

JVM 是元能力，Agent 是容器。挂上去，整个企业就活了。

## 签名即路由

一个插件就是：

```java
public interface Plugin {
    Envelop execute(Envelop envelop, Agent agent);
}
```

任何类，只要实现这个接口，放在 `src/plugins/` 下，就自动成为一个可被消息调用的能力。

没有注册中心要更新。没有 Spring Bean 要声明。没有 RPC 框架。没有 IDL。

JVM 的函数签名 `(Envelop, Agent) → Envelop` 就是路由契约。

这是让第一层的设计变成第二层执行的机制。LLM 用消息设计，JVM 用签名执行，两者在插件里相遇。

## 插件之内

插件拿到的 `agent`，是一整包能力。

```java
public Envelop execute(Envelop envelop, Agent agent) {
    // 从 Envelop 拿「要做什么」
    String action = (String) envelop.payload.get("action");

    // 从 Agent 拿「能用什么」
    LLM llm = (LLM) agent.get("llm");
    JVMContext jvm = (JVMContext) agent.get("jvm");
    SystemCaller system = (SystemCaller) agent.get("system");

    // 在同一个 JVM 进程内干活
    // ...

    // 返回一个新的 Envelop
    envelop.payload = Map.of("ok", true, "data", result);
    return envelop;
}
```

- **Envelop** 告诉它要做什么
- **Agent** 告诉它能用什么
- **JVM** 让这一切在同一个进程里发生

插件是 Envelop 和 Agent 的相遇点。也是 LLM 唯一需要学会「干活」的地方。

## Everything is an Agent

一个 Agent 就是：

```text
execute(envelop, agent) → envelop
```

没有例外。系统里的一切都是 Agent：

| 是什么         | Agent 路由                      |
| -------------- | ------------------------------- |
| 文件读写       | `os/file_utils_api`             |
| 主控台         | `builtins/agents/main_agent`    |
| 契约提取       | `builtins/agents/contract_agent` |
| 系统地图       | `builtins/agents/cogitor`       |
| Agent 能力发现 | `builtins/agents/capabilities`  |
| 前端生成       | `builtins/agents/frontend_agent` |
| 创建 Agent     | `builtins/tools/create_tool`    |
| 修复 Agent     | `builtins/tools/fix_tool`       |
| 删除 Agent     | `builtins/tools/remove_tool`    |
| 分身管理       | `builtins/tools/task_manager`   |
| 技能加载       | `builtins/tools/skill_loader`   |
| HTTP 网关      | `os/_gateway`                   |
| WebSocket      | `os/_websocket`                 |
| 定时任务       | `os/_cron`                      |
| 文件接收       | `os/_file_receiver`             |
| 静态服务       | `os/_static`                    |
| 插件注册表     | `os/_registry`                  |
| 前端页面       | `www/{project}`                 |

连 HTTP 服务器、WebSocket 服务器、cron 调度器，都是 Agent。

## 自举

AICP-BIO-1 能创建、修复、删除 Agent：

```text
main_agent 调 create_tool
  ↓
create_tool 产出一个新的 .java 插件
  ↓
FileWatcher 发现它，PluginLoader 编译它
  ↓
新 Agent 被热加载进 JVM
  ↓
main_agent 通过同一个 Envelop 调用它
```

闭环。编译这一步对 LLM 不可见。

## 架构

```text
┌──────────────────────────────────────────────┐
│  第一层：LLM 设计系统                          │
│  消息进来 → 消息流转 → 消息出去                │
└──────────────────────────────────────────────┘
                    ↕
┌──────────────────────────────────────────────┐
│  第二层：运行时递归                            │
│  读 flow → 判断 → 行动 → 写回                  │
└──────────────────────────────────────────────┘
                    ↕
┌──────────────────────────────────────────────┐
│  Envelop（消息）+ Agent（能力）                │
│  它们在插件里相遇                              │
└──────────────────────────────────────────────┘
                    ↕
┌──────────────────────────────────────────────┐
│  Runtime（Java 特有）                          │
│  FileWatcher → PluginLoader → JVM             │
│  动态编译 · 热加载 · @DEPS                     │
└──────────────────────────────────────────────┘
```

- **Envelop**：消息平面（sender / receiver / payload / meta）
- **Agent**：能力平面（llm / config / system / data_dir / log / jvm）
- **Plugin**：两个平面的交点
- **Runtime**：Java 特有的层，让插件可以热加载

## 模型建议

AICP-BIO-1 要求模型有强编码能力。以下两个是推荐的（经过实测）：

| 模型               | 提供方                | 说明                     |
| ------------------ | --------------------- | ------------------------ |
| `doubao-code-2.0`  | 火山引擎 / 聚合平台   | 编码强、快、便宜         |
| `claude-sonnet-4.6` | Anthropic / 聚合平台 | 顶级编码、推理稳定       |

> ⚠️ 推荐模型能力必须 ≥ 这两个。

低于这个能力水平的模型，可能在以下阶段失败：

- `create_tool` / `frontend_agent`：生成的代码可能缺字段或 import
- `contract_agent`：契约提取可能不准
- `main_agent`：JSON 输出可能不稳定，触发重试
- `aicp_chat`：沙箱代码生成可能失败

**直接上顶级模型。** 这个系统的瓶颈不是 token 成本，而是「一次做对」。

## 协议

AICP-BIO-1 由 AICP 协议驱动。

协议本身只有一件事：

```text
execute(envelop, agent) → envelop
```
- **aicp协议主仓库**：[aicp](https://github.com/woozheng/aicp)


## 其他实现

- **Python**：[aicp-bio-1-python](https://github.com/woozheng/aicp-bio-1-python)
- **TypeScript**：[aicp-bio-1-typescript](https://github.com/woozheng/aicp-bio-1-typescript)


所有实现遵循同一份协议。

## 开发者预览

AICP-BIO-1 处于开发者预览阶段，迭代很快，会有破坏性变更。

## 引用

```bibtex
@misc{aicp-bio-1-java-2026,
  title={AICP-BIO-1: Everything is an Agent (Java)},
  author={dvwoo},
  year={2026},
  publisher={GitHub},
  howpublished={\url{https://github.com/woozheng/aicp-bio-1-java}},
}
```

## 许可证

[MIT](LICENSE)

# AICP-BIO-1 · Java

English | [中文](./README.zh-CN.md)

AICP-BIO-1 · Java is the JVM implementation of the [AICP Protocol](https://github.com/woozheng/aicp).

> Everything is an Agent. Protocol-driven. The system is shaped by its information flow.

The protocol itself — two layers of LLM, messages as the only source of truth, no instances and no lifecycles — lives in the main repository. This document covers only what is specific to Java: compiling plugins in-process, hot-loading them into a running JVM, mounting the JVM onto the Agent, and binding routing to the function signature.

Other implementations: [Python](https://github.com/woozheng/aicp-bio-1-python) · [TypeScript](https://github.com/woozheng/aicp-bio-1-ts)

---

## Quick Start

**Requirements**

- JDK 21+ (tested on Temurin 21)
- Maven 3.10+

**Run**

```bash
git clone https://github.com/woozheng/aicp-bio-1-java.git
cd aicp-bio-1-java
```

Copy `aicp.yaml.example` → `aicp.yaml`.

Fill in your model parameters.

```bash
mvn clean package
java -jar target/aicp-java-1.0.0.jar
```

Visit http://127.0.0.1:9000

Then just start chatting. The rest is handled for you.

### Ports

| Port | Service |
|------|---------|
| 9000 | HTTP gateway + static server (console) |
| 9001 | WebSocket |
| 9002 | File receiver (uploads) |

The console is at http://127.0.0.1:9000. File uploads go through 9002. Realtime events (flow, progress, streaming output) go through 9001.

Generated applications live in `src/plugins/applications/` and `www/{project}/`. They are not part of this repository. They are products of the system, not the system itself.

---

## Interpreted-like Java

Java is a compiled language. Plugins are supposed to require a rebuild and a restart.

Here, they do not.

The LLM writes a `.java` file into `src/plugins/`. From that moment:

```text
LLM writes src/plugins/foo/bar.java
  ↓
FileWatcher (WatchService + periodic scan)
  ↓
PluginLoader.compile()  ← ToolProvider.getSystemJavaCompiler() (JSR 199)
  ↓
bar.class
  ↓
URLClassLoader → newInstance() → Plugins.INSTANCE
  ↓
main_agent calls it via Envelop — immediately
```

Three things a normal Java project does not do:

- **Dynamic compilation** — `javax.tools.JavaCompiler` compiles plugin sources in-process. No javac, no mvn, no restart.
- **Hot loading** — FileWatcher uses the JDK WatchService, plus a 2-second periodic scan to catch missed `ENTRY_CREATE` events. New `.class` files are loaded via `URLClassLoader` and registered into the plugin registry. Deleting a `.java` file unloads the plugin.
- **Automatic dependency resolution** — a plugin can declare `// @DEPS: group:artifact:version`. PluginLoader downloads the jar from a Maven mirror into `src/plugins/libs/`, then adds it to the compile and runtime classpath.

Details that make it stable in practice:

- mtime cache, so unchanged files are not reloaded
- file-size stability check, so half-written files are not compiled
- bounded retries (5 attempts) on compile failure
- mtime comparison, so a `.class` newer than its `.java` skips recompilation
- core plugins load before the rest

The net effect: the LLM writes Java the way it writes Python. The compile step is invisible to it. This is what makes "Agents can create Agents" true on the JVM.

---

## JVM as Meta-Capability

On the JVM, capability lives in the Agent container.

```java
this.agent = new Agent(attrs);
this.agent.set("inspector", new AgentInspector(this.agent));
this.agent.set("jvm", new JVMContext());
```

Anything mounted onto the Agent becomes reachable to every plugin:

```text
Agent
 ├── jvm        ← JVMContext: reflection, introspection, everything in the JVM
 ├── llm        ← the model
 ├── system     ← SystemServer: the single call entry
 ├── config     ← configuration
 ├── data_dir   ← data directory
 ├── log        ← logger
 └── ...        ← any bean, any service, anything mounted
```

An existing Java service does not need to be rewritten, wrapped in REST, or exposed over RPC. Mount it onto the Agent, and it becomes a capability that any plugin — and therefore the LLM — can reach.

This is what "process-internal capability" means. The LLM's generated code is not an external process and not an RPC service. It is an object inside the same JVM, sharing the same Agent, the same classloader, the same classpath.

JVM is the meta-capability. Agent is the container. Mount it, and the whole enterprise comes alive.

---

## Signature is Routing

A plugin is simply:

```java
public interface Plugin {
    Envelop execute(Envelop envelop, Agent agent);
}
```

Any class that implements this interface and lives under `src/plugins/` automatically becomes a message-callable capability.

There is no registry to update. No Spring Bean to declare. No RPC framework. No IDL.

The JVM function signature `(Envelop, Agent) → Envelop` is the routing contract.

This is the mechanism that turns Layer 1's design into Layer 2's execution. The LLM designs in messages. The JVM executes by signature. The two meet in the plugin.

---

## Inside the Plugin

The agent a plugin receives is a full bundle of capability.

```java
public Envelop execute(Envelop envelop, Agent agent) {
    // From the Envelop: what to do
    String action = (String) envelop.payload.get("action");

    // From the Agent: what is available
    LLM llm = (LLM) agent.get("llm");
    JVMContext jvm = (JVMContext) agent.get("jvm");
    SystemCaller system = (SystemCaller) agent.get("system");

    // Work inside the same JVM process
    // ...

    // Return a new Envelop
    envelop.payload = Map.of("ok", true, "data", result);
    return envelop;
}
```

- The **Envelop** tells it what to do
- The **Agent** tells it what is available
- The **JVM** makes it all happen in the same process

The plugin is where the Envelop and the Agent meet. It is also the only place the LLM needs to learn to work.

---

## Everything is an Agent

An Agent is simply:

```text
execute(envelop, agent) → envelop
```

No exceptions. Everything in the system is an Agent:

| What | Agent route |
|------|-------------|
| File I/O | `os/file_utils_api` |
| Main console | `builtins/agents/main_agent` |
| Contract extraction | `builtins/agents/contract_agent` |
| System map | `builtins/agents/cogitor` |
| Agent capability discovery | `builtins/agents/capabilities` |
| Frontend generation | `builtins/agents/frontend_agent` |
| Create Agent | `builtins/tools/create_tool` |
| Fix Agent | `builtins/tools/fix_tool` |
| Remove Agent | `builtins/tools/remove_tool` |
| Subagent management | `builtins/tools/task_manager` |
| Skill loading | `builtins/tools/skill_loader` |
| HTTP gateway | `os/_gateway` |
| WebSocket | `os/_websocket` |
| Cron | `os/_cron` |
| File receiver | `os/_file_receiver` |
| Static server | `os/_static` |
| Plugin registry | `os/_registry` |
| Frontend pages | `www/{project}` |

Even the HTTP server, WebSocket server, and cron scheduler are Agents.

---

## Self-Bootstrapping

AICP-BIO-1 can create, fix, and remove Agents:

```text
main_agent calls create_tool
  ↓
create_tool produces a new .java plugin
  ↓
FileWatcher detects it, PluginLoader compiles it
  ↓
the new Agent is hot-loaded into the JVM
  ↓
main_agent calls it via the same Envelop
```

Closed loop. The compile step is invisible to the LLM.

---

## Architecture

```text
┌──────────────────────────────────────────────┐
│  Layer 1: LLM designs the system              │
│  messages in → messages flow → messages out   │
└──────────────────────────────────────────────┘
                    ↕
┌──────────────────────────────────────────────┐
│  Layer 2: Runtime recurses                    │
│  read flow → decide → act → write back        │
└──────────────────────────────────────────────┘
                    ↕
┌──────────────────────────────────────────────┐
│  Envelop (message) + Agent (ability)          │
│  They meet in the Plugin                      │
└──────────────────────────────────────────────┘
                    ↕
┌──────────────────────────────────────────────┐
│  Runtime (Java-specific)                      │
│  FileWatcher → PluginLoader → JVM             │
│  Dynamic compile · Hot load · @DEPS           │
└──────────────────────────────────────────────┘
```

- **Envelop**: the message plane (sender / receiver / payload / meta)
- **Agent**: the ability plane (llm / config / system / data_dir / log / jvm)
- **Plugin**: the intersection of the two planes
- **Runtime**: the Java-specific layer that makes plugins hot-loadable

---

## Model Recommendations

AICP-BIO-1 requires a model with strong coding ability. The following two are recommended (tested in practice):

| Model | Provider | Notes |
|-------|----------|-------|
| doubao-code-2.0 | Volcano Engine / Aggregator | Strong coding, fast, cheap |
| claude-sonnet-4.6 | Anthropic / Aggregator | Top-tier coding, stable reasoning |

> ⚠️ Recommended model capability must be ≥ these two.

Models below this capability level may fail in the following stages:

- `create_tool` / `frontend_agent`: generated code may miss fields or imports
- `contract_agent`: contract extraction may be inaccurate
- `main_agent`: JSON output may be unstable, triggering retries
- `aicp_chat`: sandbox code generation may fail

Go straight for a top-tier model. The bottleneck of this system is not token cost — it is "getting it right the first time."

---

## Protocol

AICP-BIO-1 is driven by the AICP Protocol.

The protocol itself is just one thing:

```text
execute(envelop, agent) → envelop
```

AICP Protocol main repository: [aicp](https://github.com/woozheng/aicp)

---

## Other Implementations

- Python: [aicp-bio-1-python](https://github.com/woozheng/aicp-bio-1-python)
- TypeScript: [aicp-bio-1-typetscript](https://github.com/woozheng/aicp-bio-1-typescript)

All implementations follow the same protocol.

---

## Developer Preview

AICP-BIO-1 is in developer preview and iterating rapidly. There will be breaking changes.

---

## Citation

```bibtex
@misc{aicp-bio-1-java-2026,
  title={AICP-BIO-1: Everything is an Agent (Java)},
  author={dvwoo},
  year={2026},
  publisher={GitHub},
  howpublished={\url{https://github.com/woozheng/aicp-bio-1-java}},
}
```

---

## License

[MIT](LICENSE)

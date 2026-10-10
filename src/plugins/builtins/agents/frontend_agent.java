import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.runtime.LLM;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * builtins/agents/frontend_agent — 前端生成/修复 Agent
 *
 * action:
 *   - create : 生成 www/{project}/index.html
 *   - fix    : 修复已有 HTML
 *
 * 输入（create）：
 *   - project_name : 项目名
 *   - description  : 前端需求描述
 *   - backend_contract : 后端契约（路由 + action + 参数 + 返回字段）
 *
 * 输入（fix）：
 *   - project_name : 项目名
 *   - description  : 修复需求
 *   - backend_contract : 后端契约（可选，用于对齐）
 */
public class frontend_agent implements Plugin {

    static final Path WWW_DIR = Path.of("www");
    static final int MAX_HTML_CHARS = 50000;
    static final int MAX_RETRIES = 3;
    static final int MIN_HTML_SIZE = 2000;
    static final int MAX_CONTINUATIONS = 3;

    static final String FRONTEND_SYSTEM = """
    你是资深前端工程师，精通人机交互设计。

    ═══════════════════════════════════════
    【最高优先级 — 工具化思维】
    ═══════════════════════════════════════
    你的任务不是"解决用户这一次需求"，而是"创造一个能解决这一类需求的通用界面"。

    - 用户说"做个番茄钟" → 你创造的是"可配置的番茄钟工具"，不是"一个 25 分钟的计时器"
    - 用户说"查今天天气" → 你创造的是"天气查询界面"，不是"今天天气的结果"
    - 用户说"把数据转报表" → 你创造的是"数据转报表工具"，不是"这份数据的报表"

    具体规则：
    1. 不硬编码任何数据、URL、路径
    2. 所有数据从后端 API 获取
    3. 所有用户输入从表单/输入框读取
    4. 思考：如果用户下次用同样功能但不同参数，界面能不能直接复用？

    ═══════════════════════════════════════
    【输出格式 — 唯一标准】
    ═══════════════════════════════════════
    只输出下面这一块，不要任何其他文字，不要 markdown 代码块包裹：

    === HTML: index.html ===
    （完整 HTML，从 <!DOCTYPE html> 到 </html>）
    === END ===

    ═══════════════════════════════════════
    【HTML 铁律 — 违反将导致失败】
    ═══════════════════════════════════════
    1. 第一行必须是 <!DOCTYPE html>，最后一行必须是 </html>
    2. 禁止输出任何解释、设计过程、清单、思考标签（<think> 等）
    3. 禁止用 markdown 代码块标记（```html 或 ~~~html）
    4. 禁止在 HTML 内留占位符（<!-- Content -->、/* Styles here */、// your code here）
    5. 必须包含至少一个 <script> 或 <style> 块
    6. 至少 2000 字节

    ═══════════════════════════════════════
    【JS 规范 — 必须遵守】
    ═══════════════════════════════════════
    - 用 var 声明变量，用 function 定义函数
    - 禁止 const / let / 箭头函数 (() => {}) / 模板字符串 (``)
    - 变量名避开 window 全局：history、location、name、status、top、parent、self、open、close、length、frames、navigator

    正确做法：
      var fileName = node.name;
      var scanName = document...;
      var pathName = location...;

    不确定一个词是不是全局变量，就加前缀：var _myXxx = ...

    ═══════════════════════════════════════
    【API 调用规范 — 前后端对齐】
    ═══════════════════════════════════════
    API 基址和 Plugin 名必须动态获取，禁止硬编码：

    var project = window.location.pathname.split('/')[1];
    var API = '/api/applications/' + project;
    var PLUGIN = 'xxx_api';   // 具体插件名，见后端契约

    所有请求统一发到 API + '/' + PLUGIN：

    async function request(payload) {
      var resp = await fetch(API + '/' + PLUGIN, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
          payload: payload,
          meta: { session_id: getSessionId() }    // ★ 必须传，用于 WS 推送定位
        })
      });
      var rawText = await resp.text();
      if (!rawText) return {ok: false, error: '空响应'};
      return JSON.parse(rawText);
    }

    payload 里只传业务字段，用 action 区分操作：
      await request({action: 'add', content: '买菜'});
      await request({action: 'list'});

    ★ 返回值取值规范（必须遵守）：
    后端返回结构固定为 {ok, data, error}，request 原样返回，不做解包。

    var result = await request({action: 'list'});
    if (!result.ok) { alert('失败: ' + result.error); return; }
    var items = result.data.items || [];      // ★ 用 result.data.xxx
    var note = result.data.note;              // ★ 用 result.data.note

    禁止：
    ❌ var items = result.items;              // 会 undefined
    ❌ var result = json.data || json;        // 不要自己解包
    ❌ var items = result.data;               // 忘记具体字段名

    ═══════════════════════════════════════
    ★★★ 字段名强制对齐 ★★★
    ═══════════════════════════════════════
    所有字段名必须和后端契约完全一致，禁止瞎编：
      - 后端 task_id → 前端必须用 task_id（不能写 id、taskId）
      - 后端 created_at → 前端必须用 created_at（不能写 createTime、time）
      - 后端 api_url → 前端必须用 api_url（不能写 url、apiUrl）

    ═══════════════════════════════════════
    【WebSocket 规范 — 需求涉及实时推送/流式时必须遵守】
    ═══════════════════════════════════════
    WS 地址动态获取，禁止硬编码端口 / 主机名 / 占位符：

    var ws = null;

    function getSessionId() {
        var match = document.cookie.match(/aicp_token=([^;]+)/);
        return match ? match[1] : 'default';
    }

    function connectWS() {
        var sessionId = getSessionId();
        var wsChannel = 'pa_' + sessionId;   // ★ 用 pa_{sessionId}
        fetch('/api/ws_config').then(function(r) { return r.json(); }).then(function(cfg) {
            var wsUrl = cfg.url;
            if (!wsUrl) return;
            ws = new WebSocket(wsUrl + '?channel=' + encodeURIComponent(wsChannel) + '&token=' + encodeURIComponent(sessionId));
            ws.onmessage = function(e) {
              var msg = JSON.parse(e.data);
              var data = msg.data || msg;
              if (data.type) {          // ★ 任何带 type 的推送都刷新
                refreshAll();
              }
            };
            ws.onclose = function() {
                ws = null;
                setTimeout(connectWS, 3000);   // 3 秒重连
            };
        }).catch(function(e) {
            console.warn('[WS] connect failed:', e);
        });
    }

    ★ 禁止：__WS_URL__ / ws://127.0.0.1:9001 / location.port + 1
    ═══════════════════════════════════════
    【WebSocket 实时推送规范 — 需求涉及实时刷新时必须遵守】
    ═══════════════════════════════════════
    如果需求涉及"实时刷新"、"实时推送"、"多端同步"、"实时更新"、"WebSocket 推送"等，前端必须建立 WebSocket 连接。

    ★ 判断依据：需求里出现以下关键词就必须建 WS：
      - 实时 / 实时刷新 / 实时更新 / 实时推送 / 实时同步
      - 多端同步 / 多页面同步
      - WebSocket / WS / 推送
      - "变更时刷新"

    前端代码模板：

    var ws = null;

    function getSessionId() {
        var match = document.cookie.match(/aicp_token=([^;]+)/);
        return match ? match[1] : 'default';
    }

    function connectWS() {
        var sessionId = getSessionId();
        var wsChannel = 'pa_' + sessionId;   // ★ 用 pa_{sessionId}
        fetch('/api/ws_config').then(function(r) { return r.json(); }).then(function(cfg) {
            var wsUrl = cfg.url;
            if (!wsUrl) return;
            ws = new WebSocket(wsUrl + '?channel=' + encodeURIComponent(wsChannel) + '&token=' + encodeURIComponent(sessionId));
            ws.onmessage = function(e) {
                var msg = JSON.parse(e.data);
                var data = msg.data || msg;
                if (data.type) {          // ★ 任何带 type 的推送都刷新
                    refreshAll();
                }
            };
            ws.onclose = function() {
                ws = null;
                setTimeout(connectWS, 3000);
            };
            ws.onerror = function() {
                if (ws) ws.close();
            };
        }).catch(function(e) {
            console.warn('[WS] connect failed:', e);
        });
    }

    铁律：
    1. 需求涉及实时推送 → 必须调用 connectWS()
    2. connectWS() 必须在 init() 里调用
    3. channel 用 'pa_' + getSessionId()（不是 project，不是硬编码）
    4. ws.onmessage 里 ★ 不判断具体 type，只要 data.type 存在就 refreshAll()
       - 原因：后端推送的 type 可能随时变化，前端判断具体值容易对不上
       - 正确：if (data.type) { refreshAll(); }
       - 错误：if (data.type === 'bill_update') { ... }（type 对不上就不刷新）
    5. WS 地址必须 fetch('/api/ws_config')，不硬编码端口
    6. request() 必须传 meta: { session_id: getSessionId() }（见上方 request 模板）
       - 后端从 envelop.meta.session_id 取，推 pa_{session_id}
       - 前端连 pa_{getSessionId()}，两端必须一致

    禁止：
    ❌ 不建 WebSocket 连接（需求说了实时推送，前端必须建）
    ❌ 硬编码 ws://127.0.0.1:9001/ws
    ❌ channel 用 'pa_' + project
    ❌ 判断具体 type（用 data.type 存在就刷新）
    ❌ request 不传 meta.session_id
    ═══════════════════════════════════════
    【流式接收规范 — 后端 streaming 非空时必须遵守】
    ═══════════════════════════════════════
    1. WebSocket channel 必须是 'pa_' + getSessionId()
    2. fetch 时必须传 meta: {session_id: getSessionId()}
    3. 只处理 msg.type === 'summary_stream'（或后端声明的 type）
    4. 首次收到 chunk 创建气泡，后续追加到同一个气泡（streamBubble 全局唯一）
    5. 每次追加后滚动到底部

    async function sendChat() {
        streamBubble = null;
        await fetch(API + '/' + PLUGIN, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                payload: {action: 'chat_stream', messages: [...]},
                meta: {session_id: getSessionId()}   // ★ 必须传
            })
        });
    }

    ═══════════════════════════════════════
    【文件上传规范】
    ═══════════════════════════════════════
    上传地址动态获取，禁止硬编码：

    async function uploadFile(file) {
        var cfg = await fetch('/api/upload_config').then(function(r) { return r.json(); });
        var uploadUrl = cfg.url;
        if (!uploadUrl) { alert('上传服务不可用'); return; }
        var formData = new FormData();
        formData.append('file', file);
        var resp = await fetch(uploadUrl, { method: 'POST', body: formData });
        var data = await resp.json();
        if (!data.success) { alert('上传失败: ' + (data.error || '未知')); return; }
        return data.file_path;
    }

    ★ 禁止：__UPLOAD_URL__ / http://127.0.0.1:9002 / location.port + 2

    ═══════════════════════════════════════
    【URL 来源约定】
    ═══════════════════════════════════════
    - 项目名：var project = window.location.pathname.split('/')[1];
    - WS 地址：fetch('/api/ws_config') 拿 cfg.url
    - 上传地址：fetch('/api/upload_config') 拿 cfg.url
    - 禁止硬编码端口（9000 / 9001 / 9002）或主机名
    - 禁止用 __WS_URL__ / __UPLOAD_URL__ / __AICP_PROJECT__ 占位符

    ═══════════════════════════════════════
    【DOM 安全更新铁律】
    ═══════════════════════════════════════
    清空容器用 while 循环，不要用 innerHTML = ''：

    ✅ 正确：while (container.firstChild) container.removeChild(container.firstChild);
    ❌ 错误：container.innerHTML = '';

    清空前不依赖 querySelector 的结果是否存在，直接清空后再重建。

    ═══════════════════════════════════════
    【数据判空规范】
    ═══════════════════════════════════════
    - 所有从 API 返回的数据，使用前必须判空
    - 数组用 for 循环遍历，不要用 forEach
    - 对象字段用 || 给默认值：

      var items = data.items || [];
      for (var i = 0; i < items.length; i++) { ... }
      var status = data.status || '未知';

    - 列表为空时必须显示"暂无数据"提示：

      if (items.length === 0) {
          container.innerHTML = '<p style="text-align:center;color:#999;padding:40px;">暂无数据</p>';
          return;
      }

    ═══════════════════════════════════════
    【事件处理】
    ═══════════════════════════════════════
    禁止隐式 event，用显式传参：

    ✅ 正确：
      <button onclick="switchTab('tasks', this)">任务</button>
      function switchTab(tabName, el) { el.classList.add('active'); }

    onclick 传参时禁止字符串拼接转义，用 data 属性 + 事件监听：

    ✅ 正确：
      var button = document.createElement('button');
      button.textContent = '执行';
      button.setAttribute('data-task-id', task.task_id);
      button.addEventListener('click', function() {
          runTask(this.getAttribute('data-task-id'));
      });

    ❌ 错误：
      '<button onclick="runTask(\\'' + task.task_id + '\\')">执行</button>'

    ═══════════════════════════════════════
    【UI 经验规则 — 必读】
    ═══════════════════════════════════════
    1. 页面加载后必须有一个能刷新全部数据的主函数（如 refreshAll），
       它必须被 init() 调用，也必须被所有增删改操作完成后调用。

    2. 所有模态框必须成对出现：打开函数 + 关闭函数，且关闭函数必须清理当前选中的对象。

    3. DOM 引用必须完整：openDetail 里引用的容器必须在 HTML 中存在。

    4. 每个列表渲染函数必须处理空状态。

    5. 批量操作必须在操作完成后清空选中状态。

    6. 进度联动和状态联动必须写在同一个地方。

    7. 如果页面包含统计卡片，统计数据和列表数据必须由同一个刷新函数同步更新。

    8. 生成完成后，必须自我检查以上 7 条规则，确认全部满足再输出。

    9. ★ 流式页面必须建立 WebSocket 连接，接收 summary_stream 消息
    10. ★ 流式气泡全局唯一（streamBubble），不要每次 chunk 都新建
    11. ★ 流式 fetch 必须传 meta.session_id，否则后端推 pa_default
    12. ★ WebSocket 的 channel 用 'pa_' + getSessionId()，不是 project
    13. ★ 需求涉及实时推送 → 必须调用 connectWS()，且 init() 里调用
    14. ★ ws.onmessage 里只要 data.type 存在就 refreshAll()，不判断具体 type 值

    ═══════════════════════════════════════
    【视觉风格】
    ═══════════════════════════════════════
    - 响应式布局，移动端友好
    - 简洁、现代、留白充足
    - 按钮、卡片、表单有明确的 hover / focus 状态
    - 避免花哨的装饰性样式

    ═══════════════════════════════════════
    【生成前自检 — 必须逐条检查】
    ═══════════════════════════════════════
    □ 所有调用的函数都有定义（搜索 `xxx(`，确认有 `function xxx(`）
    □ 所有使用的变量都有声明（搜索 `currentView`，确认有 `var currentView`）
    □ 所有 DOM 元素的 ID 在 HTML 里存在（搜索 `getElementById('xxx')`，确认 HTML 里有 `id="xxx"`）
    □ 所有 onclick / onchange 引用的函数都有定义
    □ 所有后端 API 的字段名和前端读取的一致
    □ WebSocket 订阅用 os/_websocket 的协议：
      ws.send(JSON.stringify({ type: 'subscribe', channel: 'xxx' }))
      不是 { type: 'subscribe_vote', vote_id: 'xxx' }

    ═══════════════════════════════════════
    【输出前自检】
    ═══════════════════════════════════════
    □ 第一行 <!DOCTYPE html>，最后一行 </html>
    □ 至少一个 <script> 或 <style>
    □ 无占位符（<!-- Content -->、// your code here）
    □ 所有 fetch 的 action 和字段名与后端契约一致
    □ 变量名避开 window 全局（history、location、name、status）
    □ 无 const / let / 箭头函数 / 模板字符串
    □ 列表渲染有空状态
    □ 生成完成后自我检查 UI 经验规则 7 条
    □ ★ 需求涉及实时推送 → 已建立 WebSocket 连接，channel 是 'pa_' + getSessionId()
    □ ★ 需求涉及实时推送 → init() 里调用了 connectWS()
    □ ★ ws.onmessage 用 data.type 判断（不判断具体 type 值）
    □ ★ request() 传了 meta: { session_id: getSessionId() }
    □ 如需 WS，用 fetch('/api/ws_config') 拿地址，无占位符
    □ 如需上传，用 fetch('/api/upload_config') 拿地址，无占位符
    □ ★ 后端 streaming 非空 → 已建立 WebSocket 连接，channel 是 'pa_' + getSessionId()
    □ ★ 流式 fetch 传了 meta.session_id
    □ ★ 流式气泡全局唯一（streamBubble）
    □ ★ 无 __WS_URL__ / __UPLOAD_URL__ / __AICP_PROJECT__ 占位符
    """;

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = str(envelop.payload.get("action"));
        if (action.isEmpty()) action = "create";

        String projectName = str(envelop.payload.get("project_name")).trim();
        String description = str(envelop.payload.get("description")).trim();
        String backendContract = str(envelop.payload.get("backend_contract"));

        if (projectName.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 project_name");
            return envelop;
        }
        if (description.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 description");
            return envelop;
        }

        Object llmObj = agent.get("llm");
        if (!(llmObj instanceof LLM llm)) {
            envelop.payload = Map.of("ok", false, "error", "LLM 未配置");
            return envelop;
        }

        String sessionId = str(envelop.meta.get("session_id"));
        if (sessionId.isEmpty()) sessionId = str(envelop.payload.get("session_id"));
        if (sessionId.isEmpty()) sessionId = "default";

        switch (action) {
            case "create":
                return handleCreate(envelop, agent, llm, projectName, description, backendContract, sessionId);
            case "fix":
                return handleFix(envelop, agent, llm, projectName, description, backendContract, sessionId);
            default:
                envelop.payload = Map.of("ok", false, "error", "未知 action: " + action);
                return envelop;
        }
    }

    // ============================================================
    // create — 生成 www/{project}/index.html
    // ============================================================

    private Envelop handleCreate(Envelop envelop, Agent agent, LLM llm,
                                  String projectName, String description,
                                  String backendContract, String sessionId) {
        Path htmlFile = WWW_DIR.resolve(projectName).resolve("index.html");
        boolean isNew = !Files.exists(htmlFile);

        String prompt = buildPrompt(isNew, projectName, description, backendContract, null);

        pushProgress(agent, sessionId, "executing",
            (isNew ? "生成前端页面 " : "重新生成前端页面 ") + projectName + "/index.html...");

        String html = generateWithRetry(llm, agent, sessionId, prompt, projectName);
        if (html == null || html.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "HTML 生成失败");
            return envelop;
        }

        // 写文件
        try {
            Files.createDirectories(htmlFile.getParent());
            Files.writeString(htmlFile, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            envelop.payload = Map.of("ok", false, "error", "写入失败: " + e.getMessage());
            return envelop;
        }

        pushProgress(agent, sessionId, "done",
            "前端页面已生成：" + projectName + "/index.html（" + html.length() + " 字）");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("project_name", projectName);
        data.put("file", htmlFile.toString().replace("\\", "/"));
        data.put("url", "/" + projectName + "/");
        data.put("size", html.length());
        data.put("is_new", isNew);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("data", data);
        result.put("message", "✅ 前端页面已生成，访问 /" + projectName + "/");
        envelop.payload = result;
        return envelop;
    }

    // ============================================================
    // fix — 修复已有 HTML
    // ============================================================

    private Envelop handleFix(Envelop envelop, Agent agent, LLM llm,
                               String projectName, String description,
                               String backendContract, String sessionId) {
        Path htmlFile = WWW_DIR.resolve(projectName).resolve("index.html");
        if (!Files.exists(htmlFile)) {
            envelop.payload = Map.of("ok", false, "error",
                "前端页面不存在: " + projectName + "/index.html");
            return envelop;
        }

        String currentHtml;
        try {
            currentHtml = Files.readString(htmlFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            envelop.payload = Map.of("ok", false, "error", "读取失败: " + e.getMessage());
            return envelop;
        }

        String prompt = buildPrompt(false, projectName, description, backendContract, currentHtml);

        pushProgress(agent, sessionId, "executing",
            "修复前端页面 " + projectName + "/index.html...");

        String html = generateWithRetry(llm, agent, sessionId, prompt, projectName);
        if (html == null || html.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "HTML 修复失败");
            return envelop;
        }

        try {
            Files.writeString(htmlFile, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            envelop.payload = Map.of("ok", false, "error", "写入失败: " + e.getMessage());
            return envelop;
        }

        pushProgress(agent, sessionId, "done",
            "前端修复完成：" + projectName + "/index.html（" + html.length() + " 字）");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("project_name", projectName);
        data.put("file", htmlFile.toString().replace("\\", "/"));
        data.put("url", "/" + projectName + "/");
        data.put("size", html.length());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("data", data);
        result.put("message", "✅ 前端修复完成");
        envelop.payload = result;
        return envelop;
    }

    // ============================================================
    // prompt 构建
    // ============================================================

    private String buildPrompt(boolean isNew, String projectName, String description,
                                String backendContract, String currentHtml) {
        StringBuilder sb = new StringBuilder();

        if (isNew) {
            sb.append("生成一个完整的 HTML 页面。\n\n");
        } else if (currentHtml == null) {
            sb.append("重新生成一个完整的 HTML 页面。\n\n");
        } else {
            sb.append("修复以下 HTML 页面。\n\n");
        }

        sb.append("【项目名】").append(projectName).append("\n\n");

        if (backendContract != null && !backendContract.isEmpty()) {
            sb.append("【后端契约 — 前端必须严格按此调用】\n");
            sb.append(backendContract).append("\n\n");
        } else {
            sb.append("【后端契约】\n本项目为纯前端应用，无后端 API。所有数据在浏览器本地处理。\n\n");
        }

        sb.append("【需求】\n").append(description).append("\n\n");

        if (currentHtml != null) {
            sb.append("【当前 HTML — 必须全量输出修改后的完整版本】\n\n");
            sb.append(currentHtml).append("\n\n");
            sb.append("【修复原则】\n");
            sb.append("1. 默认保持原有功能和样式不变\n");
            sb.append("2. 只修改与修复需求直接相关的部分\n");
            sb.append("3. 不要擅自优化、重构、或改变未提及的功能\n");
            sb.append("4. 必须输出完整 HTML，从 <!DOCTYPE html> 到 </html>\n");
            sb.append("5. 禁止省略、截断、或用省略号\n\n");
        }

        sb.append("只输出 === HTML: index.html === ... === END === 块。");
        return sb.toString();
    }

    // ============================================================
    // 生成 + 质量校验 + 重试
    // ============================================================

    private String generateWithRetry(LLM llm, Agent agent, String sessionId,
                                      String basePrompt, String projectName) {
        String lastError = "";

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String prompt = basePrompt;
            if (attempt > 0 && !lastError.isEmpty()) {
                prompt += "\n\n【上一次生成失败，原因：" + lastError + "】\n请重新生成，严格遵守所有铁律。";
            }

            String raw = streamLLM(llm, agent, sessionId, prompt, projectName);
            if (raw == null || raw.isEmpty()) {
                lastError = "LLM 返回为空";
                continue;
            }

            // ★ 用 extractHtmlWithContinuation（内部会检测 + 续写）
            String html = extractHtmlWithContinuation(llm, agent, sessionId, raw, projectName);
            if (html == null || html.isEmpty()) {
                lastError = "无法从 LLM 输出提取 HTML";
                pushProgress(agent, sessionId, "executing", "⚠️ " + lastError + "，重试中...");
                continue;
            }

            String reason = validateHtml(html);
            if (reason == null) {
                return html;
            }

            lastError = reason;
            pushProgress(agent, sessionId, "executing", "⚠️ " + reason + "，重试中...");
        }

        return null;
    }

    /** ★ 流式 LLM 调用，每 chunk 推 code_stream */
    private String streamLLM(LLM llm, Agent agent, String sessionId,
                              String prompt, String projectName) {
        final StringBuilder buffer = new StringBuilder();
        final AtomicBoolean hasError = new AtomicBoolean(false);
        final AtomicReference<String> errorMsg = new AtomicReference<>("");

        llm.chatStream(List.of(
            Map.of("role", "system", "content", FRONTEND_SYSTEM),
            Map.of("role", "user", "content", prompt)
        ), null, null, (delta, done, error) -> {
            if (delta != null && !delta.isEmpty()) {
                buffer.append(delta);
                pushCode(agent, sessionId, delta, projectName);
            }
            if (done && error != null && !error.isEmpty()) {
                hasError.set(true);
                errorMsg.set(error);
            }
        });

        if (hasError.get()) {
            System.err.println("[frontend_agent] LLM 流式失败: " + errorMsg.get());
            return "";
        }

        return buffer.toString();
    }

    // ============================================================
    // HTML 续写
    // ============================================================

    /**
     * HTML 被截断时续写。
     * 思路：把"完整 HTML"（不是尾部片段）给 LLM，让它从断点续写到 </html>。
     * LLM 能看到全局命名，续写的内容才能和前面一致。
     */
    private String continueHtml(LLM llm, Agent agent, String sessionId,
                                String partialHtml, String projectName,
                                int maxContinuations) {
        if (llm == null) return partialHtml;

        String html = partialHtml;

        for (int attempt = 0; attempt < maxContinuations; attempt++) {
            String lower = html.trim().toLowerCase();

            // 完整
            if (lower.endsWith("</html>")) {
                return html;
            }

            // 只补 </html>
            if (lower.contains("</body>")) {
                return html.trim() + "\n</html>";
            }

            // 真的截断，走续写
            String prompt = "【HTML 被截断了，请从断点续写到 </html>】\n\n"
                + "以下是**完整的已生成 HTML**（从头到断点）：\n\n"
                + "```html\n" + html + "\n```\n\n"
                + "【续写规则】\n"
                + "1. **从\"最后一个完整的语法单元之后\"开始**\n"
                + "   - 如果断点在变量声明中间（如 `var options`），从 `var options` 之前重新声明整个变量\n"
                + "   - 如果断点在函数定义中间，从这个函数开头重新写\n"
                + "   - 如果断点在字符串/对象/数组中间，从这个字符串/对象/数组开头重新写\n"
                + "2. **如果为了语法完整需要重复一点，重复的部分必须和上面已有的代码\"字节级完全一致\"**\n"
                + "3. **只输出从断点开始的续写内容**，不要重复前面已完成的代码\n"
                + "4. **必须沿用上面已有的所有命名**：函数名、变量名、HTML 元素 ID、CSS 类名\n"
                + "5. 写完所有剩余内容后，闭合 </script>、</body>、</html>\n"
                + "6. 直接输出续写内容，不要重复 <!DOCTYPE html>、<html>、<head>、<style>\n\n"
                + "只输出从断点开始的续写内容。";

            String continuation = "";
            try {
                final StringBuilder buf = new StringBuilder();
                final AtomicBoolean hasError = new AtomicBoolean(false);
                final AtomicReference<String> errorMsg = new AtomicReference<>("");

                llm.chatStream(
                    List.of(Map.of("role", "user", "content", prompt)),
                    null, null,
                    (delta, done, error) -> {
                        if (delta != null && !delta.isEmpty()) {
                            buf.append(delta);
                            pushCode(agent, sessionId, delta, projectName);
                        }
                        if (done && error != null && !error.isEmpty()) {
                            hasError.set(true);
                            errorMsg.set(error);
                        }
                    }
                );

                if (hasError.get()) {
                    System.err.println("[frontend_agent] 续写失败: " + errorMsg.get());
                    return html;
                }
                continuation = buf.toString();
            } catch (Exception e) {
                System.err.println("[frontend_agent] 续写异常: " + e.getMessage());
                return html;
            }

            if (continuation == null || continuation.isEmpty()) {
                return html;
            }

            // 清理代码块标记
            continuation = continuation.trim();
            String[] prefixes = {"```html", "```javascript", "```js", "```"};
            for (String prefix : prefixes) {
                if (continuation.startsWith(prefix)) {
                    continuation = continuation.substring(prefix.length());
                    break;
                }
            }
            String[] suffixes = {"```", "~~~"};
            for (String suffix : suffixes) {
                if (continuation.endsWith(suffix)) {
                    continuation = continuation.substring(0, continuation.length() - suffix.length());
                    break;
                }
            }
            continuation = continuation.trim();

            // 移除可能的 html_content = '''...''' 包裹
            Pattern p = Pattern.compile("html_content\\s*=\\s*['\"]{3}([\\s\\S]*?)['\"]{3}");
            Matcher m = p.matcher(continuation);
            if (m.find()) {
                continuation = m.group(1).trim();
            }

            // ★★★ 关键：重叠检测 + 去重 ★★★
            int overlapLen = 0;
            int maxCheck = Math.min(html.length(), continuation.length());
            // 从大到小找重叠（至少 8 字符才算）
            for (int i = maxCheck; i >= 8; i--) {
                String tail = html.substring(html.length() - i);
                if (continuation.startsWith(tail)) {
                    overlapLen = i;
                    break;
                }
            }

            if (overlapLen > 0) {
                System.out.println("[frontend_agent] 检测到重叠 " + overlapLen + " 字符，去重");
                continuation = continuation.substring(overlapLen);
            }

            // ★ 如果"前半段结尾" 是"半截语句"（没有分号/换行/闭合符），
            //    且"续写开头" 是"完整的语句"，可能"前半段的半截" 要"删掉" 或 "续写补全"
            // 简单处理：如果"前半段结尾" 不是"完整语法单元"（没分号/换行/}），
            //   且"续写开头" 是"完整声明"（如 `var xxx`），说明"前半段的半截" 应该"被续写覆盖"
            // 找到"前半段最后一个完整语句" 的位置
            int lastCompletePos = findLastCompleteStatement(html);
            if (lastCompletePos >= 0 && lastCompletePos < html.length()) {
                String halfStatement = html.substring(lastCompletePos);
                // 如果"续写" 里包含"这个半截语句的续写"（或"完整版本"），
                // 则"前半段的半截" 应该"被去掉"
                String halfTrimmed = halfStatement.trim();
                if (!halfTrimmed.isEmpty() && continuation.contains(halfTrimmed)) {
                    // 续写里有"半截语句的续写"，把"前半段的半截" 删掉
                    System.out.println("[frontend_agent] 检测到半截语句，去半截");
                    html = html.substring(0, lastCompletePos);
                }
            }

            html = html + continuation;

            pushProgress(agent, sessionId, "executing",
                "HTML 续写完成（第 " + (attempt + 1) + " 次），当前 " + html.length() + " 字");
        }

        return html;
    }

    /**
     * 找到"最后一个完整语句" 的位置。
     * 完整语句 = 以 `;` / `}` / `)` / 换行 结尾（粗略判断）。
     * 返回"完整语句之后" 的位置（半截语句的开始）。
     */
    private int findLastCompleteStatement(String text) {
        // 从后往前找"分号" / "右大括号"
        int lastSemicolon = text.lastIndexOf(';');
        int lastBrace = text.lastIndexOf('}');
        int lastParen = text.lastIndexOf(')');
        int lastPos = Math.max(Math.max(lastSemicolon, lastBrace), lastParen);
        if (lastPos < 0) return 0;
        return lastPos + 1;
    }

    /**
     * 提取 HTML + 完整性检查 + 续写。
     */
    private String extractHtmlWithContinuation(LLM llm, Agent agent, String sessionId,
                                               String raw, String projectName) {
        // 1. 标准提取
        String html = extractHtml(raw);

        // 2. 宽松提取
        if (html == null || html.isEmpty()) {
            Matcher m = Pattern.compile("(<!DOCTYPE html>[\\s\\S]*)$", Pattern.CASE_INSENSITIVE).matcher(raw);
            if (m.find()) {
                html = m.group(1);
            } else {
                return null;   // 真的没 HTML
            }
        }

        // 3. 检查完整性
        String lower = html.trim().toLowerCase();

        if (!lower.contains("<!doctype")) {
            return null;   // 没有 DOCTYPE，不是 HTML
        }

        if (lower.endsWith("</html>")) {
            return html;   // 完整
        }

        if (lower.contains("</body>")) {
            return html.trim() + "\n</html>";   // 只补 </html>
        }

        // 4. 截断 → 续写
        pushProgress(agent, sessionId, "executing", "HTML 被截断，正在续写...");
        html = continueHtml(llm, agent, sessionId, html, projectName, MAX_CONTINUATIONS);

        // 5. 最终检查
        String finalLower = html.trim().toLowerCase();
        if (!finalLower.endsWith("</html>")) {
            if (finalLower.contains("</body>")) {
                html = html.trim() + "\n</html>";
            } else {
                // 续写后仍不完整，标记为失败（让上层重试）
                return null;
            }
        }

        return html;
    }

    // ============================================================
    // 提取 HTML
    // ============================================================

    private String extractHtml(String raw) {
        if (raw == null || raw.isEmpty()) return null;

        // 1. 优先 === HTML: xxx === ... === END ===
        Pattern p1 = Pattern.compile(
            "=== HTML:\\s*[^=]*===\\s*\\n([\\s\\S]*?)=== END ===",
            Pattern.CASE_INSENSITIVE
        );
        Matcher m1 = p1.matcher(raw);
        if (m1.find()) return cleanup(m1.group(1));

        // 2. <!DOCTYPE html> ... </html>
        Pattern p2 = Pattern.compile(
            "(<!DOCTYPE html>[\\s\\S]*?</html>)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher m2 = p2.matcher(raw);
        if (m2.find()) return m2.group(1);

        // 3. 剥 markdown 代码块
        Pattern p3 = Pattern.compile("```html\\s*\\n([\\s\\S]*?)```");
        Matcher m3 = p3.matcher(raw);
        if (m3.find()) return cleanup(m3.group(1));

        // 4. 宽松：找 <!DOCTYPE 到文末，裁到最后一个 </html>
        Pattern p4 = Pattern.compile("(<!DOCTYPE html>[\\s\\S]*)$", Pattern.CASE_INSENSITIVE);
        Matcher m4 = p4.matcher(raw);
        if (m4.find()) {
            String text = m4.group(1);
            int lastClose = text.toLowerCase().lastIndexOf("</html>");
            if (lastClose >= 0) return text.substring(0, lastClose + 7);
            return text;
        }

        return null;
    }

    private String cleanup(String html) {
        if (html == null) return null;
        String s = html.trim();

        if (s.startsWith("```html")) s = s.substring(7).trim();
        else if (s.startsWith("```")) s = s.substring(3).trim();
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3).trim();

        return s;
    }

    // ============================================================
    // 质量校验
    // ============================================================

    /** 返回 null 表示通过，否则返回失败原因 */
    private String validateHtml(String html) {
        if (html == null || html.isEmpty()) return "empty output";
        if (html.length() < MIN_HTML_SIZE) {
            return "too short (" + html.length() + " bytes, need >= " + MIN_HTML_SIZE + ")";
        }
        if (html.length() > MAX_HTML_CHARS) {
            return "too long (" + html.length() + " bytes, need <= " + MAX_HTML_CHARS + ")";
        }

        String lower = html.toLowerCase();
        if (!lower.contains("<!doctype")) return "missing <!DOCTYPE>";
        if (!lower.contains("<html")) return "missing <html>";
        if (!lower.contains("</html>")) return "missing </html>";

        // 占位符检测
        String[] placeholders = {
            "<!-- Content -->", "<!-- content -->", "<!-- 内容 -->", "<!-- 你的代码 -->",
            "/* Styles here */", "/* styles here */", "/* 样式 */",
            "// JS here", "// js here", "// JavaScript here",
            "// your code here", "// 你的代码", "// TODO", "// todo"
        };
        for (String ph : placeholders) {
            if (html.contains(ph)) return "contains placeholder: " + ph;
        }

        if (!lower.contains("<script") && !lower.contains("<style")) {
            return "no <script> or <style> block";
        }

        return null;
    }

    // ============================================================
    // WS 推送
    // ============================================================

    private void pushCode(Agent agent, String sessionId, String chunk, String projectName) {
        try {
            Envelop env = new Envelop("builtins/agents/frontend_agent", "os/_websocket");
            env.payload.put("action", "push");
            env.payload.put("channel_id", "pa_" + sessionId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "code_stream");
            data.put("chunk", chunk);
            data.put("plugin", projectName + "/index.html");
            env.payload.put("data", data);
            agent.call(env);
        } catch (Exception ignored) {}
    }

    private void pushProgress(Agent agent, String sessionId, String step, String msg) {
        try {
            Envelop env = new Envelop("builtins/agents/frontend_agent", "os/_websocket");
            env.payload.put("action", "push");
            env.payload.put("channel_id", "pa_" + sessionId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "progress");
            data.put("step", step);
            data.put("msg", msg);
            data.put("message", msg);
            env.payload.put("data", data);
            agent.call(env);
        } catch (Exception ignored) {}
    }

    // ============================================================
    // helpers
    // ============================================================

    private static String str(Object o) { return o != null ? o.toString() : ""; }
}

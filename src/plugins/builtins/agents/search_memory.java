import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.runtime.LLM;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * builtins/agents/search_memory — 历史记忆搜索 + 复盘
 *
 * action:
 *   get     — 搜索历史记忆，回答问题
 *   reflect — 全量事件复盘，沉淀经验进背包
 */
public class search_memory implements Plugin {

    // ============================================================
    // 常量
    // ============================================================

    private static final Path MEMORY_DIR = Path.of("data", "memories", "main_agent");
    private static final Path EXPERIENCE_DIR = MEMORY_DIR.resolve("experiences");
    private static final Path REFLECTION_DIR = MEMORY_DIR.resolve("reflections");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSSSSS");

    // 信号标记
    private static final List<String> ERROR_MARKERS = List.of(
        "error", "失败", "报错", "异常", "traceback", "timeout", "超时",
        "重试", "retry", "invalid", "缺少", "未找到", "not found",
        "failed", "failure", "refused", "blocked", "crash", "exception",
        "无法", "不能", "denied", "forbidden"
    );
    private static final List<String> CORRECTION_MARKERS = List.of(
        "不对", "错了", "不是", "应该", "其实", "纠正", "别", "不要",
        "重新", "停", "有问题", "wrong", "incorrect",
        "我说的是", "我的意思是"
    );
    private static final List<String> RECURSION_MARKERS = List.of(
        "递归", "深度", "深度过高", "已达上限", "执行深度",
        "recursion", "depth", "max depth", "limit reached"
    );

    // ============================================================
    // Prompt
    // ============================================================

    private static final String SIGNAL_REVIEW_SYSTEM_PROMPT = """
        你是执行记录的信号复核器。你的职责：从一批"规则初筛出的候选异常条目"里，判断哪些真正构成异常信号。

        ## 判断标准

        真正构成异常信号：
        - 出现了实际错误、报错、异常、traceback
        - 出现了重试、超时、工具调用失败
        - 用户明确纠正了 agent 的做法
        - 递归过深、执行深度触顶
        - 决策质量明显有问题（即使没报错）

        不构成异常信号：
        - 只是正常提到某个词，没有造成实际问题
        - 用户说"好的""谢谢""不对不对我说错了"这类口头语，不构成纠正
        - 正常技术讨论里出现"error"这个词，但只是在讲概念

        ## 输出格式（严格 JSON，不要解释、不要 markdown 代码块）

        {
          "items": [
            {
              "timestamp": "...",
              "from": "...",
              "signals": ["error"|"correction"|"recursion"|"other"],
              "reason": "一句话说明为什么构成信号"
            }
          ]
        }

        如果全部不构成信号，输出：
        {"items": []}""";

    private static final String REFLECT_SYSTEM_PROMPT = """
        你是决策复盘器。你的唯一职责：回看一段 agent 的执行记录（已按事件切分），并对照当前经验背包，产出复盘结果，并判断本次是否值得沉淀。

        ## 输入

        1. 【执行事件】：本次窗口内的事件（可能包含多轮、多任务）。
           - is_flagged: true 的事件命中了异常信号，重点复盘。
           - is_flagged: false 的事件是正常流程，作为上下文，也可从中提炼正向经验。
        2. 【经验背包（相关片段）】：当前背包里与本次记录相关的规则。

        ## 处理要点

        - 每个事件单独复盘，不要把不同事件混成一条。
        - 跨事件反复出现的同一个坑，合并成一条，不要按事件重复输出。
        - 异常事件优先；正常流程里若有明显可固化做法或用户偏好，也值得提炼。
        - 只输出真正值得长期保留的条目，宁少勿滥。

        ## 关于「可固化做法」的限定

        「可固化做法」指的是：以后遇到同类任务时可以直接复用的操作规则，不是本次具体怎么配了某个任务。
        - 算：某类任务走固定三步、某类场景默认前台运行、某类查询优先查契约。
        - 不算：这次给某插件配了定时任务、这次把 receiver 改成了谁。

        只描述"这次做了什么"的，不算可固化做法。

        ## 输出：三部分，缺一不可

        ### A. 新发现
        记录里出现、但背包里没有对应规则的「坑」「可优化点」或「可固化做法」。

        ### B. 旧规则复查
        背包里已有的、和本次记录相关的规则，逐条判断它在本次执行中的情况：
        - 仍然有效：这次遵守了，且没出问题。
        - 已过时：这次遵守了，但仍出问题；或场景已不复存在。
        - 需修正：规则方向对，但触发场景/应对方式描述不准，需改。

        ### C. 沉淀判断
        本次复盘有没有真正值得写进长期背包的内容？
        - 有：输出 SETTLE: YES
        - 没有（只是复述现象、没有可执行应对、或全是"仍然有效"无需改动）：输出 SETTLE: NO

        ## 硬性规则

        1. 只看记录和背包里真实存在的内容，不臆测、不补充。
        2. 每条结果必须锚定具体位置：第几步 / 调了什么工具 / 报了什么错；或背包里的哪一条规则。
        3. 语言具体可执行，不要空话（如「要更小心」）。
        4. 新发现最多 5 条，旧规则复查最多 5 条。事件越多，越要合并同类。
        5. 没有内容就写 NONE，不要硬凑。
        6. 如果整体无值得沉淀的东西，只输出：
        NO_EXPERIENCE

        ## 输出格式（严格照此）

        【新发现】
        - [坑/优化] <描述> | 触发场景：<...> | 应对：<...>
        （无则写 NONE）

        【旧规则复查】
        - [仍然有效/已过时/需修正] <引用背包规则的关键句> | 本次情况：<...> | 建议：<...>
        （无则写 NONE）

        SETTLE: YES/NO

        只输出上述内容，不要解释、前言、后记。""";

    private static final String MERGE_SYSTEM_PROMPT = """
        你是经验背包的归并器（Experience Backpack Merger）。

        你的职责：把本次复盘产出的经验，安全地归并进当前背包。

        规则：
        1. 以【当前背包】为基础，把【本次复盘】里真正值得长期保留的内容合并进去。
        2. 只合并「具体、可执行、带触发场景和应对」的条目；只是描述现象、没有应对的，丢弃。
        3. 保留当前背包里未被触及的内容，不要因为本次复盘就删掉旧条目。
        4. 旧规则复查里标记为「需修正」的，按建议修正原条目；标记为「已过时」的，删除或标注废弃。
        5. 「仍然有效」的，保持原样。
        6. 新发现与背包已有条目语义重复的，去重，保留更清晰、更完整的版本。
        7. 保持原有格式风格（段落、标题、编号），不要强行重排结构。
        8. 不要输出任何解释、前言、后记。

        只输出归并后的完整背包正文。""";

    // ============================================================
    // 主入口
    // ============================================================

    @Override
    @SuppressWarnings("unchecked")
    public Envelop execute(Envelop envelop, Agent agent) {
        Map<String, Object> payload = envelop.payload != null ? envelop.payload : new LinkedHashMap<>();
        String action = str(payload.get("action"));
        if (action.isEmpty()) action = "get";

        String sessionId = str(payload.get("session_id"));
        if (sessionId.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 session_id");
            return envelop;
        }

        String dateFrom = str(payload.get("date_from"));
        String dateTo = str(payload.get("date_to"));
        if (dateFrom.isEmpty()) dateFrom = LocalDate.now().minusDays(30).format(DATE_FMT);
        if (dateTo.isEmpty()) dateTo = LocalDate.now().format(DATE_FMT);

        List<String> keywords = new ArrayList<>();
        Object kwObj = payload.get("keywords");
        if (kwObj instanceof List<?> list) {
            for (Object o : list) if (o != null) keywords.add(o.toString());
        } else if (kwObj instanceof String s) {
            keywords.add(s);
        }

        try {
            if ("reflect".equals(action)) {
                return handleReflect(envelop, agent, sessionId, payload, keywords, dateFrom, dateTo);
            } else if ("get".equals(action)) {
                return handleGet(envelop, agent, sessionId, payload, keywords, dateFrom, dateTo);
            } else {
                envelop.payload = Map.of("ok", false, "error", "未知 action: " + action);
                return envelop;
            }
        } catch (Exception e) {
            envelop.payload = Map.of("ok", false, "error", "search_memory 异常: " + e.getMessage());
            return envelop;
        }
    }

    // ============================================================
    // action: reflect
    // ============================================================

    private Envelop handleReflect(Envelop envelop, Agent agent, String sessionId,
                                   Map<String, Object> payload, List<String> keywords,
                                   String dateFrom, String dateTo) throws IOException {
        String afterTs = str(payload.get("after_ts"));
        String beforeTs = str(payload.get("before_ts"));
        String focus = str(payload.get("focus")).trim().toLowerCase();

        if (!isValidTs(afterTs)) {
            afterTs = resolveAutoAfterTs(sessionId, 12);
        }

        String windowEnd = !beforeTs.isEmpty()
            ? beforeTs
            : LocalDateTime.now().format(TS_FMT);

        // 1. 窗口内全量 flow
        WindowResult wr = getEntriesInWindow(sessionId, afterTs, beforeTs, dateFrom, dateTo, 400);
        List<Map<String, Object>> entries = wr.entries;
        int total = wr.total;

        if (entries.isEmpty()) {
            envelop.payload = buildReflectResult("NO_EXPERIENCE", 0, total,
                afterTs, beforeTs, false, false, "窗口内无可复盘记录");
            return envelop;
        }

        // 2. 落盘前查重
        String existing = isDuplicateReflection(sessionId, afterTs);
        if (existing != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("answer", "");
            r.put("matched_count", entries.size());
            r.put("total_in_range", total);
            r.put("window", afterTs + " ~ " + (beforeTs.isEmpty() ? "now" : beforeTs));
            r.put("saved", false);
            r.put("skipped", true);
            r.put("skip_reason", "该窗口已反思过");
            r.put("existing_reflection", existing);
            r.put("merged", false);
            envelop.payload = r;
            return envelop;
        }

        // 3. 事件切分
        List<Map<String, Object>> baseEntries = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("timestamp", str(e.get("timestamp")));
            b.put("from", str(e.get("from")));
            b.put("content", str(e.get("content")));
            baseEntries.add(b);
        }
        List<Map<String, Object>> allEpisodes = groupIntoEpisodes(baseEntries, 300);

        // 4. 信号识别
        List<Map<String, Object>> tagged = detectSignalsLlm(agent, entries);
        Set<String> signalTs = new HashSet<>();
        for (Map<String, Object> t : tagged) {
            String ts = str(t.get("timestamp"));
            if (!ts.isEmpty()) signalTs.add(ts);
        }

        for (Map<String, Object> ep : allEpisodes) {
            Set<String> epSigs = new LinkedHashSet<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) ep.get("items");
            for (Map<String, Object> it : items) {
                String ts = str(it.get("timestamp"));
                if (signalTs.contains(ts)) {
                    for (Map<String, Object> t : tagged) {
                        if (ts.equals(str(t.get("timestamp")))) {
                            Object sigsObj = t.get("signals");
                            if (sigsObj instanceof List<?> sigs) {
                                for (Object s : sigs) epSigs.add(String.valueOf(s));
                            }
                        }
                    }
                }
            }
            ep.put("signals", new ArrayList<>(epSigs));
            ep.put("is_flagged", !epSigs.isEmpty());
        }

        // 5. focus 过滤
        List<Map<String, Object>> episodes = filterByFocus(allEpisodes, focus);
        if (episodes.isEmpty()) {
            envelop.payload = buildReflectResult("NO_EXPERIENCE", 0, total,
                afterTs, beforeTs, false, false, "窗口内无满足 focus 的事件");
            return envelop;
        }

        // 6. 背包相关片段
        String backpack = loadBackpack(sessionId);
        String backpackSnippet;
        if (!keywords.isEmpty()) {
            backpackSnippet = extractRelevantBackpack(backpack, keywords, 60);
        } else {
            backpackSnippet = backpack;
        }

        // 7. 复盘
        String reflectionRaw = generateReflection(agent, episodes, backpackSnippet);

        if (reflectionRaw.isEmpty()
            || reflectionRaw.startsWith("NO_EXPERIENCE")
            || reflectionRaw.startsWith("REFLECT_FAILED")) {
            Map<String, Object> r = buildReflectResult(reflectionRaw, entries.size(), total,
                afterTs, beforeTs, false, false, null);
            r.put("answer", reflectionRaw);
            envelop.payload = r;
            return envelop;
        }

        // 8. 解析 SETTLE
        boolean shouldSettle = parseSettle(reflectionRaw);
        String reflection = stripSettleLine(reflectionRaw);

        Set<String> signals = new TreeSet<>();
        int flaggedCount = 0;
        for (Map<String, Object> ep : episodes) {
            Object sigsObj = ep.get("signals");
            if (sigsObj instanceof List<?> sigs) {
                for (Object s : sigs) signals.add(String.valueOf(s));
            }
            if (Boolean.TRUE.equals(ep.get("is_flagged"))) flaggedCount++;
        }

        // 9. 落盘
        String filePath = saveReflection(sessionId, reflection, afterTs, beforeTs,
            windowEnd, focus, episodes.size(), flaggedCount,
            new ArrayList<>(signals), shouldSettle);

        // 10. SETTLE: NO —— 不归并
        if (!shouldSettle) {
            Map<String, Object> r = buildReflectResult(reflection, entries.size(), total,
                afterTs, beforeTs, true, false, null);
            r.put("episode_count", episodes.size());
            r.put("flagged_count", flaggedCount);
            r.put("signals", new ArrayList<>(signals));
            r.put("focus", focus);
            r.put("reflection_file", filePath);
            r.put("settled", false);
            r.put("merge_note", "复盘判定本次无需沉淀，仅落盘反思");
            r.put("backpack_revision", "");
            r.put("backpack_size", backpack.length());
            envelop.payload = r;
            return envelop;
        }

        // 11. 归并进背包
        boolean mergedOk = false;
        String mergeNote;
        String revision = "";

        if (!backpack.isEmpty()) {
            revision = backupBackpack(sessionId, backpack);
        }

        MergeResult mr = mergeReflectionIntoBackpack(agent, backpack, reflection);
        String newBackpack = mr.content;
        mergeNote = mr.note;

        try {
            saveBackpack(sessionId, newBackpack);
            mergedOk = true;
        } catch (Exception e) {
            mergeNote = "背包落盘失败: " + e.getMessage();
        }

        Map<String, Object> r = buildReflectResult(reflection, entries.size(), total,
            afterTs, beforeTs, true, mergedOk, null);
        r.put("episode_count", episodes.size());
        r.put("flagged_count", flaggedCount);
        r.put("signals", new ArrayList<>(signals));
        r.put("focus", focus);
        r.put("reflection_file", filePath);
        r.put("settled", true);
        r.put("merge_note", mergeNote);
        r.put("backpack_revision", revision);
        r.put("backpack_size", mergedOk ? newBackpack.length() : 0);
        envelop.payload = r;
        return envelop;
    }

    private Map<String, Object> buildReflectResult(String answer, int matched, int total,
                                                    String afterTs, String beforeTs,
                                                    boolean saved, boolean merged,
                                                    String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("answer", answer);
        r.put("matched_count", matched);
        r.put("total_in_range", total);
        r.put("window", afterTs + " ~ " + (beforeTs.isEmpty() ? "now" : beforeTs));
        r.put("saved", saved);
        r.put("merged", merged);
        if (message != null) r.put("message", message);
        return r;
    }

    // ============================================================
    // action: get
    // ============================================================

    private Envelop handleGet(Envelop envelop, Agent agent, String sessionId,
                               Map<String, Object> payload, List<String> keywords,
                               String dateFrom, String dateTo) throws IOException {
        String query = str(payload.get("query"));
        if (query.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 query 参数");
            return envelop;
        }
        if (keywords.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 keywords 参数");
            return envelop;
        }

        SearchResult sr = searchEntriesWithContext(sessionId, keywords, dateFrom, dateTo, 50);
        String answer = generateAnswer(agent, query, sr.entries, dateFrom, dateTo);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("answer", answer);
        r.put("matched_count", sr.entries.size());
        r.put("total_in_range", sr.total);
        r.put("range", dateFrom + " ~ " + dateTo);
        r.put("keywords", keywords);
        envelop.payload = r;
        return envelop;
    }

    // ============================================================
    // 日期与 flow 读取
    // ============================================================

    private static LocalDate parseDate(String s) {
        try { return LocalDate.parse(s, DATE_FMT); } catch (Exception e) { return null; }
    }

    private List<Path> getFlowFiles(String sessionId, String dateFrom, String dateTo) {
        List<Path> files = new ArrayList<>();

        Path rootFile = MEMORY_DIR.resolve(sessionId + "_flow.json");
        if (Files.exists(rootFile)) files.add(rootFile);

        Path flowsDir = MEMORY_DIR.resolve("flows");
        if (Files.exists(flowsDir)) {
            LocalDate today = LocalDate.now();
            LocalDate start = parseDate(dateFrom);
            if (start == null) start = today.minusDays(30);
            LocalDate end = parseDate(dateTo);
            if (end == null) end = today;

            LocalDate cur = start;
            while (!cur.isAfter(end)) {
                String dateKey = cur.format(DATE_FMT);
                Path dayDir = flowsDir.resolve(dateKey);
                Path flowFile = dayDir.resolve(sessionId + ".json");
                if (Files.exists(flowFile) && !files.contains(flowFile)) files.add(flowFile);
                cur = cur.plusDays(1);
            }
        }
        return files;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getAllEntries(String sessionId, String dateFrom, String dateTo) {
        List<Path> files = getFlowFiles(sessionId, dateFrom, dateTo);
        if (files.isEmpty()) return new ArrayList<>();

        List<Map<String, Object>> all = new ArrayList<>();
        for (Path p : files) {
            try {
                String raw = Files.readString(p, StandardCharsets.UTF_8);
                List<Map<String, Object>> entries = MAPPER.readValue(raw, List.class);
                if (entries != null) all.addAll(entries);
            } catch (Exception ignored) {}
        }

        all.sort(Comparator.comparing(e -> str(e.get("timestamp"))));
        return all;
    }

    // ============================================================
    // 打分、去重、清洗
    // ============================================================

    private static String getSearchableText(Map<String, Object> entry) {
        StringBuilder sb = new StringBuilder();
        Object content = entry.get("content");
        if (content != null) sb.append(content);
        Object summary = entry.get("summary");
        if (summary != null) sb.append(" ").append(summary);
        if ("system".equals(str(entry.get("from")))) {
            Object result = entry.get("result");
            if (result != null) sb.append(" ").append(result);
        }
        return sb.toString().toLowerCase();
    }

    private static int scoreEntry(Map<String, Object> entry, List<String> keywords) {
        if (keywords.isEmpty()) return 0;
        String text = getSearchableText(entry);
        if (text.isEmpty()) return 0;
        int score = 0;
        for (String kw : keywords) {
            if (kw != null && !kw.isEmpty() && text.contains(kw.toLowerCase())) score++;
        }
        return score;
    }

    private static String extractAiContent(Map<String, Object> entry) {
        Object c = entry.get("content");
        if (c != null && !c.toString().isEmpty()) return c.toString();
        Object s = entry.get("summary");
        if (s != null) return s.toString();
        return "";
    }

    private static List<Map<String, Object>> deduplicateEntries(List<Map<String, Object>> entries) {
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            String content = str(e.get("content"));
            if (content.isEmpty()) content = str(e.get("summary"));
            String key = str(e.get("timestamp")) + "|" + str(e.get("from")) + "|" + content;
            if (seen.add(key)) result.add(e);
        }
        return result;
    }

    private static Map<String, Object> buildCleanEntry(Map<String, Object> entry) {
        String from = str(entry.get("from"));
        Map<String, Object> clean = new LinkedHashMap<>();
        clean.put("timestamp", str(entry.get("timestamp")));
        clean.put("from", from);
        if ("user".equals(from)) {
            clean.put("content", str(entry.get("content")));
        } else if ("ai".equals(from)) {
            String content = extractAiContent(entry);
            clean.put("content", content.isEmpty() ? str(entry.get("summary")) : content);
        }
        return clean;
    }

    // ============================================================
    // 搜索 + 因果链补全
    // ============================================================

    private static class SearchResult {
        List<Map<String, Object>> entries;
        int total;
    }

    private SearchResult searchEntriesWithContext(String sessionId, List<String> keywords,
                                                   String dateFrom, String dateTo, int limit) {
        SearchResult sr = new SearchResult();
        sr.entries = new ArrayList<>();
        sr.total = 0;

        List<String> kws = keywords.stream()
            .filter(k -> k != null && !k.trim().isEmpty())
            .map(String::trim)
            .distinct()
            .collect(Collectors.toList());

        List<Map<String, Object>> all = getAllEntries(sessionId, dateFrom, dateTo);
        if (all.isEmpty()) return sr;
        sr.total = all.size();

        List<int[]> scored = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            int s = scoreEntry(all.get(i), kws);
            if (s > 0) scored.add(new int[]{s, i});
        }
        if (scored.isEmpty()) return sr;

        scored.sort((a, b) -> Integer.compare(b[0], a[0]));
        List<Integer> anchorIndices = new ArrayList<>();
        for (int i = 0; i < Math.min(30, scored.size()); i++) anchorIndices.add(scored.get(i)[1]);

        Set<Integer> expanded = new TreeSet<>();
        for (int idx : anchorIndices) {
            Map<String, Object> entry = all.get(idx);
            String from = str(entry.get("from"));

            if ("ai".equals(from)) {
                for (int prev = idx - 1; prev > Math.max(0, idx - 20) - 1; prev--) {
                    if ("user".equals(str(all.get(prev).get("from")))) {
                        expanded.add(prev);
                        break;
                    }
                }
                expanded.add(idx);
            } else if ("user".equals(from)) {
                expanded.add(idx);
                for (int next = idx + 1; next < Math.min(all.size(), idx + 20); next++) {
                    if ("ai".equals(str(all.get(next).get("from")))) {
                        expanded.add(next);
                        break;
                    }
                }
            } else if ("system".equals(from)) {
                String action = str(entry.get("action"));
                if (action.contains("reply")) {
                    for (int prev = idx - 1; prev > Math.max(0, idx - 20) - 1; prev--) {
                        if ("user".equals(str(all.get(prev).get("from")))) {
                            expanded.add(prev);
                            break;
                        }
                    }
                    expanded.add(idx);
                }
            }
        }

        List<Map<String, Object>> clean = new ArrayList<>();
        for (int idx : expanded) {
            Map<String, Object> entry = all.get(idx);
            String from = str(entry.get("from"));
            if ("user".equals(from) || "ai".equals(from)) {
                clean.add(buildCleanEntry(entry));
            }
        }
        clean = deduplicateEntries(clean);
        clean.sort(Comparator.comparing(e -> str(e.get("timestamp"))));
        if (clean.size() > limit) clean = clean.subList(0, limit);

        sr.entries = clean;
        return sr;
    }

    // ============================================================
    // 窗口化
    // ============================================================

    private static class WindowResult {
        List<Map<String, Object>> entries;
        int total;
    }

    private WindowResult getEntriesInWindow(String sessionId, String afterTs, String beforeTs,
                                             String dateFrom, String dateTo, int limit) {
        WindowResult wr = new WindowResult();
        wr.entries = new ArrayList<>();
        wr.total = 0;

        List<Map<String, Object>> all = getAllEntries(sessionId, dateFrom, dateTo);
        if (all.isEmpty()) return wr;
        wr.total = all.size();

        List<Map<String, Object>> windowed = new ArrayList<>();
        for (Map<String, Object> e : all) {
            String ts = str(e.get("timestamp"));
            if (!afterTs.isEmpty() && ts.compareTo(afterTs) < 0) continue;
            if (!beforeTs.isEmpty() && ts.compareTo(beforeTs) > 0) continue;
            windowed.add(e);
        }

        List<Map<String, Object>> clean = new ArrayList<>();
        for (Map<String, Object> e : windowed) {
            String from = str(e.get("from"));
            if ("user".equals(from) || "ai".equals(from)) {
                clean.add(buildCleanEntry(e));
            }
        }
        clean = deduplicateEntries(clean);
        clean.sort(Comparator.comparing(e -> str(e.get("timestamp"))));
        if (clean.size() > limit) clean = clean.subList(clean.size() - limit, clean.size());

        wr.entries = clean;
        return wr;
    }

    // ============================================================
    // 时间戳校验 + 反思文件头解析
    // ============================================================

    private static boolean isValidTs(String ts) {
        if (ts == null || ts.isEmpty()) return false;
        ts = ts.trim();
        if (!ts.matches("^\\d{4}-\\d{2}-\\d{2}.*")) return false;
        try {
            LocalDateTime.parse(ts.replace(" ", "T").substring(0, Math.min(19, ts.length())), TS_FMT);
            return true;
        } catch (Exception e) {
            try {
                Instant.parse(ts);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    private static Map<String, String> parseReflectionHeader(String head) {
        Map<String, String> meta = new LinkedHashMap<>();
        for (String line : head.split("\n")) {
            line = line.trim();
            if (!line.startsWith("- ")) continue;
            String body = line.substring(2);
            int colon = body.indexOf(':');
            if (colon < 0) continue;
            String k = body.substring(0, colon).trim();
            String v = body.substring(colon + 1).trim();
            if (!k.isEmpty() && !meta.containsKey(k)) meta.put(k, v);
        }
        return meta;
    }

    // ============================================================
    // 自动窗口
    // ============================================================

    private String resolveAutoAfterTs(String sessionId, int defaultHours) {
        if (Files.exists(REFLECTION_DIR)) {
            try (Stream<Path> stream = Files.list(REFLECTION_DIR)) {
                List<Path> files = stream
                    .filter(p -> p.getFileName().toString().startsWith(sessionId + "_"))
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (IOException e) { return 0; }
                    })
                    .collect(Collectors.toList());

                for (Path fp : files) {
                    try {
                        String content = Files.readString(fp, StandardCharsets.UTF_8);
                        String head = content.substring(0, Math.min(1500, content.length()));
                        Map<String, String> meta = parseReflectionHeader(head);

                        for (String key : new String[]{"window_end", "before_ts"}) {
                            String val = meta.get(key);
                            if (val != null && isValidTs(val)) return val;
                        }

                        String afterVal = meta.get("after_ts");
                        if (afterVal != null && isValidTs(afterVal)) {
                            try {
                                LocalDateTime dt = LocalDateTime.parse(afterVal.substring(0, Math.min(19, afterVal.length())), TS_FMT);
                                return dt.plusHours(defaultHours).format(TS_FMT);
                            } catch (Exception e) {
                                return afterVal;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (IOException ignored) {}
        }
        return LocalDateTime.now().minusHours(defaultHours).format(TS_FMT);
    }

    // ============================================================
    // 信号识别（规则初筛）
    // ============================================================

    private static boolean hasAny(String text, List<String> markers) {
        if (text == null || text.isEmpty()) return false;
        String low = text.toLowerCase();
        for (String m : markers) {
            if (low.contains(m.toLowerCase())) return true;
        }
        return false;
    }

    private static List<Map<String, Object>> detectSignals(List<Map<String, Object>> entries) {
        List<Map<String, Object>> tagged = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            StringBuilder sb = new StringBuilder();
            for (String k : new String[]{"content", "summary", "result", "message", "text"}) {
                Object v = e.get(k);
                if (v != null) sb.append(v).append(" ");
            }
            String text = sb.toString();

            List<String> signals = new ArrayList<>();
            if (hasAny(text, ERROR_MARKERS)) signals.add("error");
            if ("user".equals(str(e.get("from"))) && hasAny(text, CORRECTION_MARKERS)) signals.add("correction");
            if (hasAny(text, RECURSION_MARKERS)) signals.add("recursion");

            if (!signals.isEmpty()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("timestamp", str(e.get("timestamp")));
                t.put("from", str(e.get("from")));
                t.put("signals", signals);
                tagged.add(t);
            }
        }
        return tagged;
    }

    // ============================================================
    // 信号识别（LLM 复核）
    // ============================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> detectSignalsLlm(Agent agent, List<Map<String, Object>> entries) {
        List<Map<String, Object>> tagged = detectSignals(entries);
        if (tagged.isEmpty()) return new ArrayList<>();

        List<Map<String, Object>> candidates = tagged.size() > 80
            ? tagged.subList(tagged.size() - 80, tagged.size())
            : tagged;

        Object llmObj = agent.get("llm");
        if (!(llmObj instanceof LLM llm)) return tagged;

        String itemsJson;
        try { itemsJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(candidates); }
        catch (Exception e) { itemsJson = candidates.toString(); }

        String prompt = "下面是规则初筛出的候选异常条目。请判断哪些真正构成异常信号。\n\n"
            + "【候选条目】\n" + itemsJson + "\n\n请按规则输出 JSON。";

        try {
            String raw = llm.chat(List.of(
                Map.of("role", "system", "content", SIGNAL_REVIEW_SYSTEM_PROMPT),
                Map.of("role", "user", "content", prompt)
            ));
            raw = (raw == null ? "" : raw.trim());
            if (raw.startsWith("```")) {
                raw = raw.replaceFirst("^```[a-zA-Z]*\\n?", "");
                raw = raw.replaceFirst("\\n?```$", "");
            }

            Map<String, Object> data = MAPPER.readValue(raw, Map.class);
            Object itemsObj = data.get("items");
            if (itemsObj instanceof List<?> items) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object it : items) {
                    if (!(it instanceof Map<?, ?> m)) continue;
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("timestamp", str(m.get("timestamp")));
                    r.put("from", str(m.get("from")));
                    Object sigs = m.get("signals");
                    r.put("signals", sigs instanceof List ? sigs : List.of("other"));
                    result.add(r);
                }
                return result;
            }
        } catch (Exception e) {
            System.err.println("[search_memory] LLM 信号复核失败，回退规则结果: " + e.getMessage());
        }
        return tagged;
    }

    // ============================================================
    // 事件切分
    // ============================================================

    private static List<Map<String, Object>> groupIntoEpisodes(List<Map<String, Object>> entries, int gapSeconds) {
        if (entries.isEmpty()) return new ArrayList<>();

        List<Map<String, Object>> episodes = new ArrayList<>();
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("start", str(entries.get(0).get("timestamp")));
        List<Map<String, Object>> currentItems = new ArrayList<>();
        currentItems.add(entries.get(0));
        current.put("items", currentItems);

        Instant prevT = parseInstant(str(entries.get(0).get("timestamp")));

        for (int i = 1; i < entries.size(); i++) {
            Map<String, Object> e = entries.get(i);
            Instant t = parseInstant(str(e.get("timestamp")));

            if (prevT != null && t != null && Duration.between(prevT, t).getSeconds() > gapSeconds) {
                episodes.add(current);
                current = new LinkedHashMap<>();
                current.put("start", str(e.get("timestamp")));
                currentItems = new ArrayList<>();
                currentItems.add(e);
                current.put("items", currentItems);
            } else {
                currentItems.add(e);
            }
            prevT = t != null ? t : prevT;
        }
        episodes.add(current);

        for (Map<String, Object> ep : episodes) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) ep.get("items");
            Set<String> sigs = new TreeSet<>();
            for (Map<String, Object> it : items) {
                Object sObj = it.get("signals");
                if (sObj instanceof List<?> sList) {
                    for (Object s : sList) sigs.add(String.valueOf(s));
                }
            }
            ep.put("signals", new ArrayList<>(sigs));
            ep.put("is_flagged", !sigs.isEmpty());
        }
        return episodes;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s, TS_FMT).atZone(java.time.ZoneId.systemDefault()).toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static List<Map<String, Object>> filterByFocus(List<Map<String, Object>> episodes, String focus) {
        if (focus == null || focus.isEmpty()) return episodes;
        focus = focus.trim().toLowerCase();
        if (focus.equals("window") || focus.equals("all")) return episodes;

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> ep : episodes) {
            Object sigsObj = ep.get("signals");
            if (sigsObj instanceof List<?> sigs) {
                for (Object s : sigs) {
                    if (focus.equals(String.valueOf(s))) {
                        result.add(ep);
                        break;
                    }
                }
            }
        }
        return result;
    }

    // ============================================================
    // 经验背包
    // ============================================================

    private static Path getBackpackFile(String sessionId) {
        try { Files.createDirectories(EXPERIENCE_DIR); } catch (IOException ignored) {}
        return EXPERIENCE_DIR.resolve(sessionId + "_backpack.txt");
    }

    private static String loadBackpack(String sessionId) {
        Path p = getBackpackFile(sessionId);
        if (!Files.exists(p)) return "";
        try { return Files.readString(p, StandardCharsets.UTF_8).trim(); }
        catch (IOException e) { return ""; }
    }

    private static void saveBackpack(String sessionId, String content) throws IOException {
        Path p = getBackpackFile(sessionId);
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private static String backupBackpack(String sessionId, String content) {
        if (content == null || content.isEmpty()) return "";
        Path historyDir = EXPERIENCE_DIR.resolve("history");
        try { Files.createDirectories(historyDir); } catch (IOException ignored) {}
        String ts = LocalDateTime.now().format(FILE_TS_FMT);
        String revision = sessionId + "_" + ts;
        try {
            Files.writeString(historyDir.resolve(revision + ".txt"), content, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
        return revision;
    }

    private static String extractRelevantBackpack(String backpack, List<String> keywords, int maxLines) {
        if (backpack == null || backpack.isEmpty()) return "";
        List<String> kws = keywords.stream()
            .filter(k -> k != null && !k.trim().isEmpty())
            .map(k -> k.trim().toLowerCase())
            .collect(Collectors.toList());

        if (kws.isEmpty()) {
            String[] lines = backpack.split("\n");
            return String.join("\n", Arrays.copyOfRange(lines, 0, Math.min(maxLines, lines.length)));
        }

        String[] lines = backpack.split("\n");
        Set<Integer> hitIdx = new TreeSet<>();
        for (int i = 0; i < lines.length; i++) {
            String low = lines[i].toLowerCase();
            for (String kw : kws) {
                if (low.contains(kw)) {
                    for (int j : new int[]{i - 1, i, i + 1}) {
                        if (j >= 0 && j < lines.length) hitIdx.add(j);
                    }
                    break;
                }
            }
        }
        if (hitIdx.isEmpty()) return "";

        List<Integer> ordered = new ArrayList<>(hitIdx);
        if (ordered.size() > maxLines) ordered = ordered.subList(0, maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(lines[ordered.get(i)]);
        }
        return sb.toString();
    }

    // ============================================================
    // 复盘生成
    // ============================================================

    private String generateReflection(Agent agent, List<Map<String, Object>> episodes, String backpackSnippet) {
        if (episodes.isEmpty()) return "NO_EXPERIENCE";

        List<Map<String, Object>> payload = new ArrayList<>();
        for (Map<String, Object> ep : episodes) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("start", str(ep.get("start")));
            p.put("is_flagged", ep.getOrDefault("is_flagged", false));
            p.put("signals", ep.getOrDefault("signals", new ArrayList<>()));
            p.put("items", ep.getOrDefault("items", new ArrayList<>()));
            payload.add(p);
        }

        String itemsJson;
        try { itemsJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload); }
        catch (Exception e) { itemsJson = payload.toString(); }

        String prompt = "【执行事件】（窗口内事件，按时间排序）：\n\n"
            + itemsJson + "\n\n"
            + "【经验背包（相关片段）】：\n\n"
            + (backpackSnippet != null && !backpackSnippet.isEmpty() ? backpackSnippet : "（无相关片段）")
            + "\n\n请按规则复盘，输出【新发现】【旧规则复查】和 SETTLE 判断。";

        Object llmObj = agent.get("llm");
        if (!(llmObj instanceof LLM llm)) return "REFLECT_FAILED: LLM 未配置";

        try {
            String raw = llm.chat(List.of(
                Map.of("role", "system", "content", REFLECT_SYSTEM_PROMPT),
                Map.of("role", "user", "content", prompt)
            ));
            raw = raw == null ? "" : raw.trim();
            return raw.isEmpty() ? "NO_EXPERIENCE" : raw;
        } catch (Exception e) {
            return "REFLECT_FAILED: " + e.getMessage();
        }
    }

    // ============================================================
    // SETTLE 解析
    // ============================================================

    private static boolean parseSettle(String reflection) {
        if (reflection == null || reflection.isEmpty()) return false;
        Matcher m = Pattern.compile("SETTLE\\s*:\\s*(YES|NO)", Pattern.CASE_INSENSITIVE).matcher(reflection);
        if (!m.find()) return true;
        return "YES".equalsIgnoreCase(m.group(1));
    }

    private static String stripSettleLine(String reflection) {
        if (reflection == null || reflection.isEmpty()) return reflection;
        StringBuilder sb = new StringBuilder();
        String[] lines = reflection.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().matches("(?i)^SETTLE\\s*:.*")) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(lines[i]);
        }
        return sb.toString().trim();
    }

    // ============================================================
    // 归并
    // ============================================================

    private static class MergeResult {
        String content;
        String note;
    }

    private MergeResult mergeReflectionIntoBackpack(Agent agent, String current, String reflection) {
        MergeResult mr = new MergeResult();
        String prompt = MERGE_SYSTEM_PROMPT
            + "\n\n【当前背包】\n" + (current.isEmpty() ? "（空）" : current)
            + "\n\n【本次复盘】\n" + reflection;

        Object llmObj = agent.get("llm");
        if (llmObj instanceof LLM llm) {
            try {
                String merged = llm.chat(List.of(Map.of("role", "user", "content", prompt)));
                merged = merged == null ? "" : merged.trim();
                if (!merged.isEmpty()) {
                    mr.content = merged;
                    mr.note = "已归并";
                    return mr;
                }
            } catch (Exception e) {
                System.err.println("[search_memory.reflect] 归并失败，回退保守合并: " + e.getMessage());
            }
        }

        if (!current.isEmpty() && !reflection.isEmpty()) {
            mr.content = current + "\n\n" + reflection;
            mr.note = "归并失败，已保守追加";
        } else {
            mr.content = current.isEmpty() ? reflection : current;
            mr.note = "归并失败，保留原内容或直接写入";
        }
        return mr;
    }

    // ============================================================
    // 反思落盘 + 查重
    // ============================================================

    private static String isDuplicateReflection(String sessionId, String afterTs) {
        if (!Files.exists(REFLECTION_DIR)) return null;
        if (!isValidTs(afterTs)) return null;

        try (Stream<Path> stream = Files.list(REFLECTION_DIR)) {
            List<Path> files = stream
                .filter(p -> p.getFileName().toString().startsWith(sessionId + "_"))
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                .sorted((a, b) -> {
                    try {
                        return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                    } catch (IOException e) { return 0; }
                })
                .limit(5)
                .collect(Collectors.toList());

            for (Path fp : files) {
                try {
                    String content = Files.readString(fp, StandardCharsets.UTF_8);
                    String head = content.substring(0, Math.min(800, content.length()));
                    Map<String, String> meta = parseReflectionHeader(head);
                    if (afterTs.equals(meta.get("after_ts"))) return fp.toString();
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static String saveReflection(String sessionId, String content, String afterTs,
                                          String beforeTs, String windowEnd, String focus,
                                          int episodeCount, int flaggedCount,
                                          List<String> signals, boolean settle) {
        try { Files.createDirectories(REFLECTION_DIR); } catch (IOException ignored) {}
        String ts = LocalDateTime.now().format(FILE_TS_FMT);
        Path filePath = REFLECTION_DIR.resolve(sessionId + "_" + ts + ".md");

        String header = "# 反思 " + ts + "\n"
            + "- session: " + sessionId + "\n"
            + "- after_ts: " + afterTs + "\n"
            + "- before_ts: " + beforeTs + "\n"
            + "- window_end: " + windowEnd + "\n"
            + "- focus: " + focus + "\n"
            + "- episodes: " + episodeCount + "\n"
            + "- flagged: " + flaggedCount + "\n"
            + "- signals: " + String.join(",", signals) + "\n"
            + "- settle: " + (settle ? "YES" : "NO") + "\n"
            + "- window: " + afterTs + " ~ " + (beforeTs.isEmpty() ? "now" : beforeTs) + "\n\n";

        try {
            Files.writeString(filePath, header + content, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
        return filePath.toString();
    }

    // ============================================================
    // 回答问题
    // ============================================================

    private String generateAnswer(Agent agent, String query, List<Map<String, Object>> entries,
                                   String dateFrom, String dateTo) {
        if (entries.isEmpty()) {
            return "在 " + dateFrom + " ~ " + dateTo + " 期间，未找到与「" + query + "」相关的记录。";
        }

        String itemsJson;
        try { itemsJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entries); }
        catch (Exception e) { itemsJson = entries.toString(); }

        String prompt = "用户想问：" + query + "\n\n"
            + "时间范围：" + dateFrom + " ~ " + dateTo + "\n"
            + "匹配到的对话记录（已按时间排序，已过滤系统噪音）：\n\n"
            + itemsJson + "\n\n"
            + "请根据这些记录，回答用户的问题。\n"
            + "要求：\n"
            + "- 如果记录中有明确答案，直接回答\n"
            + "- 如果记录中包含关键信息（如邮箱、地址、账号、网址等），一定要提取出来\n"
            + "- 按时间顺序还原事件过程\n"
            + "- 如果记录不足以回答问题，说明\"当前记录中未找到足够信息\"\n"
            + "- 不要输出原始记录，直接回答问题\n";

        Object llmObj = agent.get("llm");
        if (!(llmObj instanceof LLM llm)) return "LLM 未配置";

        try {
            String raw = llm.chat(List.of(Map.of("role", "user", "content", prompt)));
            raw = raw == null ? "" : raw.trim();
            if (raw.isEmpty()) {
                return "找到 " + entries.size() + " 条相关记录，但生成回答时返回空。\n\n匹配到的记录摘要：\n"
                    + itemsJson.substring(0, Math.min(1500, itemsJson.length()));
            }
            return raw;
        } catch (Exception e) {
            return "生成回答失败：" + e.getMessage();
        }
    }

    // ============================================================
    // helpers
    // ============================================================

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }
}

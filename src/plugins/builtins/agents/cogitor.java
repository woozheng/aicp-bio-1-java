import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.core.Plugins;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * builtins/agents/cogitor — 系统概览 + 搜索
 *
 * receiver 从"文件路径"推：builtins/agents/cogitor
 *
 * 职责：
 *   - get_map：系统概览（文本）
 *   - list_plugins：一次返回 分类 + 插件名 + 描述
 *   - search：按词搜索（terms 列表 / query 字符串）
 *
 * 分组：applications / builtins / os / 其他
 * 每个分类限 15 个，applications 限 20 个项目
 */
public class cogitor implements Plugin {

    private static final String PLUGINS_ROOT = "src/plugins";
    private static final int PER_GROUP_LIMIT = 15;
    private static final int PER_PROJECT_LIMIT = 20;

    /** 描述缓存 */
    private static final Map<String, String> DESC_CACHE = new HashMap<>();

    /** 拆词：按非中英文数字的符号拆，保留 _ 和 - */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\u4e00-\\u9fffa-zA-Z0-9_-]+");

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "list_plugins";

        switch (action) {
            case "list_plugins":
                return handleListPlugins(envelop);
            case "get_map":
                return handleGetMap(envelop);
            case "search":
                return handleSearch(envelop);
            default:
                envelop.payload = Map.of("ok", false, "error", "Unknown action: " + action);
                return envelop;
        }
    }

    // ============================================================
    // list_plugins
    // ============================================================

    private Envelop handleListPlugins(Envelop envelop) {
        List<String> pluginNames = new ArrayList<>(Plugins.INSTANCE.keySet());
        pluginNames.sort(Comparator.naturalOrder());

        String category = str(envelop.payload.get("category"));

        if (!category.isEmpty()) {
            List<Map<String, Object>> matched = new ArrayList<>();
            for (String name : pluginNames) {
                if (name.equals(category) || name.startsWith(category + "/")) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("name", name);
                    item.put("desc", extractDesc(name));
                    matched.add(item);
                }
            }
            if (matched.isEmpty()) {
                envelop.payload = Map.of("ok", false, "error",
                    "没有找到分类或插件: " + category,
                    "hint", "先用 list_plugins 不带参数查看所有分类");
                return envelop;
            }
            envelop.payload = Map.of("ok", true, "category", category,
                "plugins", matched, "total", matched.size());
            return envelop;
        }

        Map<String, Object> groups = groupWithDesc(pluginNames);

        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("groups", groups);
        result.put("total", pluginNames.size());
        result.put("hint", "每个分类最多列 " + PER_GROUP_LIMIT + " 个。想看完整列表用 list_plugins category=<分类>");

        envelop.payload = result;
        return envelop;
    }

    // ============================================================
    // get_map（人可读的文本概览）
    // ============================================================

    private Envelop handleGetMap(Envelop envelop) {
        List<String> pluginNames = new ArrayList<>(Plugins.INSTANCE.keySet());
        pluginNames.sort(Comparator.naturalOrder());

        Map<String, Object> groups = groupWithDesc(pluginNames);
        int total = pluginNames.size();

        List<String> lines = new ArrayList<>();
        lines.add("系统共有 " + total + " 个后端插件。");
        lines.add("");
        lines.add("【插件分组】");
        lines.add("");

        for (String cat : new TreeSet<>(groups.keySet())) {
            Object infoObj = groups.get(cat);
            if (!(infoObj instanceof Map<?, ?> info)) continue;

            int count = num(info.get("count"));
            Object projectsObj = info.get("projects");

            if (projectsObj instanceof Map<?, ?> projects && !projects.isEmpty()) {
                lines.add("  • " + cat + "（" + count + " 个插件，" + projects.size() + " 个项目）");
                for (Object pKey : new TreeSet<>(projects.keySet())) {
                    Object pVal = projects.get(pKey);
                    if (!(pVal instanceof Map<?, ?> p)) continue;
                    int pCount = num(p.get("count"));
                    String pDesc = str(p.get("desc"));
                    lines.add("      - " + pKey + "（" + pCount + " 个）" + (pDesc.isEmpty() ? "" : " — " + pDesc));
                }
                int more = num(info.get("more_projects"));
                if (more > 0) lines.add("      - ... 还有 " + more + " 个项目");
            } else {
                lines.add("  • " + cat + "（" + count + " 个）");
                Object pluginsObj = info.get("plugins");
                if (pluginsObj instanceof List<?> plugins) {
                    for (Object p : plugins) {
                        if (!(p instanceof Map<?, ?> pm)) continue;
                        String name = str(pm.get("name"));
                        String desc = str(pm.get("desc"));
                        lines.add("      - " + name + (desc.isEmpty() ? "" : " — " + desc));
                    }
                }
                int more = num(info.get("more"));
                if (more > 0) lines.add("      - ... 还有 " + more + " 个");
            }
        }

        envelop.payload = Map.of("ok", true, "summary", String.join("\n", lines), "total", total);
        return envelop;
    }

    // ============================================================
    // search：按词搜索
    // ============================================================

    @SuppressWarnings("unchecked")
    private Envelop handleSearch(Envelop envelop) {
        // 优先 terms，其次 query
        List<String> terms = new ArrayList<>();
        Object termsObj = envelop.payload.get("terms");
        if (termsObj instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) terms.add(o.toString());
            }
        }
        if (terms.isEmpty()) {
            String query = str(envelop.payload.get("query")).trim();
            terms = tokenize(query);
        }

        if (terms.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 terms 或 query 参数");
            return envelop;
        }

        int limit = envelop.payload.get("limit") != null
            ? Integer.parseInt(envelop.payload.get("limit").toString())
            : 10;
        String mode = str(envelop.payload.get("mode"));
        if (!mode.equals("all")) mode = "any";

        List<String> pluginNames = new ArrayList<>(Plugins.INSTANCE.keySet());
        List<Map<String, Object>> results = termSearch(terms, pluginNames, limit, mode);

        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("terms", terms);
        result.put("results", results);
        result.put("total", results.size());

        envelop.payload = result;
        return envelop;
    }

    private List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return tokens;
        for (String t : TOKEN_SPLIT.split(query.trim())) {
            if (!t.isEmpty()) tokens.add(t.toLowerCase());
        }
        return tokens;
    }

    private List<Map<String, Object>> termSearch(
            List<String> terms, List<String> pluginNames, int limit, String mode) {

        if (terms == null || terms.isEmpty()) return new ArrayList<>();

        // 统一小写，去空白
        List<String> normalizedTerms = new ArrayList<>();
        for (String t : terms) {
            if (t == null) continue;
            String s = t.toLowerCase().trim();
            if (!s.isEmpty()) normalizedTerms.add(s);
        }
        if (normalizedTerms.isEmpty()) return new ArrayList<>();

        List<Map<String, Object>> results = new ArrayList<>();

        for (String name : pluginNames) {
            String desc = extractDesc(name);
            String nameLower = name.toLowerCase();
            String descLower = desc.toLowerCase();

            int score = 0;
            List<String> matchedTerms = new ArrayList<>();
            Map<String, List<String>> matchDetail = new LinkedHashMap<>();
            List<Map<String, String>> descMatched = new ArrayList<>();

            for (String term : normalizedTerms) {
                int termScore = 0;
                List<String> matchedIn = new ArrayList<>();

                if (nameLower.contains(term)) {
                    termScore += 10;
                    matchedIn.add("name");
                }
                if (descLower.contains(term)) {
                    termScore += 5;
                    matchedIn.add("desc");
                    // 提取描述里的命中片段
                    int idx = descLower.indexOf(term);
                    if (idx >= 0) {
                        int start = Math.max(0, idx - 20);
                        int end = Math.min(desc.length(), idx + term.length() + 20);
                        String snippet = desc.substring(start, end);
                        if (start > 0) snippet = "…" + snippet;
                        if (end < desc.length()) snippet = snippet + "…";
                        Map<String, String> dm = new LinkedHashMap<>();
                        dm.put("term", term);
                        dm.put("snippet", snippet);
                        descMatched.add(dm);
                    }
                }

                if (termScore > 0) {
                    score += termScore;
                    matchedTerms.add(term);
                    matchDetail.put(term, matchedIn);
                }
            }

            // 模式判断
            if (mode.equals("all")) {
                if (matchedTerms.size() < normalizedTerms.size()) continue;
            } else {
                if (matchedTerms.isEmpty()) continue;
            }

            // 加分
            if (!desc.isEmpty()) score += 2;
            score += matchedTerms.size() * 3;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("desc", desc);
            item.put("score", score);
            item.put("matched_terms", matchedTerms);
            item.put("match_detail", matchDetail);
            item.put("desc_matched", descMatched);
            results.add(item);
        }

        results.sort((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")));
        return results.size() > limit ? results.subList(0, limit) : results;
    }

    // ============================================================
    // 分组 + 描述
    // ============================================================

    private Map<String, Object> groupWithDesc(List<String> pluginNames) {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();

        for (String name : pluginNames) {
            if (name == null || name.isEmpty()) continue;
            String[] parts = name.split("/");

            if (parts[0].equals("applications") && parts.length >= 3) {
                String category = "applications";
                String project = parts[1];
                Map<String, Object> cat = groups.computeIfAbsent(category, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("projects", new LinkedHashMap<String, List<String>>());
                    return m;
                });
                @SuppressWarnings("unchecked")
                Map<String, List<String>> projects = (Map<String, List<String>>) cat.get("projects");
                projects.computeIfAbsent(project, k -> new ArrayList<>()).add(name);
            } else {
                String category = parts[0];
                Map<String, Object> cat = groups.computeIfAbsent(category, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("plugins", new ArrayList<String>());
                    return m;
                });
                @SuppressWarnings("unchecked")
                List<String> plugins = (List<String>) cat.get("plugins");
                plugins.add(name);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (String cat : new TreeSet<>(groups.keySet())) {
            Map<String, Object> info = groups.get(cat);
            Object projectsObj = info.get("projects");

            if (projectsObj instanceof Map<?, ?> projects && !projects.isEmpty()) {
                Map<String, Object> projBrief = new LinkedHashMap<>();
                int shown = 0;
                int totalCount = 0;
                for (Object pKey : new TreeSet<>(projects.keySet())) {
                    if (shown >= PER_PROJECT_LIMIT) break;
                    @SuppressWarnings("unchecked")
                    List<String> plugins = (List<String>) projects.get(pKey);
                    String desc = "";
                    for (String p : plugins) {
                        String d = extractDesc(p);
                        if (!d.isEmpty()) { desc = d; break; }
                    }
                    Map<String, Object> pb = new LinkedHashMap<>();
                    pb.put("count", plugins.size());
                    pb.put("desc", desc);
                    projBrief.put(pKey.toString(), pb);
                    totalCount += plugins.size();
                    shown++;
                }
                int totalProjects = projects.size();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("projects", projBrief);
                entry.put("count", totalCount);
                entry.put("total_projects", totalProjects);
                if (totalProjects > PER_PROJECT_LIMIT) {
                    entry.put("more_projects", totalProjects - PER_PROJECT_LIMIT);
                }
                result.put(cat, entry);
            } else {
                @SuppressWarnings("unchecked")
                List<String> plugins = (List<String>) info.get("plugins");
                plugins.sort(Comparator.naturalOrder());

                List<Map<String, Object>> pluginBrief = new ArrayList<>();
                int shown = 0;
                for (String p : plugins) {
                    if (shown >= PER_GROUP_LIMIT) break;
                    Map<String, Object> pb = new LinkedHashMap<>();
                    pb.put("name", p);
                    pb.put("desc", extractDesc(p));
                    pluginBrief.add(pb);
                    shown++;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("plugins", pluginBrief);
                entry.put("count", plugins.size());
                if (plugins.size() > PER_GROUP_LIMIT) {
                    entry.put("more", plugins.size() - PER_GROUP_LIMIT);
                }
                result.put(cat, entry);
            }
        }

        return result;
    }

    // ============================================================
    // 描述提取
    // ============================================================

    private String extractDesc(String pluginName) {
        if (DESC_CACHE.containsKey(pluginName)) {
            return DESC_CACHE.get(pluginName);
        }

        String desc = "";
        Path javaFile = Path.of(PLUGINS_ROOT, pluginName + ".java");

        if (Files.exists(javaFile)) {
            try {
                String content = Files.readString(javaFile);

                int helpPos = content.indexOf("function help");
                if (helpPos == -1) helpPos = content.indexOf("static Map<String, Object> help");
                int searchFrom = helpPos >= 0 ? helpPos : 0;
                String searchWindow = content.substring(searchFrom,
                    Math.min(content.length(), searchFrom + 1500));

                Matcher m = Pattern.compile(
                    "[\"']?description[\"']?\\s*[:=]\\s*[\"']([^\"']{1,100})[\"']"
                ).matcher(searchWindow);
                if (m.find()) {
                    String d = m.group(1).trim();
                    if (!d.contains("${")) {
                        desc = d;
                    }
                }

                if (desc.isEmpty()) {
                    Matcher m2 = Pattern.compile("/\\*\\*\\s*\\n?\\s*\\*?\\s*(.{1,200}?)\\n")
                        .matcher(content);
                    if (m2.find()) {
                        String d = m2.group(1).trim();
                        if (!d.isEmpty() && !d.startsWith("import ") && !d.contains("${")) {
                            int dashIdx = d.indexOf(" — ");
                            if (dashIdx >= 0) {
                                d = d.substring(dashIdx + 3).trim();
                            } else {
                                int asciiDash = d.indexOf(" - ");
                                if (asciiDash >= 0) {
                                    d = d.substring(asciiDash + 3).trim();
                                }
                            }
                            desc = d;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (desc.length() > 80) desc = desc.substring(0, 78) + "…";

        DESC_CACHE.put(pluginName, desc);
        return desc;
    }

    // ============================================================
    // helpers
    // ============================================================

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        return 0;
    }
}

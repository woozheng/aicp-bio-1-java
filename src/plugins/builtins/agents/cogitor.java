import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.core.Plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * builtins/agents/cogitor — 系统概览
 *
 * receiver 从"文件路径"推：builtins/agents/cogitor
 *
 * 职责：
 *   - 列出所有插件
 *   - 每个插件读源码提取描述（description 字段 / docstring / @AICP_ALIGN）
 *   - 分组：applications / builtins / os / 其他
 *   - 每个分类限 15 个，applications 限 20 个项目
 */
public class cogitor implements Plugin {

    private static final String PLUGINS_ROOT = "src/plugins";
    private static final int PER_GROUP_LIMIT = 15;
    private static final int PER_PROJECT_LIMIT = 20;

    /** 描述缓存 */
    private static final Map<String, String> DESC_CACHE = new HashMap<>();

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
            // 返回该分类的完整列表
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

        // 不带 category：分组 + 插件名 + 描述
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
    // 分组 + 描述
    // ============================================================

    private Map<String, Object> groupWithDesc(List<String> pluginNames) {
        // 先分组
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();

        for (String name : pluginNames) {
            if (name == null || name.isEmpty()) continue;
            String[] parts = name.split("/");

            if (parts[0].equals("applications") && parts.length >= 3) {
                // applications/xxx/yyy
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
                // builtins / os / www / 其他
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

        // 精简 + 加描述
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
                    projBrief.put(pKey.toString(), Map.of(
                        "count", plugins.size(),
                        "desc", desc
                    ));
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
                    pluginBrief.add(Map.of("name", p, "desc", extractDesc(p)));
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

                // 1. description 字段（只匹配 help() 之后的）
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

                // 2. 如果没提到，读顶部注释第一行
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

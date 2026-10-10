import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.core.Plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * builtins/tools/remove_tool — 删除整个项目
 *
 * receiver 从"文件路径"推：builtins/tools/remove_tool
 *
 * 删除：
 *   src/plugins/applications/{项目名}/     整个后端目录
 *   src/plugins/www/{项目名}.java           前端插件（如果有）
 *   www/{项目名}/                           前端 HTML（如果有）
 *   data/www/{项目名}/                      前端数据（如果有）
 *
 * 不删 data/{项目名}/（用户数据）
 */
public class remove_tool implements Plugin {

    private static final Path PROJECTS_DIR = Path.of("src", "plugins", "applications");
    private static final Path WWW_PLUGINS_DIR = Path.of("src", "plugins", "www");
    private static final Path WWW_HTML_DIR = Path.of("www");
    private static final Path DATA_WWW_DIR = Path.of("data", "www");

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "remove";

        if (!"remove".equals(action)) {
            envelop.payload = Map.of("ok", false, "error", "Unknown action: " + action);
            return envelop;
        }

        String target = str(envelop.payload.get("target"));
        if (target.isEmpty()) {
            target = str(envelop.payload.get("name"));
        }
        if (target.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 target 参数");
            return envelop;
        }

        // 安全检查：禁止路径穿越
        if (target.contains("..")) {
            envelop.payload = Map.of("ok", false, "error", "target 含非法字符");
            return envelop;
        }

        // 1. 解析项目名
        String projectName = findProject(target);
        if (projectName == null || projectName.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "未找到项目: " + target);
            return envelop;
        }

        if (projectName.contains("/") || projectName.contains("\\")) {
            envelop.payload = Map.of("ok", false, "error", "项目名含非法字符");
            return envelop;
        }

        // 2. 收集要删的路径
        List<Path> candidates = new ArrayList<>();
        candidates.add(PROJECTS_DIR.resolve(projectName));                  // 后端目录
        candidates.add(WWW_PLUGINS_DIR.resolve(projectName + ".java"));     // 前端插件
        candidates.add(WWW_HTML_DIR.resolve(projectName));                  // 前端 HTML
        candidates.add(DATA_WWW_DIR.resolve(projectName));                  // 前端数据

        List<Path> existing = new ArrayList<>();
        for (Path p : candidates) {
            if (Files.exists(p)) existing.add(p);
        }

        if (existing.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "项目不存在: " + projectName);
            return envelop;
        }

        // 3. 删除
        List<String> deleted = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Path p : existing) {
            try {
                deleteRecursive(p);
                deleted.add(p.toString().replace("\\", "/"));
            } catch (IOException e) {
                errors.add(p.toString().replace("\\", "/") + ": " + e.getMessage());
            }
        }

        // 4. 等 FileWatcher 卸载
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // 5. 验证：相关 receiver 是否已从 Plugins.INSTANCE 移除
        List<String> stillLoaded = new ArrayList<>();
        String prefix = "applications/" + projectName + "/";
        for (String key : Plugins.INSTANCE.keySet()) {
            if (key.startsWith(prefix) || key.equals("www/" + projectName)) {
                stillLoaded.add(key);
            }
        }
        boolean unloaded = stillLoaded.isEmpty();

        // 6. 返回
        if (!errors.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("project_name", projectName);
            data.put("removed", deleted);
            data.put("errors", errors);
            data.put("unloaded", unloaded);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put("error", "部分删除失败: " + String.join("; ", errors));
            result.put("data", data);
            envelop.payload = result;
            return envelop;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("project_name", projectName);
        data.put("removed", deleted);
        data.put("unloaded", unloaded);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("data", data);
        result.put("message", unloaded
            ? "🗑️ 已删除项目 `" + projectName + "`\n共删除 " + deleted.size() + " 个文件/目录"
            : "⚠️ 文件已删除，但插件未卸载：" + projectName + "（可能需要重启）");
        envelop.payload = result;
        return envelop;
    }

    // ============================================================
    // 项目名解析
    // ============================================================

    private String findProject(String target) {
        String t = target.trim();

        // 1. 完整路径 applications/xxx/xxx_api → 提取项目名
        if (t.contains("applications/")) {
            String after = t.substring(t.lastIndexOf("applications/") + "applications/".length());
            int slash = after.indexOf('/');
            String candidate = slash >= 0 ? after.substring(0, slash) : after;
            if (!candidate.isEmpty() && Files.exists(PROJECTS_DIR.resolve(candidate))) {
                return candidate;
            }
        }

        // 2. 直接检查文件系统
        if (Files.exists(PROJECTS_DIR.resolve(t))) {
            return t;
        }

        // 3. 去掉 _api / .java / .ts 后缀再检查
        String clean = t
            .replaceAll("_api$", "")
            .replaceAll("\\.java$", "")
            .replaceAll("\\.ts$", "");
        if (!clean.equals(t) && Files.exists(PROJECTS_DIR.resolve(clean))) {
            return clean;
        }

        // 4. 列 applications/ 下的项目，做模糊匹配
        List<String> projects = listProjects();
        if (projects.contains(t)) return t;

        String tLower = t.toLowerCase();
        for (String p : projects) {
            if (p.toLowerCase().equals(tLower)) return p;
        }
        for (String p : projects) {
            if (p.toLowerCase().contains(tLower) || tLower.contains(p.toLowerCase())) return p;
        }

        if (!clean.equals(t)) {
            String cLower = clean.toLowerCase();
            for (String p : projects) {
                if (p.toLowerCase().contains(cLower) || cLower.contains(p.toLowerCase())) return p;
            }
        }

        return null;
    }

    private List<String> listProjects() {
        List<String> result = new ArrayList<>();
        if (!Files.exists(PROJECTS_DIR)) return result;

        try (Stream<Path> stream = Files.list(PROJECTS_DIR)) {
            stream.filter(Files::isDirectory)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(p -> result.add(p.getFileName().toString()));
        } catch (IOException ignored) {}

        return result;
    }

    // ============================================================
    // 递归删除
    // ============================================================

    private void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;

        if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.walk(path)) {
                List<Path> all = new ArrayList<>();
                stream.sorted(Comparator.reverseOrder()).forEach(all::add);
                for (Path p : all) {
                    Files.deleteIfExists(p);
                }
            }
        } else {
            Files.deleteIfExists(path);
        }
    }

    // ============================================================
    // helpers
    // ============================================================

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }
}

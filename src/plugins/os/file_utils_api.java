import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * os/file_utils_api — 文件读写工具
 *
 * receiver 从"文件路径"推：os/file_utils_api
 *
 * 支持：
 *   read_file       读文件（≤100KB 全读，>100KB 读尾部）
 *   read_file_lines 按行读
 *   write_file      写文件（全量覆盖）
 *   append_file     追加
 *   edit_file       局部替换
 *   list_dir        列目录
 *   delete_file     删文件
 *   exists          检查存在
 *   file_stat       文件属性
 *   mkdir           建目录
 */
public class file_utils_api implements Plugin {

    private static final long MAX_INLINE_SIZE = 100 * 1024;   // 100KB
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "read_file";

        try {
            switch (action) {
                case "read_file":
                    return readFile(envelop);
                case "read_file_lines":
                    return readFileLines(envelop);
                case "write_file":
                    return writeFile(envelop);
                case "append_file":
                    return appendFile(envelop);
                case "edit_file":
                    return editFile(envelop);
                case "list_dir":
                    return listDir(envelop);
                case "delete_file":
                    return deleteFile(envelop);
                case "exists":
                    return exists(envelop);
                case "file_stat":
                    return fileStat(envelop);
                case "mkdir":
                    return mkdir(envelop);
                default:
                    envelop.payload = Map.of("ok", false, "error", "Unknown action: " + action);
                    return envelop;
            }
        } catch (Exception e) {
            envelop.payload = Map.of("ok", false, "error", e.getMessage());
            return envelop;
        }
    }

    // ============================================================
    // read_file
    // ============================================================

    private Envelop readFile(Envelop envelop) throws IOException {
        String rawPath = str(envelop.payload.get("path"));
        if (rawPath.isEmpty()) return err(envelop, "path required");

        Path p = resolvePath(rawPath);
        if (!Files.exists(p)) return err(envelop, "File not found: " + rawPath);
        if (!Files.isRegularFile(p)) return err(envelop, "Not a file: " + rawPath);

        long size = Files.size(p);
        Map<String, Object> data = new HashMap<>();
        data.put("path", displayPath(p));
        data.put("size", size);
        data.put("size_kb", round(size / 1024.0));

        if (size <= MAX_INLINE_SIZE) {
            String content = Files.readString(p, StandardCharsets.UTF_8);
            data.put("content", content);
            data.put("truncated", false);
        } else {
            // 读尾部 100KB
            String content = Files.readString(p, StandardCharsets.UTF_8);
            String tail = content.length() > MAX_INLINE_SIZE
                ? content.substring(content.length() - (int) MAX_INLINE_SIZE)
                : content;
            data.put("preview", tail);
            data.put("truncated", true);
            data.put("hint", "文件超过 100KB，只返回尾部预览。用 read_file_lines 读指定行。");
        }

        envelop.payload = Map.of("ok", true, "data", data);
        return envelop;
    }

    // ============================================================
    // read_file_lines
    // ============================================================

    private Envelop readFileLines(Envelop envelop) throws IOException {
        String rawPath = str(envelop.payload.get("path"));
        if (rawPath.isEmpty()) return err(envelop, "path required");

        Path p = resolvePath(rawPath);
        if (!Files.exists(p)) return err(envelop, "File not found: " + rawPath);
        if (!Files.isRegularFile(p)) return err(envelop, "Not a file: " + rawPath);

        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        int total = lines.size();

        int start = num(envelop.payload.get("start_line"), 1);
        int end = num(envelop.payload.get("end_line"), total);
        boolean withLineNum = bool(envelop.payload.get("with_line_num"), false);

        if (start < 1) start = 1;
        if (end > total) end = total;
        if (start > end) return err(envelop, "start > end");

        List<String> selected = lines.subList(start - 1, end);

        Map<String, Object> data = new HashMap<>();
        data.put("path", displayPath(p));
        data.put("total_lines", total);
        data.put("start", start);
        data.put("end", end);
        data.put("returned", selected.size());
        data.put("with_line_num", withLineNum);

        if (withLineNum) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < selected.size(); i++) {
                result.add(Map.of("line_num", start + i, "content", selected.get(i)));
            }
            data.put("lines", result);
        } else {
            data.put("lines", selected);
        }

        envelop.payload = Map.of("ok", true, "data", data);
        return envelop;
    }

    // ============================================================
    // write_file
    // ============================================================

    private Envelop writeFile(Envelop envelop) throws IOException {
        String rawPath = str(envelop.payload.get("path"));
        String content = str(envelop.payload.get("content"));
        if (rawPath.isEmpty()) return err(envelop, "path required");

        Path p = resolvePath(rawPath);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);

        envelop.payload = Map.of("ok", true, "data", Map.of(
            "path", displayPath(p),
            "size", Files.size(p)
        ));
        return envelop;
    }

    // ============================================================
    // append_file
    // ============================================================

    private Envelop appendFile(Envelop envelop) throws IOException {
        String rawPath = str(envelop.payload.get("path"));
        String content = str(envelop.payload.get("content"));
        if (rawPath.isEmpty()) return err(envelop, "path required");

        Path p = resolvePath(rawPath);
        if (p.getParent() != null) Files.createDirectories(p.getParent());

        if (Files.exists(p)) {
            Files.writeString(p, content, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        } else {
            Files.writeString(p, content, StandardCharsets.UTF_8);
        }

        envelop.payload = Map.of("ok", true, "data", Map.of("path", displayPath(p)));
        return envelop;
    }

    // ============================================================
    // edit_file
    // ============================================================

    private Envelop editFile(Envelop envelop) throws IOException {
        String rawPath = str(envelop.payload.get("path"));
        String find = str(envelop.payload.get("find"));
        String replace = str(envelop.payload.get("replace"));
        if (rawPath.isEmpty()) return err(envelop, "path required");
        if (find.isEmpty()) return err(envelop, "find required");

        Path p = resolvePath(rawPath);
        if (!Files.exists(p)) return err(envelop, "File not found: " + rawPath);

        String content = Files.readString(p, StandardCharsets.UTF_8);
        int idx = content.indexOf(find);
        if (idx == -1) return err(envelop, "find not matched");
        if (content.indexOf(find, idx + 1) != -1) return err(envelop, "find not unique");

        String newContent = content.substring(0, idx) + replace + content.substring(idx + find.length());
        Files.writeString(p, newContent, StandardCharsets.UTF_8);

        envelop.payload = Map.of("ok", true, "data", Map.of(
            "path", displayPath(p),
            "old_size", content.length(),
            "new_size", newContent.length()
        ));
        return envelop;
    }

    // ============================================================
    // list_dir
    // ============================================================

    private Envelop listDir(Envelop envelop) throws IOException {
        String rawPath = str(envelop.payload.getOrDefault("path", "."));
        Path p = resolvePath(rawPath);
        if (!Files.exists(p)) return err(envelop, "Dir not found: " + rawPath);
        if (!Files.isDirectory(p)) return err(envelop, "Not a dir: " + rawPath);

        List<Map<String, Object>> items = new ArrayList<>();
        try (Stream<Path> stream = Files.list(p)) {
            stream.sorted().forEach(entry -> {
                try {
                    boolean isDir = Files.isDirectory(entry);
                    Map<String, Object> item = new HashMap<>();
                    item.put("name", entry.getFileName().toString());
                    item.put("type", isDir ? "directory" : "file");
                    item.put("size", isDir ? 0 : Files.size(entry));
                    items.add(item);
                } catch (IOException ignored) {}
            });
        }

        envelop.payload = Map.of("ok", true, "data", Map.of(
            "path", displayPath(p),
            "items", items
        ));
        return envelop;
    }

    // ============================================================
    // delete_file
    // ============================================================

    private Envelop deleteFile(Envelop envelop) throws IOException {
        String rawPath = str(envelop.payload.get("path"));
        if (rawPath.isEmpty()) return err(envelop, "path required");

        Path p = resolvePath(rawPath);
        if (!Files.exists(p)) return err(envelop, "Not found: " + rawPath);

        if (Files.isDirectory(p)) {
            try (Stream<Path> s = Files.list(p)) {
                if (s.findAny().isPresent()) return err(envelop, "Dir not empty: " + rawPath);
            }
            Files.delete(p);
        } else {
            Files.delete(p);
        }

        envelop.payload = Map.of("ok", true, "data", Map.of("deleted", true));
        return envelop;
    }

    // ============================================================
    // exists / file_stat / mkdir
    // ============================================================

    private Envelop exists(Envelop envelop) {
        String rawPath = str(envelop.payload.get("path"));
        Path p = resolvePath(rawPath);
        envelop.payload = Map.of("ok", true, "data", Map.of("exists", Files.exists(p)));
        return envelop;
    }

    private Envelop fileStat(Envelop envelop) throws IOException {
        String rawPath = str(envelop.payload.get("path"));
        Path p = resolvePath(rawPath);
        if (!Files.exists(p)) return err(envelop, "Not found: " + rawPath);

        Map<String, Object> stat = new HashMap<>();
        stat.put("name", p.getFileName().toString());
        stat.put("type", Files.isDirectory(p) ? "directory" : "file");
        stat.put("size", Files.isDirectory(p) ? 0 : Files.size(p));
        stat.put("modified_at", Files.getLastModifiedTime(p).toString());

        envelop.payload = Map.of("ok", true, "data", Map.of("stat", stat));
        return envelop;
    }

    private Envelop mkdir(Envelop envelop) throws IOException {
        String rawPath = str(envelop.payload.get("path"));
        Path p = resolvePath(rawPath);
        Files.createDirectories(p);
        envelop.payload = Map.of("ok", true, "data", Map.of("path", displayPath(p)));
        return envelop;
    }

    // ============================================================
    // helpers
    // ============================================================

    private Envelop err(Envelop envelop, String msg) {
        envelop.payload = Map.of("ok", false, "error", msg);
        return envelop;
    }

    private static Path resolvePath(String rawPath) {
        String normalized = rawPath.replace("\\", "/");
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        Path p = Path.of(normalized);
        if (!p.isAbsolute()) p = PROJECT_ROOT.resolve(p);
        return p.normalize();
    }

    private static String displayPath(Path absPath) {
        try {
            return PROJECT_ROOT.relativize(absPath).toString().replace("\\", "/");
        } catch (Exception e) {
            return absPath.toString();
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private static int num(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try { return Integer.parseInt(o.toString()); } catch (Exception ignored) {}
        }
        return def;
    }

    private static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        if (o != null) return Boolean.parseBoolean(o.toString());
        return def;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}

package com.aicp.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * runtime/atomic_write — 统一原子写
 *
 * 所有落盘的地方都调这个，避免两套逻辑。
 *
 * 功能：
 * - {project} 占位符替换（路径 + 内容）
 * - 路径校正（plugins/ → src/plugins/ 等）
 * - app_id 应用（www/ → www/{appId}/）
 * - 安全检查（防 .. 逃逸和绝对路径）
 * - 原子写（.xxx.tmp → rename）
 */
public class AtomicWrite {

    private static final String PROJECT_ROOT = System.getProperty("user.dir");

    // ============================================================
    // 路径处理
    // ============================================================

    private static String normalizePath(String filepath) {
        filepath = filepath.replaceAll("^/+", "");
        filepath = filepath.replaceAll("^\\./", "");
        filepath = filepath.replace("\\", "/");

        if (filepath.startsWith("src/plugins/") || filepath.startsWith("www/")) {
            return filepath;
        }
        if (filepath.startsWith("plugins/")) {
            return "src/" + filepath;
        }
        return "src/plugins/applications/" + filepath;
    }

    private static String applyAppId(String filepath, String appId) {
        if (appId == null || appId.isEmpty()) return filepath;

        // 只处理 www/ 开头（没有项目层的）
        if (filepath.startsWith("www/") && !filepath.startsWith("www/" + appId + "/")) {
            String rest = filepath.replaceAll("^www/", "");
            return "www/" + appId + "/" + rest;
        }
        return filepath;
    }

    private static boolean isSafePath(String filepath) {
        if (filepath.contains("..")) return false;
        if (filepath.startsWith("/")) return false;
        if (filepath.matches("^[A-Za-z]:.*")) return false;
        return true;
    }

    // ============================================================
    // 原子写
    // ============================================================

    public static String write(String path, String content) throws IOException {
        return write(path, content, "");
    }

    public static String write(String path, String content, String appId) throws IOException {
        // 1. {project} 替换（路径 + 内容）
        if (appId != null && !appId.isEmpty()) {
            path = path.replace("{project}", appId);
            content = content.replace("{project}", appId);
        }

        // 2. 路径校正
        path = normalizePath(path);

        // 3. app_id 应用
        path = applyAppId(path, appId);

        // 4. 安全检查
        if (!isSafePath(path)) {
            throw new IOException("不安全的路径: " + path);
        }

        // 5. 解析绝对路径，确认在项目内
        Path absPath = Path.of(PROJECT_ROOT).resolve(path).normalize();
        Path relPath = Path.of(PROJECT_ROOT).relativize(absPath);
        if (relPath.toString().startsWith("..")) {
            throw new IOException("路径逃逸: " + path);
        }

        // 6. 原子写
        Files.createDirectories(absPath.getParent());

        Path dir = absPath.getParent();
        String name = absPath.getFileName().toString();
        Path tmpPath = dir.resolve("." + name + ".tmp");

        Files.writeString(tmpPath, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmpPath, absPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 某些文件系统不支持 ATOMIC_MOVE，降级
            Files.move(tmpPath, absPath, StandardCopyOption.REPLACE_EXISTING);
        }

        return path;
    }
}

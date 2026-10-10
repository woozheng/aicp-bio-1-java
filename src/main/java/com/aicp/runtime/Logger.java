package com.aicp.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * logger — 简单日志（写到 data/logs/gateway.log）
 *
 * 与 TS 版 runtime/logger.ts 对应（简化版）。
 */
public class Logger {

    private static final Path LOG_DIR = Path.of("data", "logs");
    private static final Path LOG_FILE = LOG_DIR.resolve("gateway.log");

    private static boolean installed = false;

    public static void install() {
        if (installed) return;
        installed = true;

        // Java 没有"劫持 System.out"的优雅方式
        // 这里只提供一个静态 log 方法，不劫持
        try {
            Files.createDirectories(LOG_DIR);
        } catch (IOException ignored) {}
    }

    public static void info(String msg) {
        appendLog("INFO", msg);
    }

    public static void warn(String msg) {
        appendLog("WARN", msg);
    }

    public static void error(String msg) {
        appendLog("ERROR", msg);
    }

    private static void appendLog(String level, String msg) {
        String line = "[" + Instant.now() + "] [" + level + "] " + msg + "\n";
        try {
            Files.writeString(LOG_FILE, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 静默失败，不阻塞主流程
        }
    }
}

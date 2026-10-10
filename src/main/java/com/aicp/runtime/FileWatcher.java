package com.aicp.runtime;

import com.aicp.core.Plugins;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * FileWatcher — 监听 plugins/ 目录，自动编译 + 加载新插件
 *
 * 与 TS 版 runtime/hot_reload_watcher.ts 对应。
 *
 * 支持：
 *   - 新建 .java → 编译 + 加载
 *   - 修改 .java → 编译 + 加载
 *   - 删除 .java → 卸载
 *
 * 用 JDK 自带的 WatchService。
 *
 * ★ 关键：
 *   1. 启动时 initCaches() 记下所有现有 .java 的 mtime，避免"重复 reload"
 *   2. 每 2 秒 doPeriodicScan() 补漏（WatchService 的 ENTRY_CREATE 事件可能漏）
 */
public class FileWatcher {

    private final Path pluginsDir;
    private final PluginLoader loader;
    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean running = false;

    /** 已处理过的文件 mtime，避免重复处理 */
    private final Map<Path, Long> mtimeCache = new HashMap<>();

    /** 失败重试计数 */
    private final Map<Path, Integer> retryCount = new HashMap<>();
    private static final int MAX_RETRY = 5;

    public FileWatcher(String pluginsDir, PluginLoader loader) {
        this.pluginsDir = Path.of(pluginsDir != null ? pluginsDir : "src/plugins");
        this.loader = loader;
    }

    // ============================================================
    // 启动 / 停止
    // ============================================================

    public void start() throws IOException {
        if (running) return;

        watchService = FileSystems.getDefault().newWatchService();
        registerAll(pluginsDir);
        initCaches();   // ★ 启动时记下所有现有文件的 mtime

        running = true;
        watchThread = new Thread(this::loop, "FileWatcher");
        watchThread.setDaemon(true);
        watchThread.start();

        System.out.println("[FileWatcher] Started, watching " + pluginsDir);
    }

    public void stop() {
        running = false;
        if (watchThread != null) watchThread.interrupt();
        try {
            if (watchService != null) watchService.close();
        } catch (IOException ignored) {}
        System.out.println("[FileWatcher] Stopped");
    }

    // ============================================================
    // ★ 初始化缓存：记下所有现有 .java 的 mtime
    //    这样 doPeriodicScan 不会把"现有文件" 当"新文件"
    // ============================================================

    private void initCaches() {
        try {
            if (!Files.exists(pluginsDir)) return;
            Files.walk(pluginsDir)
                .filter(Files::isRegularFile)
                .filter(this::isJavaFile)
                .forEach(p -> {
                    try {
                        long mtime = Files.getLastModifiedTime(p).toMillis();
                        mtimeCache.put(p, mtime);
                    } catch (IOException ignored) {}
                });
            System.out.println("[FileWatcher] Initialized cache with " + mtimeCache.size() + " files");
        } catch (Exception e) {
            System.err.println("[FileWatcher] initCaches error: " + e.getMessage());
        }
    }

    // ============================================================
    // 主循环
    // ============================================================

    private void loop() {
        while (running) {
            try {
                // ★ 关键：poll(2s)，没事件就做全量扫描
                WatchKey key = watchService.poll(2, TimeUnit.SECONDS);

                if (key != null) {
                    Path dir = (Path) key.watchable();

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();

                        if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                        Path name = (Path) event.context();
                        Path fullPath = dir.resolve(name);

                        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                            // 新建目录 → 注册到 watcher
                            if (Files.isDirectory(fullPath)) {
                                registerAll(fullPath);
                            }
                            // 新建 .java → 编译 + 加载
                            else if (isJavaFile(fullPath)) {
                                handleNewOrModified(fullPath);
                            }
                        } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                            if (isJavaFile(fullPath)) {
                                handleNewOrModified(fullPath);
                            }
                        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                            if (isJavaFile(fullPath)) {
                                handleRemoved(fullPath);
                            }
                        }
                    }

                    key.reset();
                } else {
                    // ★ 没有事件 → 做全量扫描，补"漏掉的事件"
                    doPeriodicScan();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (Exception e) {
                System.err.println("[FileWatcher] Error: " + e.getMessage());
            }
        }
    }

    // ============================================================
    // ★ 周期性全量扫描（补漏）
    //    - 检测"新文件"（mtimeCache 里没有）
    //    - 检测"被修改的文件"（mtime 变大）
    //    - 检测"被删除的文件"（mtimeCache 里有但实际没有）
    // ============================================================

    private void doPeriodicScan() {
        try {
            if (!Files.exists(pluginsDir)) return;

            // 收集当前所有 .java 文件
            Map<Path, Long> currentFiles = new HashMap<>();
            Files.walk(pluginsDir)
                .filter(Files::isRegularFile)
                .filter(this::isJavaFile)
                .forEach(p -> {
                    try {
                        long mtime = Files.getLastModifiedTime(p).toMillis();
                        currentFiles.put(p, mtime);
                    } catch (IOException ignored) {}
                });

            // 检测"新文件" / "被修改的文件"
            for (Map.Entry<Path, Long> entry : currentFiles.entrySet()) {
                Path file = entry.getKey();
                Long mtime = entry.getValue();
                Long cached = mtimeCache.get(file);
                if (cached == null || mtime > cached) {
                    handleNewOrModified(file);
                }
            }

            // 检测"被删除的文件"
            // 注意：遍历 mtimeCache 的"副本"，避免并发修改
            for (Path cachedPath : new HashMap<>(mtimeCache).keySet()) {
                if (!currentFiles.containsKey(cachedPath)) {
                    handleRemoved(cachedPath);
                }
            }
        } catch (Exception e) {
            System.err.println("[FileWatcher] Periodic scan error: " + e.getMessage());
        }
    }

    // ============================================================
    // 处理新建 / 修改
    // ============================================================

    private void handleNewOrModified(Path javaFile) {
        try {
            Thread.sleep(500);

            // 检查文件大小稳定（防"半截文件"）
            long size1;
            try {
                size1 = Files.size(javaFile);
            } catch (IOException e) {
                return;   // 文件不存在了
            }
            Thread.sleep(300);
            long size2;
            try {
                size2 = Files.size(javaFile);
            } catch (IOException e) {
                return;
            }
            if (size1 != size2) return;   // 还在写

            long mtime = Files.getLastModifiedTime(javaFile).toMillis();
            Long cached = mtimeCache.get(javaFile);
            if (cached != null && cached == mtime) return;

            String receiver = calcReceiver(javaFile);
            boolean ok = loader.loadDynamic(javaFile, receiver);

            if (ok) {
                mtimeCache.put(javaFile, mtime);
                retryCount.remove(javaFile);
                System.out.println("[FileWatcher] Reloaded: " + receiver);
            } else {
                int n = retryCount.getOrDefault(javaFile, 0) + 1;
                retryCount.put(javaFile, n);
                if (n >= MAX_RETRY) {
                    mtimeCache.put(javaFile, mtime);   // 放弃
                    retryCount.remove(javaFile);
                    System.err.println("[FileWatcher] Give up after " + n + " retries: " + receiver);
                } else {
                    System.err.println("[FileWatcher] Retry " + n + "/" + MAX_RETRY + ": " + receiver);
                    // 不缓存 mtime，下次会重试
                }
            }
        } catch (Exception e) {
            System.err.println("[FileWatcher] Handle failed: " + e.getMessage());
        }
    }

    // ============================================================
    // 处理删除
    // ============================================================

    private void handleRemoved(Path javaFile) {
        try {
            String receiver = calcReceiver(javaFile);

            // 从 Plugins.INSTANCE 移除
            if (Plugins.INSTANCE.remove(receiver) != null) {
                System.out.println("[FileWatcher] Removed: " + receiver);
            }

            // 从 PluginLoader 的 dynamicLoaded 移除
            loader.getDynamicLoaded().remove(receiver);

            // 从 mtimeCache 移除
            mtimeCache.remove(javaFile);
            retryCount.remove(javaFile);

            // 删除对应的 .class（可选）
            Path classFile = javaFile.resolveSibling(
                javaFile.getFileName().toString().replace(".java", ".class")
            );
            if (Files.exists(classFile)) {
                try {
                    Files.delete(classFile);
                    System.out.println("[FileWatcher] Deleted class: " + classFile.getFileName());
                } catch (IOException e) {
                    System.err.println("[FileWatcher] Delete class failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[FileWatcher] Handle removed failed: " + e.getMessage());
        }
    }

    // ============================================================
    // 注册目录到 watcher
    // ============================================================

    private void registerAll(Path root) throws IOException {
        if (!Files.exists(root)) return;

        Files.walk(root)
            .filter(Files::isDirectory)
            .forEach(dir -> {
                try {
                    dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                } catch (IOException e) {
                    System.err.println("[FileWatcher] Register failed: " + dir + " " + e.getMessage());
                }
            });
    }

    // ============================================================
    // helpers
    // ============================================================

    private boolean isJavaFile(Path path) {
        String name = path.getFileName().toString();
        if (!name.endsWith(".java")) return false;
        if (name.startsWith(".")) return false;
        if (name.endsWith(".d.java")) return false;
        if (name.contains(".test.")) return false;
        if (name.contains(".spec.")) return false;
        return true;
    }

    private String calcReceiver(Path javaFile) {
        Path rel = pluginsDir.relativize(javaFile);
        String s = rel.toString().replace("\\", "/");
        return s.replaceAll("\\.java$", "");
    }
}

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
 * ★ 不死保证：
 *   1. loop() 用 catch (Throwable)，任何 Error/Exception 都不会杀线程
 *   2. handleNewOrModified() 用 catch (Throwable)
 *   3. handleRemoved() 用 catch (Throwable)
 *   4. doPeriodicScan() 用 catch (Throwable)
 *   5. scanDirNow() 用 catch (Throwable)
 *   6. WatchService 挂了，自动重建
 *
 * ★ 关键机制：
 *   1. 启动时 initCaches() 记下所有现有 .java 的 mtime
 *   2. 每 2 秒 doPeriodicScan() 补漏
 *   3. 新建目录时，registerAll 后立即扫描目录内已有的 .java
 */
public class FileWatcher {

    private final Path pluginsDir;
    private final PluginLoader loader;
    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean running = false;

    /** 已处理过的文件 mtime */
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
        initCaches();

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
        } catch (Throwable ignored) {}
        System.out.println("[FileWatcher] Stopped");
    }

    // ============================================================
    // initCaches
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
                    } catch (Throwable ignored) {}
                });
            System.out.println("[FileWatcher] Initialized cache with " + mtimeCache.size() + " files");
        } catch (Throwable t) {
            System.err.println("[FileWatcher] initCaches error: " + t);
        }
    }

    // ============================================================
    // 主循环 —— 不死
    // ============================================================

    private void loop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(2, TimeUnit.SECONDS);

                if (key != null) {
                    Path dir = (Path) key.watchable();

                    for (WatchEvent<?> event : key.pollEvents()) {
                        try {
                            WatchEvent.Kind<?> kind = event.kind();
                            if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                            Path name = (Path) event.context();
                            Path fullPath = dir.resolve(name);

                            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                                if (Files.isDirectory(fullPath)) {
                                    registerAll(fullPath);
                                    scanDirNow(fullPath);
                                } else if (isJavaFile(fullPath)) {
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
                        } catch (Throwable t) {
                            System.err.println("[FileWatcher] Event handle error: " + t);
                            t.printStackTrace();
                        }
                    }

                    try {
                        key.reset();
                    } catch (Throwable ignored) {}

                } else {
                    doPeriodicScan();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                // watchService 被关了，尝试重建
                System.err.println("[FileWatcher] WatchService closed, recreating...");
                if (!recreateWatchService()) {
                    break;
                }
            } catch (Throwable t) {
                // ★ 任何异常都不杀线程
                System.err.println("[FileWatcher] Loop error: " + t);
                t.printStackTrace();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        System.out.println("[FileWatcher] Loop exited");
    }

    private boolean recreateWatchService() {
        try {
            if (watchService != null) {
                try { watchService.close(); } catch (Throwable ignored) {}
            }
            watchService = FileSystems.getDefault().newWatchService();
            registerAll(pluginsDir);
            System.out.println("[FileWatcher] WatchService recreated");
            return true;
        } catch (Throwable t) {
            System.err.println("[FileWatcher] Recreate failed: " + t);
            return false;
        }
    }

    // ============================================================
    // scanDirNow —— 新目录里已有的 .java
    // ============================================================

    private void scanDirNow(Path dir) {
        try {
            if (!Files.exists(dir)) return;
            Files.walk(dir)
                .filter(Files::isRegularFile)
                .filter(this::isJavaFile)
                .forEach(this::handleNewOrModified);
        } catch (Throwable t) {
            System.err.println("[FileWatcher] scanDirNow error: " + t);
        }
    }

    // ============================================================
    // doPeriodicScan —— 全量补漏
    // ============================================================

    private void doPeriodicScan() {
        try {
            if (!Files.exists(pluginsDir)) return;

            Map<Path, Long> currentFiles = new HashMap<>();
            Files.walk(pluginsDir)
                .filter(Files::isRegularFile)
                .filter(this::isJavaFile)
                .forEach(p -> {
                    try {
                        long mtime = Files.getLastModifiedTime(p).toMillis();
                        currentFiles.put(p, mtime);
                    } catch (Throwable ignored) {}
                });

            for (Map.Entry<Path, Long> entry : currentFiles.entrySet()) {
                Path file = entry.getKey();
                Long mtime = entry.getValue();
                Long cached = mtimeCache.get(file);
                if (cached == null || mtime > cached) {
                    handleNewOrModified(file);
                }
            }

            for (Path cachedPath : new HashMap<>(mtimeCache).keySet()) {
                if (!currentFiles.containsKey(cachedPath)) {
                    handleRemoved(cachedPath);
                }
            }
        } catch (Throwable t) {
            System.err.println("[FileWatcher] Periodic scan error: " + t);
        }
    }

    // ============================================================
    // handleNewOrModified —— 不死
    // ============================================================

    private void handleNewOrModified(Path javaFile) {
        try {
            Thread.sleep(300);

            long mtime1;
            try {
                mtime1 = Files.getLastModifiedTime(javaFile).toMillis();
            } catch (Throwable e) {
                return;
            }
            Thread.sleep(200);
            long mtime2;
            try {
                mtime2 = Files.getLastModifiedTime(javaFile).toMillis();
            } catch (Throwable e) {
                return;
            }
            if (mtime1 != mtime2) return;

            long mtime = mtime2;
            Long cached = mtimeCache.get(javaFile);
            if (cached != null && cached == mtime) return;

            String receiver = calcReceiver(javaFile);
            boolean ok = false;
            try {
                ok = loader.loadDynamic(javaFile, receiver);
            } catch (Throwable t) {
                // ★ 关键：NoClassDefFoundError 等 Error 不会杀线程
                System.err.println("[FileWatcher] loadDynamic threw: " + t);
                t.printStackTrace();
                ok = false;
            }

            if (ok) {
                mtimeCache.put(javaFile, mtime);
                retryCount.remove(javaFile);
                System.out.println("[FileWatcher] Reloaded: " + receiver);
            } else {
                int n = retryCount.getOrDefault(javaFile, 0) + 1;
                retryCount.put(javaFile, n);
                if (n >= MAX_RETRY) {
                    mtimeCache.put(javaFile, mtime);
                    retryCount.remove(javaFile);
                    System.err.println("[FileWatcher] Give up after " + n + " retries: " + receiver);
                } else {
                    System.err.println("[FileWatcher] Retry " + n + "/" + MAX_RETRY + ": " + receiver);
                }
            }
        } catch (Throwable t) {
            // ★ 兜底：任何 Throwable 都不杀线程
            System.err.println("[FileWatcher] Handle failed: " + t);
            t.printStackTrace();
        }
    }

    // ============================================================
    // handleRemoved —— 不死
    // ============================================================

    private void handleRemoved(Path javaFile) {
        try {
            String receiver = calcReceiver(javaFile);

            try {
                if (Plugins.INSTANCE.remove(receiver) != null) {
                    System.out.println("[FileWatcher] Removed: " + receiver);
                }
            } catch (Throwable ignored) {}

            try {
                loader.getDynamicLoaded().remove(receiver);
            } catch (Throwable ignored) {}

            mtimeCache.remove(javaFile);
            retryCount.remove(javaFile);

            Path classFile = javaFile.resolveSibling(
                javaFile.getFileName().toString().replace(".java", ".class")
            );
            if (Files.exists(classFile)) {
                try {
                    Files.delete(classFile);
                    System.out.println("[FileWatcher] Deleted class: " + classFile.getFileName());
                } catch (Throwable e) {
                    System.err.println("[FileWatcher] Delete class failed: " + e);
                }
            }
        } catch (Throwable t) {
            System.err.println("[FileWatcher] Handle removed failed: " + t);
        }
    }

    // ============================================================
    // registerAll
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
                } catch (Throwable e) {
                    System.err.println("[FileWatcher] Register failed: " + dir + " " + e);
                }
            });
    }

    // ============================================================
    // helpers
    // ============================================================

    private boolean isJavaFile(Path path) {
        try {
            String name = path.getFileName().toString();
            if (!name.endsWith(".java")) return false;
            if (name.startsWith(".")) return false;
            if (name.endsWith(".d.java")) return false;
            if (name.contains(".test.")) return false;
            if (name.contains(".spec.")) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String calcReceiver(Path javaFile) {
        try {
            Path rel = pluginsDir.relativize(javaFile);
            String s = rel.toString().replace("\\", "/");
            return s.replaceAll("\\.java$", "");
        } catch (Throwable t) {
            return javaFile.getFileName().toString().replace(".java", "");
        }
    }
}

package com.aicp.runtime;

import com.aicp.core.Plugin;
import com.aicp.core.Plugins;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Plugin Loader — 全量编译 + mtime 校验 + 分阶段加载 + 依赖自动下载
 *
 * receiver = 文件路径（相对 pluginsDir），去掉 .java
 *
 * 特性：
 *   - 全量编译：所有插件都在 src/plugins/
 *   - mtime 校验：.class 比 .java 新 → 跳过编译
 *   - 分阶段加载：核心优先
 *   - @DEPS：解析源码里的 // @DEPS: group:artifact:version，自动从 Maven 下载
 */
public class PluginLoader {

    private final String pluginsDir;
    private final List<String> dynamicLoaded = new ArrayList<>();

    /** 依赖 jar 存放目录 */
    private static final Path LIBS_DIR = Path.of("src/plugins/libs");

    /** Maven 镜像（阿里云） */
    private static final String MAVEN_MIRROR = "https://maven.aliyun.com/repository/public/";

    private static final List<String> CORE_RECEIVERS = List.of(
        "os/_registry",
        "os/_static",
        "os/_auth",
        "os/_gateway",
        "os/_websocket",
        "builtins/agents/capabilities",
         "builtins/agents/main_agent",
         "builtins/tools/task_manager",
         "builtins/tools/skill_loader"
    );

    /** @DEPS 匹配 */
    private static final Pattern DEPS_PATTERN = Pattern.compile(
        "^\\s*//\\s*@DEPS:\\s*(.+)$", Pattern.MULTILINE
    );

    /** 已下载依赖集合（groupId:artifactId:version） */
    private static final java.util.Set<String> DOWNLOADED_DEPS = new java.util.HashSet<>();

    public PluginLoader(String pluginsDir) {
        this.pluginsDir = pluginsDir != null ? pluginsDir : "src/plugins";
    }

    public PluginLoader() {
        this("src/plugins");
    }

    // ============================================================
    // 加载所有
    // ============================================================

    public int loadAll() {
        int loaded = 0;
        Path dir = Path.of(pluginsDir);

        List<Path> javaFiles = scan(dir);
        System.out.println("[PluginLoader] Found " + javaFiles.size() + " plugin source files");

        // 1. 核心优先
        for (String receiver : CORE_RECEIVERS) {
            Path file = dir.resolve(receiver + ".java");
            if (!Files.exists(file)) {
                System.err.println("[PluginLoader] Core plugin not found: " + file);
                continue;
            }
            if (loadDynamic(file, receiver)) loaded++;
        }

        // 2. 其他
        for (Path javaFile : javaFiles) {
            String receiver = calcReceiver(dir, javaFile);
            if (CORE_RECEIVERS.contains(receiver)) continue;
            if (dynamicLoaded.contains(receiver)) continue;
            if (loadDynamic(javaFile, receiver)) loaded++;
        }

        return loaded;
    }

    // ============================================================
    // 动态加载
    // ============================================================

    public boolean loadDynamic(Path javaFile, String receiver) {
        try {
            // 0. 解析 @DEPS，下载依赖
            List<String> deps = parseDeps(javaFile);
            if (!deps.isEmpty()) {
                for (String dep : deps) {
                    Path jar = downloadDependency(dep);
                    if (jar == null) {
                        System.err.println("[PluginLoader] Failed to download dependency: " + dep);
                    }
                }
            }

            // 1. mtime 校验
            Path classFile = javaFile.resolveSibling(
                javaFile.getFileName().toString().replace(".java", ".class")
            );

            boolean needCompile = true;
            if (Files.exists(classFile)) {
                try {
                    long javaMtime = Files.getLastModifiedTime(javaFile).toMillis();
                    long classMtime = Files.getLastModifiedTime(classFile).toMillis();
                    if (classMtime >= javaMtime) needCompile = false;
                } catch (IOException ignored) {}
            }

            // 2. 编译
            if (needCompile) {
                if (!compile(javaFile)) {
                    System.err.println("[PluginLoader] Compile FAILED: " + receiver);
                    return false;
                }
                System.out.println("[PluginLoader] Compiled: " + javaFile.getFileName());
            }

            // 3. ClassLoader 加载
            Path classDir = javaFile.getParent();
            String className = javaFile.getFileName().toString().replace(".java", "");

            // classpath 包含：插件目录 + libs 下的所有 jar
            List<URL> urls = new ArrayList<>();
            urls.add(classDir.toUri().toURL());

            if (Files.exists(LIBS_DIR)) {
                try (Stream<Path> stream = Files.list(LIBS_DIR)) {
                    stream.filter(p -> p.toString().endsWith(".jar"))
                        .forEach(p -> {
                            try { urls.add(p.toUri().toURL()); } catch (Exception ignored) {}
                        });
                } catch (IOException ignored) {}
            }

            URLClassLoader loader = new URLClassLoader(
                urls.toArray(new URL[0]),
                this.getClass().getClassLoader()
            );
            Class<?> clazz = loader.loadClass(className);

            // 4. 反射 new
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Plugin plugin)) {
                System.err.println("[PluginLoader] Not a Plugin: " + className);
                return false;
            }

            // 5. 用"路径算的 receiver"注册
            Plugins.INSTANCE.put(receiver, plugin);
            dynamicLoaded.add(receiver);
            System.out.println("[PluginLoader] Loaded: " + receiver);
            return true;

        } catch (ClassNotFoundException e) {
            System.err.println("[PluginLoader] Class not found: " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("[PluginLoader] Load failed (" + receiver + "): " + e.getMessage());
            return false;
        }
    }

    // ============================================================
    // 编译
    // ============================================================

    public boolean compile(Path javaFile) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("[PluginLoader] No JavaCompiler available");
            return false;
        }

        Path outDir = javaFile.getParent();

        // classpath：主程序 + libs 下的所有 jar
        List<String> classpathParts = new ArrayList<>();
        classpathParts.add(System.getProperty("java.class.path"));

        if (Files.exists(LIBS_DIR)) {
            try (Stream<Path> stream = Files.list(LIBS_DIR)) {
                stream.filter(p -> p.toString().endsWith(".jar"))
                    .forEach(p -> classpathParts.add(p.toString()));
            } catch (IOException ignored) {}
        }

        String classpath = String.join(File.pathSeparator, classpathParts);

        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(outDir.toString());
        args.add("-classpath");
        args.add(classpath);
        args.add(javaFile.toString());

        return compiler.run(null, null, null, args.toArray(new String[0])) == 0;
    }

    // ============================================================
    // @DEPS 解析 + 下载
    // ============================================================

    /**
     * 解析源码里的 // @DEPS: group:artifact:version
     */
    private List<String> parseDeps(Path javaFile) {
        List<String> deps = new ArrayList<>();
        try {
            String content = Files.readString(javaFile);
            Matcher m = DEPS_PATTERN.matcher(content);
            while (m.find()) {
                String dep = m.group(1).trim();
                // 格式：group:artifact:version
                String[] parts = dep.split(":");
                if (parts.length == 3) {
                    deps.add(dep);
                }
            }
        } catch (IOException ignored) {}
        return deps;
    }

    /**
     * 从 Maven 下载依赖。
     * 已下载 → 直接返回。
     * 否则 → 下载到 src/plugins/libs/{artifactId}-{version}.jar
     */
    private Path downloadDependency(String dep) {
        if (DOWNLOADED_DEPS.contains(dep)) {
            return findJarForDep(dep);
        }

        String[] parts = dep.split(":");
        if (parts.length != 3) return null;

        String groupId = parts[0];
        String artifactId = parts[1];
        String version = parts[2];

        String path = groupId.replace(".", "/") + "/" + artifactId + "/" + version
            + "/" + artifactId + "-" + version + ".jar";
        String url = MAVEN_MIRROR + path;

        Path target = LIBS_DIR.resolve(artifactId + "-" + version + ".jar");

        if (Files.exists(target)) {
            DOWNLOADED_DEPS.add(dep);
            return target;
        }

        try {
            Files.createDirectories(LIBS_DIR);
            System.out.println("[PluginLoader] Downloading: " + dep + " from " + url);

            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            DOWNLOADED_DEPS.add(dep);
            System.out.println("[PluginLoader] Downloaded: " + target.getFileName());
            return target;

        } catch (Exception e) {
            System.err.println("[PluginLoader] Download failed (" + dep + "): " + e.getMessage());
            return null;
        }
    }

    private Path findJarForDep(String dep) {
        String[] parts = dep.split(":");
        if (parts.length != 3) return null;
        Path target = LIBS_DIR.resolve(parts[1] + "-" + parts[2] + ".jar");
        return Files.exists(target) ? target : null;
    }

    // ============================================================
    // 卸载
    // ============================================================

    public boolean unloadDynamic(String receiver) {
        if (Plugins.INSTANCE.remove(receiver) != null) {
            dynamicLoaded.remove(receiver);
            System.out.println("[PluginLoader] Unloaded: " + receiver);
            return true;
        }
        return false;
    }

    public List<String> getDynamicLoaded() {
        return new ArrayList<>(dynamicLoaded);
    }

    // ============================================================
    // 扫描
    // ============================================================

    public List<Path> scan(Path dir) {
        List<Path> result = new ArrayList<>();
        if (!Files.exists(dir)) return result;

        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    if (name.startsWith(".")) return false;
                    if (name.endsWith(".d.java")) return false;
                    if (name.contains(".test.")) return false;
                    if (name.contains(".spec.")) return false;
                    return name.endsWith(".java");
                })
                .forEach(result::add);
        } catch (IOException e) {
            System.err.println("[PluginLoader] Scan failed: " + e.getMessage());
        }

        return result;
    }

    private String calcReceiver(Path pluginsDir, Path javaFile) {
        Path rel = pluginsDir.relativize(javaFile);
        String s = rel.toString().replace("\\", "/");
        return s.replaceAll("\\.java$", "");
    }
}

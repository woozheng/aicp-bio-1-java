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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Plugin Loader — 全量编译 + mtime 校验 + 分阶段加载 + 依赖自动下载（含传递依赖）
 *
 * receiver = 文件路径（相对 pluginsDir），去掉 .java
 *
 * ★ 不死保证：
 *   - loadAll 里每个插件的加载单独 try/catch (Throwable)
 *   - loadDynamic 用 catch (Throwable)
 *   - 单个插件失败不会崩整个进程
 *
 * ★ @DEPS 容错解析：
 *   - 兼容 // @DEPS: a:b:c
 *   - 兼容 // @DEPS a:b:c
 *   - 兼容 * @DEPS: a:b:c d:e:f
 *   - 兼容 // @dep: a:b:c（大小写不敏感）
 *   - 一行多个依赖，空格分隔
 *
 * ★ 传递依赖递归下载：
 *   - 下载 jar + pom
 *   - 递归下载 <parent> pom，合并 <properties>
 *   - 用合并后的 properties 替换 ${...}
 *   - 跳过 scope=test/provided、optional=true
 *   - visited 防循环
 */
public class PluginLoader {

    private final String pluginsDir;
    private final List<String> dynamicLoaded = new ArrayList<>();

    /** 依赖 jar/pom 存放目录 */
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

    // ============================================================
    // @DEPS 容错解析
    // ============================================================

    /** 匹配 @DEPS 行（大小写不敏感，冒号可选） */
    private static final Pattern DEPS_LINE_PATTERN = Pattern.compile(
        "@?DEPS:?\\s*(.+)",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );

    /** 从一行里抓所有 groupId:artifactId:version */
    private static final Pattern GAV_PATTERN = Pattern.compile(
        "([A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+)"
    );

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
            try {
                if (loadDynamic(file, receiver)) loaded++;
            } catch (Throwable t) {
                System.err.println("[PluginLoader] Core load failed: " + receiver + " - " + t);
                t.printStackTrace();
            }
        }

        // 2. 其他
        for (Path javaFile : javaFiles) {
            String receiver = calcReceiver(dir, javaFile);
            if (CORE_RECEIVERS.contains(receiver)) continue;
            if (dynamicLoaded.contains(receiver)) continue;
            try {
                if (loadDynamic(javaFile, receiver)) loaded++;
            } catch (Throwable t) {
                System.err.println("[PluginLoader] Load failed: " + receiver + " - " + t);
                t.printStackTrace();
            }
        }

        return loaded;
    }

    // ============================================================
    // 动态加载
    // ============================================================

    public boolean loadDynamic(Path javaFile, String receiver) {
        try {
            // 0. 解析 @DEPS，递归下载
            List<String> deps = parseDeps(javaFile);
            if (!deps.isEmpty()) {
                System.out.println("[PluginLoader] " + receiver + " 声明了 " + deps.size() + " 个依赖");
                Set<String> visited = new HashSet<>();
                for (String dep : deps) {
                    downloadDependencyRecursive(dep, visited);
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

            // 5. 注册
            Plugins.INSTANCE.put(receiver, plugin);
            dynamicLoaded.add(receiver);
            System.out.println("[PluginLoader] Loaded: " + receiver);
            return true;

        } catch (Throwable t) {
            System.err.println("[PluginLoader] Load failed (" + receiver + "): " + t);
            t.printStackTrace();
            return false;
        }
    }

    // ============================================================
    // 编译
    // ============================================================

    public boolean compile(Path javaFile) {
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                System.err.println("[PluginLoader] No JavaCompiler available");
                return false;
            }

            Path outDir = javaFile.getParent();

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
        } catch (Throwable t) {
            System.err.println("[PluginLoader] Compile error: " + t);
            return false;
        }
    }

    // ============================================================
    // @DEPS 容错解析
    // ============================================================

    /**
     * 从 Java 文件里提取所有依赖坐标。
     * 兼容多种格式，见类注释。
     */
    private List<String> parseDeps(Path javaFile) {
        List<String> deps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            String content = Files.readString(javaFile);
            Matcher lineMatcher = DEPS_LINE_PATTERN.matcher(content);
            while (lineMatcher.find()) {
                String line = lineMatcher.group(1);

                // 去掉行尾注释/闭合符号
                line = line.replaceAll("\\*/.*$", "");
                line = line.replaceAll("//.*$", "");
                line = line.trim();

                Matcher gav = GAV_PATTERN.matcher(line);
                while (gav.find()) {
                    String dep = gav.group(1);
                    if (seen.add(dep)) {
                        deps.add(dep);
                    }
                }
            }
        } catch (IOException ignored) {}
        return deps;
    }

    // ============================================================
    // 递归下载依赖（含传递依赖 + 父 pom 解析）
    // ============================================================

    /**
     * 递归下载依赖。
     * 1. 下载 jar
     * 2. 下载 pom
     * 3. 递归下载父 pom，合并 <properties>
     * 4. 用合并后的 properties 替换 ${...}
     * 5. 递归下载每个传递依赖
     */
    private void downloadDependencyRecursive(String dep, Set<String> visited) {
        if (dep == null || dep.isEmpty()) return;
        if (!visited.add(dep)) return;

        String[] parts = dep.split(":");
        if (parts.length != 3) return;

        String groupId = parts[0];
        String artifactId = parts[1];
        String version = parts[2];

        String basePath = groupId.replace(".", "/") + "/" + artifactId + "/" + version
            + "/" + artifactId + "-" + version;

        // 1. 下载 jar
        Path jar = LIBS_DIR.resolve(artifactId + "-" + version + ".jar");
        if (!Files.exists(jar)) {
            if (!downloadFile(MAVEN_MIRROR + basePath + ".jar", jar)) {
                System.err.println("[PluginLoader] jar 下载失败: " + dep);
                return;
            }
            System.out.println("[PluginLoader] Downloaded: " + jar.getFileName());
        }

        // 2. 下载 pom
        Path pom = LIBS_DIR.resolve(artifactId + "-" + version + ".pom");
        if (!Files.exists(pom)) {
            if (!downloadFile(MAVEN_MIRROR + basePath + ".pom", pom)) {
                // pom 下载失败不致命（有些 jar 没 pom）
                return;
            }
        }

        // 3. 递归父 pom，合并 properties
        Map<String, String> props = new HashMap<>();
        resolveParentPoms(pom, props, new HashSet<>());

        // 4. 解析 <dependencies>，替换 ${...}，递归下载
        try {
            String pomContent = Files.readString(pom);
            List<String> subDeps = parsePomDependencies(pomContent, props);
            for (String subDep : subDeps) {
                downloadDependencyRecursive(subDep, visited);
            }
        } catch (Throwable t) {
            System.err.println("[PluginLoader] pom 解析失败: " + dep + " - " + t);
        }
    }

    /**
     * 递归下载父 pom，把所有 <properties> 合并到 props。
     * 用 visited 防止父 pom 循环。
     */
    private void resolveParentPoms(Path pom, Map<String, String> props, Set<String> visited) {
        try {
            String content = Files.readString(pom);
            content = content.replaceAll("<!--[\\s\\S]*?-->", "");

            // 收集本 pom 的 <properties>
            Matcher pm = Pattern.compile("<properties>([\\s\\S]*?)</properties>").matcher(content);
            while (pm.find()) {
                Matcher kv = Pattern.compile("<([\\w.\\-]+)>([^<]+)</\\1>").matcher(pm.group(1));
                while (kv.find()) {
                    props.putIfAbsent(kv.group(1), kv.group(2).trim());
                }
            }

            // 找 <parent>
            Matcher par = Pattern.compile("<parent>([\\s\\S]*?)</parent>").matcher(content);
            if (par.find()) {
                String parentBlock = par.group(1);
                String pg = extractTag(parentBlock, "groupId");
                String pa = extractTag(parentBlock, "artifactId");
                String pv = extractTag(parentBlock, "version");
                if (pg == null || pa == null || pv == null) return;

                String key = pg + ":" + pa + ":" + pv;
                if (!visited.add(key)) return;

                String basePath = pg.replace(".", "/") + "/" + pa + "/" + pv
                    + "/" + pa + "-" + pv;
                Path parentPom = LIBS_DIR.resolve(pa + "-" + pv + ".pom");

                if (!Files.exists(parentPom)) {
                    downloadFile(MAVEN_MIRROR + basePath + ".pom", parentPom);
                }
                if (Files.exists(parentPom)) {
                    resolveParentPoms(parentPom, props, visited);
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 解析 pom 的 <dependencies>，用 props 替换 ${...}。
     * 跳过 scope=test/provided、optional=true、解析不出来的 ${...}。
     */
    private List<String> parsePomDependencies(String pomContent, Map<String, String> props) {
        List<String> deps = new ArrayList<>();
        pomContent = pomContent.replaceAll("<!--[\\s\\S]*?-->", "");

        Matcher m = Pattern.compile("<dependencies>([\\s\\S]*?)</dependencies>").matcher(pomContent);
        while (m.find()) {
            Matcher dm = Pattern.compile("<dependency>([\\s\\S]*?)</dependency>").matcher(m.group(1));
            while (dm.find()) {
                String dep = dm.group(1);
                String g = extractTag(dep, "groupId");
                String a = extractTag(dep, "artifactId");
                String v = extractTag(dep, "version");
                String scope = extractTag(dep, "scope");
                String optional = extractTag(dep, "optional");

                if (g == null || a == null || v == null) continue;
                if ("test".equals(scope) || "provided".equals(scope)) continue;
                if ("true".equals(optional)) continue;

                v = resolvePlaceholders(v, props);
                if (v == null || v.contains("${")) continue;

                deps.add(g + ":" + a + ":" + v);
            }
        }
        return deps;
    }

    /** 用 props 替换 ${...}，解析不出来的保留原样 */
    private String resolvePlaceholders(String v, Map<String, String> props) {
        Matcher m = Pattern.compile("\\$\\{([^}]+)\\}").matcher(v);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String val = props.getOrDefault(m.group(1), m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ============================================================
    // 下载
    // ============================================================

    private boolean downloadFile(String url, Path target) {
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Throwable t) {
            System.err.println("[PluginLoader] Download failed: " + url + " - " + t);
            return false;
        }
    }

    // ============================================================
    // XML 工具
    // ============================================================

    private String extractTag(String xml, String tag) {
        Pattern p = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    // ============================================================
    // 卸载
    // ============================================================

    public boolean unloadDynamic(String receiver) {
        try {
            if (Plugins.INSTANCE.remove(receiver) != null) {
                dynamicLoaded.remove(receiver);
                System.out.println("[PluginLoader] Unloaded: " + receiver);
                return true;
            }
        } catch (Throwable t) {
            System.err.println("[PluginLoader] Unload failed: " + receiver + " - " + t);
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
        } catch (Throwable t) {
            System.err.println("[PluginLoader] Scan failed: " + t);
        }

        return result;
    }

    private String calcReceiver(Path pluginsDir, Path javaFile) {
        try {
            Path rel = pluginsDir.relativize(javaFile);
            String s = rel.toString().replace("\\", "/");
            return s.replaceAll("\\.java$", "");
        } catch (Throwable t) {
            return javaFile.getFileName().toString().replace(".java", "");
        }
    }
}

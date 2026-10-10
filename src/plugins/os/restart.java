import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * os/restart — 系统重启插件
 *
 * 与 Python 版 plugins/builtins/os/restart.py 对齐。
 *
 * 原理：
 *   1. 检测是否被进程管理器托管（systemd / supervisor / docker / pm2）
 *      - 被托管：System.exit(0)，托管器自动拉起
 *      - 未托管：起一个 detached 的 shell 脚本
 *        - 脚本循环等端口 9000 释放
 *        - 端口释放后启动新 JVM（新窗口可见，实时显示启动日志）
 *   2. 3 秒延迟后执行
 *   3. System.exit(0) 后，兜底线程 3 秒后 halt，防止 shutdown hook 卡住
 */
public class restart implements Plugin {

    private static final ScheduledExecutorService SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "restart-scheduler");
            t.setDaemon(true);
            return t;
        });

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "restart";

        if ("restart".equals(action)) {
            System.out.println("[restart] 收到重启请求，3秒后重启...");

            envelop.payload = Map.of(
                "ok", true,
                "message", "系统将在3秒后重启"
            );

            SCHEDULER.schedule(this::doRestart, 3, TimeUnit.SECONDS);
            return envelop;
        }

        envelop.payload = Map.of("error", "Unknown action: " + action);
        return envelop;
    }

    // ============================================================
    // 重启逻辑
    // ============================================================

    private void doRestart() {
        System.out.println("[restart] 正在重启...");

        String managed = detectManaged();

        if (!managed.isEmpty()) {
            System.out.println("[restart] 托管方式: " + managed + "，退出让托管器拉起");
            exitNow();
            return;
        }

        try {
            List<String> cmd = buildRestartCommand();
            if (cmd == null || cmd.isEmpty()) {
                System.err.println("[restart] 无法识别启动方式，放弃重启");
                System.exit(1);
                return;
            }

            System.out.println("[restart] 重启命令: " + cmd);
            spawnDetached(cmd);

        } catch (Exception e) {
            System.err.println("[restart] 启动新进程失败: " + e.getMessage());
            e.printStackTrace();
        }

        exitNow();
    }

    /**
     * 优雅退出 + 3 秒兜底强制退出
     */
    private static void exitNow() {
        // 兜底：3 秒后强制 halt（non-daemon，防止 shutdown hook 卡住）
        Thread forceExit = new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {}
            System.err.println("[restart] shutdown hook 未在 3 秒内完成，强制退出");
            Runtime.getRuntime().halt(0);
        }, "restart-force-exit");
        // ★ 不设 daemon —— 让 JVM 等它
        forceExit.start();

        // 优雅退出（跑 shutdown hook）
        System.exit(0);
    }

    // ============================================================
    // 检测托管方式
    // ============================================================

    private static String detectManaged() {
        Map<String, String> env = System.getenv();

        if (env.get("INVOCATION_ID") != null || env.get("JOURNAL_STREAM") != null) {
            return "systemd";
        }
        if (env.get("SUPERVISOR_ENABLED") != null || env.get("SUPERVISOR_PROCESS_NAME") != null) {
            return "supervisor";
        }
        if (Files.exists(Path.of("/.dockerenv"))) {
            return "docker";
        }
        if (env.get("PM2_HOME") != null) {
            return "pm2";
        }

        return "";
    }

    // ============================================================
    // 构建重启命令
    // ============================================================

    private static List<String> buildRestartCommand() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        // 1. 自定义命令优先
        String customCmd = System.getenv("AICP_RESTART_CMD");
        if (customCmd != null && !customCmd.isBlank()) {
            System.out.println("[restart] 使用 AICP_RESTART_CMD");
            return splitCommand(customCmd);
        }

        // 2. 从 sun.java.command 推断
        String javaHome = System.getProperty("java.home");
        String javaExe = javaHome + File.separator + "bin" + File.separator +
            (isWindows ? "java.exe" : "java");

        String classpath = System.getProperty("java.class.path");
        String command = System.getProperty("sun.java.command");

        if (command == null || command.isBlank()) {
            return null;
        }

        System.out.println("[restart] raw sun.java.command = [" + command + "]");

        String[] parts = command.trim().split("\\s+");
        if (parts.length == 0) return null;

        String main = parts[0].trim().replace("\r", "").replace("\n", "");

        System.out.println("[restart] main = [" + main + "] endsWith .jar = " + main.endsWith(".jar"));

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);

        if (main.endsWith(".jar")) {
            cmd.add("-jar");
            cmd.add(main);
        } else {
            cmd.add("-cp");
            cmd.add(classpath);
            cmd.add(main);
        }

        for (int i = 1; i < parts.length; i++) {
            cmd.add(parts[i]);
        }

        return cmd;
    }

    /**
     * 简单命令行拆分（支持引号）
     */
    private static List<String> splitCommand(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    // ============================================================
    // 起 detached 进程（新窗口可见）
    // ============================================================

    /**
     * 起一个 detached 进程：
     *   1. 写 restart.bat（含"等端口释放"循环 + 启动新 JVM）
     *   2. 写 restart.vbs（隐藏 cmd 层级，弹出新窗口跑 bat）
     *   3. 用 wscript 起 vbs
     *
     * 新 JVM 的 stdout/stderr 直接显示在新窗口，同时也写 restart.log。
     */
    private static void spawnDetached(List<String> cmd) throws Exception {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String userDir = System.getProperty("user.dir");

        Path dataDir = Path.of(userDir, "data");
        Files.createDirectories(dataDir);

        if (isWindows) {
            // ========== Windows ==========

            String javaExe = cmd.get(0);
            StringBuilder rest = new StringBuilder();
            for (int i = 1; i < cmd.size(); i++) {
                if (rest.length() > 0) rest.append(" ");
                String part = cmd.get(i);
                if (part.contains(" ") || part.contains("\"")) {
                    rest.append("\"").append(part.replace("\"", "\\\"")).append("\"");
                } else {
                    rest.append(part);
                }
            }

            // 1. 写 .bat
            Path bat = dataDir.resolve("restart.bat");
            String batContent =
                "@echo off\r\n" +
                "chcp 65001 >nul\r\n" +
                "title AICP Restart\r\n" +
                "cd /d \"" + userDir + "\"\r\n" +
                "\r\n" +
                "echo ============================================\r\n" +
                "echo   AICP Restart\r\n" +
                "echo ============================================\r\n" +
                "echo.\r\n" +
                "echo [restart] 等待端口 9000 释放（最多 30 秒）...\r\n" +
                "echo.\r\n" +
                "\r\n" +
                "set /a count=0\r\n" +
                ":wait\r\n" +
                "timeout /t 1 /nobreak >nul\r\n" +
                "netstat -ano | findstr \":9000\" | findstr \"LISTENING\" >nul\r\n" +
                "if %errorlevel%==0 (\r\n" +
                "  set /a count+=1\r\n" +
                "  echo [restart] 端口占用中... ^(第 %count% 次检查^)\r\n" +
                "  if %count% lss 30 goto wait\r\n" +
                ")\r\n" +
                "\r\n" +
                "echo.\r\n" +
                "echo [restart] 端口已释放，启动新 JVM...\r\n" +
                "echo [restart] 时间: %date% %time%\r\n" +
                "echo.\r\n" +
                "\r\n" +
                "REM 启动新 JVM（输出显示在窗口，同时写日志）\r\n" +
                "powershell -NoProfile -Command \"& '" + javaExe + "' " + rest + " 2>&1 | Tee-Object -FilePath 'data\\restart.log'\"\r\n" +
                "\r\n" +
                "echo.\r\n" +
                "echo [restart] JVM 已退出。按任意键关闭窗口...\r\n" +
                "pause >nul\r\n";
            Files.writeString(bat, batContent);

            // 2. 写 .vbs（弹新窗口跑 bat）
            Path vbs = dataDir.resolve("restart.vbs");
            String vbsContent =
                "Set WshShell = CreateObject(\"WScript.Shell\")\r\n" +
                "WshShell.Run \"cmd /c \"\"" + bat.toString() + "\"\"\", 1, False\r\n";
            Files.writeString(vbs, vbsContent);

            // 3. 起 wscript（它自己闪一下就退，不影响 bat 的独立窗口）
            ProcessBuilder pb = new ProcessBuilder(
                "wscript.exe", "//nologo", "//B", vbs.toString()
            );
            pb.directory(new File(userDir));
            Process p = pb.start();
            p.waitFor(2, TimeUnit.SECONDS);

            System.out.println("[restart] 已起 restart.vbs（新窗口将显示启动日志）");

        } else {
            // ========== Unix ==========

            Path script = dataDir.resolve("restart.sh");
            String sh =
                "#!/bin/sh\n" +
                "cd \"" + userDir + "\"\n" +
                "\n" +
                "echo '============================================'\n" +
                "echo '  AICP Restart'\n" +
                "echo '============================================'\n" +
                "\n" +
                "echo '[restart] 等待端口 9000 释放（最多 30 秒）...'\n" +
                "\n" +
                "count=0\n" +
                "while netstat -tuln 2>/dev/null | grep -q ':9000 '; do\n" +
                "  sleep 1\n" +
                "  count=$((count+1))\n" +
                "  echo \"[restart] 端口占用中... (第 $count 次检查)\"\n" +
                "  [ $count -gt 30 ] && break\n" +
                "done\n" +
                "\n" +
                "echo '[restart] 端口已释放，启动新 JVM...'\n" +
                "echo \"[restart] 时间: $(date)\"\n" +
                "\n" +
                joinCmd(cmd) + " 2>&1 | tee -a data/restart.log\n";
            Files.writeString(script, sh);
            script.toFile().setExecutable(true);

            // Unix 用 xterm / gnome-terminal 起新窗口
            // 退回到 nohup + tee 到文件（如果没窗口）
            ProcessBuilder pb;
            if (isCommandAvailable("gnome-terminal")) {
                pb = new ProcessBuilder("gnome-terminal", "--", "sh", script.toString());
            } else if (isCommandAvailable("xterm")) {
                pb = new ProcessBuilder("xterm", "-e", "sh", script.toString());
            } else {
                // 无窗口环境（服务器），退回到后台 + 日志
                pb = new ProcessBuilder("nohup", "sh", script.toString());
            }
            pb.directory(new File(userDir));
            Process p = pb.start();
            p.waitFor(2, TimeUnit.SECONDS);

            System.out.println("[restart] 已起 restart.sh");
        }
    }

    private static boolean isCommandAvailable(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把命令列表拼成单行字符串，带空格的参数加引号
     */
    private static String joinCmd(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.size(); i++) {
            if (i > 0) sb.append(" ");
            String part = cmd.get(i);
            if (part.contains(" ") || part.contains("\"")) {
                sb.append("\"").append(part.replace("\"", "\\\"")).append("\"");
            } else {
                sb.append(part);
            }
        }
        return sb.toString();
    }
}

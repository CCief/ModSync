package modsync.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 客户端自重启（尽力而为，但比 v0.1.0 可靠得多）。
 *
 * v0.1.0 的做法是：JVM 参数 + sun.java.command，**漏掉了 classpath** → 新进程一起来就
 * 因为找不到 Minecraft 的 jar 而立刻死掉，玩家只看到"游戏闪退"。
 *
 * 这里做三件事：
 *  1. 补上 -cp <java.class.path>（PCL / MultiMC / Prism / 官方启动器都靠它）
 *  2. 正确解析主类与参数（sun.java.command 第一个 token 是类名就当主类，是 .jar 就用 -jar）
 *  3. ★ 先拉起新进程并确认它还活着，再退出当前进程 —— 万一拉不起来，游戏不会被弄没，
 *     而是提示玩家手动重启。
 *
 * 同时把「要重连的服务器地址」写进标记文件，重启后由 ClientSync 读取并自动连回去。
 */
public final class Restart {
    private Restart() {}

    /** 标记文件：存在即表示「这是 mod 主动重启留下的，重启后需要自动重连」。 */
    public static final Path RECONNECT_FILE =
            FabricLoader.getInstance().getGameDir().resolve("modsync_reconnect.txt");

    public static void restart(String serverAddress) {
        try {
            saveReconnectTarget(serverAddress);
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<String> cmd = buildCommand();
        if (cmd == null) {
            fail("无法还原启动命令行");
            return;
        }

        try {
            System.out.println("[ModSync] 重启命令: " + String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            Process p = pb.start();

            // ★ 等待并确认新进程存活，再退出当前进程
            boolean alive = false;
            for (int i = 0; i < 30; i++) {          // 最多等 15 秒
                Thread.sleep(500);
                if (p.isAlive()) { alive = true; break; }
                if (p.exitValue() != 0) break;      // 明确失败退出
            }

            if (!alive) {
                fail("新进程启动失败（可能 classpath 不匹配）。mod 已装好，请手动重启游戏。");
                return;
            }

            System.out.println("[ModSync] 新进程已启动，退出当前游戏进程。");
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            fail("重启异常: " + e.getMessage() + " —— mod 已装好，请手动重启游戏。");
        }
    }

    /**
     * 还原出与当前进程等价的启动命令。
     * 关键：必须带 -cp java.class.path，否则新进程找不到 Minecraft。
     */
    private static List<String> buildCommand() {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + (isWindows() ? "java.exe" : "java");
        if (!new File(javaBin).exists()) {
            javaBin = "java";
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());

        String cp = System.getProperty("java.class.path");
        String sun = System.getProperty("sun.java.command");
        if (sun == null || sun.isBlank()) return null;

        // sun.java.command 的第一个 token 可能是主类，也可能是 .jar
        int sp = sun.indexOf(' ');
        String head = sp > 0 ? sun.substring(0, sp) : sun;
        String tail = sp > 0 ? sun.substring(sp + 1) : "";

        if (head.endsWith(".jar")) {
            cmd.add("-jar");
            cmd.add(head);
        } else {
            if (cp == null || cp.isBlank()) return null;
            cmd.add("-cp");
            cmd.add(cp);
            cmd.add(head);
        }
        if (!tail.isBlank()) cmd.add(tail);
        return cmd;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 记录要重连的服务器地址，供重启后自动重连。 */
    static void saveReconnectTarget(String serverAddress) throws IOException {
        if (serverAddress == null || serverAddress.isBlank()) return;
        Files.write(RECONNECT_FILE, serverAddress.getBytes(StandardCharsets.UTF_8));
    }

    /** 读取待重连地址；没有则返回 null。 */
    public static String readReconnectTarget() {
        try {
            if (!Files.exists(RECONNECT_FILE)) return null;
            String s = new String(Files.readAllBytes(RECONNECT_FILE), StandardCharsets.UTF_8).trim();
            Files.deleteIfExists(RECONNECT_FILE);
            return s.isBlank() ? null : s;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static void fail(String msg) {
        System.out.println("[ModSync] " + msg);
        ClientSync.notifyUser("§c[ModSync] " + msg);
    }
}

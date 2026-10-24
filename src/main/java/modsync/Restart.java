package modsync;

import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * 尽力而为的客户端自重启：
 * 用当前 JVM 参数 + sun.java.command 重新拉起一个相同进程，然后退出本进程。
 * 对 MultiMC/Prism/手动 java -jar 启动有效；官方启动器若无法自重启，
 * 由于 mod 已写入 mods/，玩家手动重启一次即可生效（finishSync 也会提示）。
 */
public class Restart {
    public static void restart() {
        try {
            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            cmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
            String sun = System.getProperty("sun.java.command");
            if (sun != null && !sun.isBlank()) cmd.add(sun);

            new File(FabricLoader.getInstance().getGameDir().toFile(), "modsync_restart.lock").createNewFile();
            new ProcessBuilder(cmd).inheritIO().start();
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

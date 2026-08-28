package com.agentdemo.skill.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 预置技能播种运行器
 * <p>
 * 业务含义：应用启动完成后，将 classpath 下的预置技能播种到技能存储
 * （技术方案 4.3，AC-N06 预置初始态）。幂等——目标技能目录已存在则不覆盖用户修改。
 * 支持 file（IDE/本地目录运行）与 jar（打包部署/spring-boot:run）两种资源协议：
 * jar 场景经 JarFile 将技能目录提取到临时目录后复用 seedPresets(Path) 统一播种。
 * </p>
 */
@Component
public class SkillPresetSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillPresetSeeder.class);

    /** classpath 预置技能目录（与 SkillStore 同模块资源） */
    private static final String PRESETS_CLASSPATH_DIR = "skills";

    private final SkillStore skillStore;

    public SkillPresetSeeder(SkillStore skillStore) {
        this.skillStore = skillStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            // 从 classpath 定位预置目录（启动后可读），转为文件路径供 seedPresets 遍历
            var resource = getClass().getClassLoader().getResource(PRESETS_CLASSPATH_DIR);
            if (resource == null) {
                log.warn("预置技能目录不存在于 classpath，跳过播种: {}", PRESETS_CLASSPATH_DIR);
                return;
            }
            // file:/ 协议下直接取路径；jar 内资源走临时目录提取（打包部署场景）
            if ("file".equalsIgnoreCase(resource.getProtocol())) {
                skillStore.seedPresets(Path.of(resource.toURI()));
            } else if ("jar".equalsIgnoreCase(resource.getProtocol())) {
                seedFromJar(resource);
            } else {
                log.warn("预置技能资源协议不支持（{}），跳过播种", resource.getProtocol());
            }
        } catch (Exception e) {
            log.warn("预置技能播种异常（不影响启动）: {}", e.getMessage());
        }
    }

    /**
     * jar 协议播种：将 jar 内 skills/ 目录提取到临时目录，复用 seedPresets(Path) 统一播种（幂等）
     */
    private void seedFromJar(URL resource) throws IOException {
        JarURLConnection connection = (JarURLConnection) resource.openConnection();
        try (JarFile jarFile = connection.getJarFile()) {
            String dirEntry = connection.getEntryName();
            String prefix = (dirEntry == null || dirEntry.isBlank()) ? "skills"
                    : (dirEntry.endsWith("/") ? dirEntry : dirEntry + "/");
            Path tempPresets = Files.createTempDirectory("skill-presets");
            try {
                Path extractRoot = tempPresets.resolve("skills");
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith(prefix)) {
                        continue;
                    }
                    String relative = name.substring(prefix.length());
                    if (relative.isEmpty()) {
                        continue;
                    }
                    Path target = extractRoot.resolve(relative);
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        try (InputStream in = jarFile.getInputStream(entry)) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                skillStore.seedPresets(extractRoot);
            } finally {
                deleteRecursively(tempPresets);
            }
        }
    }

    private void deleteRecursively(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时清理失败可忽略（不影响播种）
                }
            });
        } catch (IOException ignored) {
            // 忽略清理失败
        }
    }
}

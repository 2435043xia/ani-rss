package ani.rss.service;

import ani.rss.entity.Config;
import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read the configured execution host without saving settings or starting a worker. */
@Service
public class SubtitleOrcaService {
    public record Effort(String value, String label) {}
    public record Model(String id, String label, boolean isDefault, List<Effort> efforts) {}
    public record Provider(String id, String label, boolean supportsModelSelection,
                           boolean inheritOnly, String origin, List<Model> models) {}
    public record Catalog(boolean ok, List<Provider> providers, List<String> errors,
                          boolean launchModelSelectionSupported, Long checkedAt, String error) {}

    public Catalog models(Config config) {
        Integer port = config.getSubtitleTranslateSshPort();
        String key = StrUtil.blankToDefault(config.getSubtitleTranslateSshKeyPath(), "").trim();
        String binary = StrUtil.blankToDefault(config.getSubtitleTranslateOrcaBin(), "").trim();
        if (port == null || !SubtitleTranslateSupport.allowedOrcaBin(binary)) {
            throw new IllegalArgumentException("请先填写有效的 SSH 端口和 Orca 路径。");
        }
        List<String> command = SubtitleTranslateSupport.sshCommand(config.getSubtitleTranslateSshHost(),
                port, config.getSubtitleTranslateSshUser(), key)
                .orElseThrow(() -> new IllegalArgumentException("请核对 SSH 主机、用户、端口和私钥路径。"));
        if (!Files.isRegularFile(Path.of(key))) {
            throw new IllegalArgumentException("服务器无法读取该私钥文件，请填写服务器或容器内的路径。");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "catalogs");
        payload.put("orca", binary);
        payload.put("agent", StrUtil.blankToDefault(config.getSubtitleTranslateOrcaAgent(), "grok").trim());
        String output = execute(command, new Gson().toJson(payload));
        Catalog result;
        try {
            result = new Gson().fromJson(output, Catalog.class);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Orca 模型目录响应无效，请检查远端安装及连接。", error);
        }
        if (result == null || !result.ok() || result.providers() == null || result.errors() == null) {
            throw new IllegalArgumentException("Orca 模型目录读取失败，请检查 SSH、远端 Orca 是否运行及版本是否支持模型查询。");
        }
        return result;
    }

    protected String execute(List<String> command, String input) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process running = process;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            AtomicBoolean overflow = new AtomicBoolean(false);
            Thread reader = new Thread(() -> {
                try (var stream = running.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) >= 0) {
                        if (output.size() + count > 2 * 1024 * 1024) {
                            overflow.set(true); running.destroyForcibly(); return;
                        }
                        output.write(buffer, 0, count);
                    }
                } catch (Exception ignored) {}
            });
            reader.setDaemon(true); reader.start();
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            if (!process.waitFor(110, TimeUnit.SECONDS)) {
                throw new IllegalArgumentException("SSH 模型查询超时，请检查远端 Orca 后重试。");
            }
            reader.join(5000);
            if (reader.isAlive() || overflow.get()) throw new IllegalArgumentException("Orca 模型目录响应超过限制。");
            if (output.size() == 0) throw new IllegalArgumentException("SSH 未返回模型目录，请检查连接和私钥权限。");
            return output.toString(StandardCharsets.UTF_8);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("模型查询已中断。", error);
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("无法启动 SSH 模型查询，请检查服务器工具和连接配置。", error);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}

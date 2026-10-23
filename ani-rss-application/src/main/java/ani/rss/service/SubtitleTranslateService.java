package ani.rss.service;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.service.SubtitleTranslateSupport.Choice;
import ani.rss.service.SubtitleTranslateSupport.Cue;
import ani.rss.service.SubtitleTranslateSupport.ModelReply;
import ani.rss.service.SubtitleTranslateSupport.Skip;
import ani.rss.service.SubtitleTranslateSupport.SubtitleStream;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import wushuo.tmdb.api.entity.Tmdb;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 下载完成后，把勾选了翻译的订阅里的文字软字幕译成简体中文，写到视频旁边。
 */
@Slf4j
@Service
public class SubtitleTranslateService {

    private static final Set<String> VIDEO_EXT = Set.of("mkv", "mp4", "m4v", "avi", "ts", "wmv", "mov");
    private static final ConcurrentHashMap<String, Object> GLOSSARY_LOCKS = new ConcurrentHashMap<>();
    private static final Set<String> BUSY = ConcurrentHashMap.newKeySet();

    public record Target(Ani ani, String directory) {
    }

    public void translateCompleted(Ani ani, TorrentsInfo torrentsInfo) {
        if (ani == null || !Boolean.TRUE.equals(ani.getTranslateSubtitle())) {
            return;
        }
        Tmdb tmdb = ani.getTmdb();
        String tmdbId = tmdb == null ? "" : tmdb.getId();
        if (!SubtitleTranslateSupport.hasTmdbId(tmdbId)) {
            log.info("订阅没有 TMDB id，跳过字幕翻译 {}", ani.getTitle());
            return;
        }
        if (!translatorReady(ani.getTitle())) {
            return;
        }
        if (torrentsInfo == null || torrentsInfo.getFilesSupplier() == null || StrUtil.isBlank(torrentsInfo.getSavePath())) {
            log.info("字幕翻译没有拿到视频文件 {}", ani.getTitle());
            return;
        }
        List<String> names;
        try {
            names = torrentsInfo.getFilesSupplier().get();
        } catch (Exception e) {
            log.info("字幕翻译没有拿到视频文件 {}", ani.getTitle());
            return;
        }
        if (names == null || names.isEmpty()) {
            log.info("字幕翻译没有拿到视频文件 {}", ani.getTitle());
            return;
        }
        for (String name : names) {
            if (!isVideo(name)) {
                continue;
            }
            File video = new File(torrentsInfo.getSavePath(), name);
            translateFile(ani, tmdbId.trim(), video);
        }
    }

    public void translateExistingAsync(List<Target> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        List<Target> copy = List.copyOf(targets);
        ThreadUtil.execute(() -> {
            for (Target target : copy) {
                try {
                    translateInDirectory(target.ani(), target.directory());
                } catch (Exception e) {
                    String title = target.ani() == null ? "" : target.ani().getTitle();
                    log.info("字幕翻译失败 {}", title);
                }
            }
        });
    }

    public void translateInDirectory(Ani ani, String directory) {
        String tmdbId = readyId(ani);
        if (tmdbId == null || StrUtil.isBlank(directory)) {
            return;
        }
        File dir = new File(directory);
        if (!dir.isDirectory()) {
            return;
        }
        List<File> pending = FileUtil.loopFiles(dir, SubtitleTranslateService::needsTranslation);
        if (pending.isEmpty()) {
            return;
        }
        log.info("开始补翻字幕 {} {} 个视频", ani.getTitle(), pending.size());
        for (File video : pending) {
            translateFile(ani, tmdbId, video);
        }
    }

    static boolean needsTranslation(File video) {
        if (video == null || !video.isFile() || !isVideo(video.getName())) {
            return false;
        }
        File output = new File(video.getParentFile(), FileUtil.mainName(video) + ".zh.srt");
        return !output.isFile();
    }

    private void translateFile(Ani ani, String tmdbId, File video) {
        if (video == null || !video.isFile()) {
            log.info("字幕翻译找不到视频 {}", video == null ? "" : video.getName());
            return;
        }
        if (!needsTranslation(video)) {
            return;
        }
        if (!BUSY.add(video.getAbsolutePath())) {
            return;
        }
        try {
            translateFileBody(ani, tmdbId, video);
        } finally {
            BUSY.remove(video.getAbsolutePath());
        }
    }

    private void translateFileBody(Ani ani, String tmdbId, File video) {
        File output = new File(video.getParentFile(), FileUtil.mainName(video) + ".zh.srt");
        if (output.isFile()) {
            return;
        }
        String probe = run(List.of(tool("ffprobe"), "-v", "error", "-print_format", "json", "-show_streams",
                "-select_streams", "s", video.getAbsolutePath()), null);
        if (probe == null) {
            log.info("未找到 ffprobe 或读取字幕失败，跳过 {}", video.getName());
            return;
        }
        Choice choice = SubtitleTranslateSupport.choose(parseProbe(probe));
        if (choice.skip() == Skip.HAS_CHINESE) {
            log.info("已有中文字幕轨，跳过 {}", video.getName());
            return;
        }
        if (choice.skip() == Skip.NO_TEXT || choice.stream() == null) {
            log.info("没有可翻译的文字字幕 {}", video.getName());
            return;
        }
        File extracted = new File(System.getProperty("java.io.tmpdir"), "ani-rss-" + video.getName() + ".srt");
        String extractedLog = run(List.of(tool("ffmpeg"), "-y", "-v", "error", "-i", video.getAbsolutePath(),
                "-map", "0:" + choice.stream().index(), "-c:s", "srt", extracted.getAbsolutePath()), extracted);
        if (extractedLog == null || !extracted.isFile()) {
            log.info("字幕翻译失败 {}", video.getName());
            FileUtil.del(extracted);
            return;
        }
        try {
            List<Cue> cues = SubtitleTranslateSupport.parseSrt(FileUtil.readUtf8String(extracted));
            if (cues.isEmpty()) {
                log.info("字幕翻译失败 {}", video.getName());
                return;
            }
            Object lock = GLOSSARY_LOCKS.computeIfAbsent(tmdbId, key -> new Object());
            synchronized (lock) {
                File glossaryFile = SubtitleTranslateSupport.glossaryFile(
                        ConfigUtil.getConfigDir(),
                        ConfigUtil.CONFIG.getSubtitleTranslateGlossaryPath(),
                        tmdbId);
                Map<String, String> glossary = glossaryFile.isFile()
                        ? SubtitleTranslateSupport.readGlossary(FileUtil.readUtf8String(glossaryFile))
                        : new LinkedHashMap<>();
                List<Cue> translated = translateCues(ani.getTitle(), glossary, cues);
                if (translated == null) {
                    log.info("字幕翻译失败 {}", video.getName());
                    return;
                }
                FileUtil.writeUtf8String(SubtitleTranslateSupport.renderSrt(translated), output);
                FileUtil.mkParentDirs(glossaryFile);
                FileUtil.writeUtf8String(SubtitleTranslateSupport.writeGlossary(glossary), glossaryFile);
                log.info("字幕翻译完成 {}", output.getName());
            }
        } finally {
            FileUtil.del(extracted);
        }
    }

    private String readyId(Ani ani) {
        if (ani == null || !Boolean.TRUE.equals(ani.getTranslateSubtitle())) {
            return null;
        }
        Tmdb tmdb = ani.getTmdb();
        String tmdbId = tmdb == null ? "" : tmdb.getId();
        if (!SubtitleTranslateSupport.hasTmdbId(tmdbId)) {
            log.info("订阅没有 TMDB id，跳过字幕翻译 {}", ani.getTitle());
            return null;
        }
        if (!translatorReady(ani.getTitle())) {
            return null;
        }
        return tmdbId.trim();
    }

    private boolean translatorReady(String title) {
        Config config = ConfigUtil.CONFIG;
        if (SubtitleTranslateSupport.usesSsh(config.getSubtitleTranslateBackend())) {
            Integer port = config.getSubtitleTranslateSshPort();
            if (port == null
                    || SubtitleTranslateSupport.sshCommand(
                    config.getSubtitleTranslateSshHost(),
                    port,
                    config.getSubtitleTranslateSshUser(),
                    config.getSubtitleTranslateSshKeyPath()).isEmpty()
                    || !SubtitleTranslateSupport.allowedOrcaBin(config.getSubtitleTranslateOrcaBin())
                    || !new File(StrUtil.blankToDefault(config.getSubtitleTranslateSshKeyPath(), "")).isFile()) {
                log.info("未配置字幕翻译 SSH，跳过 {}", title);
                return false;
            }
            String model = StrUtil.blankToDefault(config.getSubtitleTranslateOrcaModel(), "").trim();
            String effort = StrUtil.blankToDefault(config.getSubtitleTranslateOrcaEffort(), "").trim();
            if (!SubtitleTranslateSupport.allowedAgent(StrUtil.blankToDefault(config.getSubtitleTranslateOrcaAgent(), "grok"))
                    || (!model.isEmpty() && !SubtitleTranslateSupport.allowedModel(model))
                    || !SubtitleTranslateSupport.allowedEffort(effort) || (!effort.isEmpty() && model.isEmpty())) {
                log.info("未配置字幕翻译模型，跳过 {}", title);
                return false;
            }
            if (!SubtitleTranslateSupport.allowedWorktree(config.getSubtitleTranslateOrcaWorktree())) {
                log.info("未配置字幕翻译工作区，跳过 {}", title);
                return false;
            }
            return true;
        }
        if (StrUtil.isBlank(config.getSubtitleTranslateApiKey())) {
            log.info("未配置字幕翻译 API Key，跳过 {}", title);
            return false;
        }
        return true;
    }

    private List<Cue> translateCues(String title, Map<String, String> glossary, List<Cue> cues) {
        List<Cue> translated = new ArrayList<>();
        List<List<Cue>> groups = SubtitleTranslateSupport.batches(cues);
        boolean sshTranslation = SubtitleTranslateSupport.usesSsh(ConfigUtil.CONFIG.getSubtitleTranslateBackend());
        if (sshTranslation) {
            groups = cues.isEmpty() ? List.of() : List.of(List.copyOf(cues));
        }
        for (List<Cue> batch : groups) {
            Optional<ModelReply> reply = ask(title, glossary, batch);
            // An unconfirmed Orca receipt may still own a worker; never create a duplicate automatically.
            if (reply.isEmpty() && !sshTranslation) {
                reply = ask(title, glossary, batch);
            }
            if (reply.isEmpty()) {
                return null;
            }
            ModelReply modelReply = reply.get();
            glossary.putAll(SubtitleTranslateSupport.mergeGlossary(glossary, modelReply.terms()));
            for (int i = 0; i < batch.size(); i++) {
                Cue cue = batch.get(i);
                translated.add(new Cue(cue.start(), cue.end(), modelReply.lines().get(i)));
            }
        }
        return translated;
    }

    private Optional<ModelReply> ask(String title, Map<String, String> glossary, List<Cue> batch) {
        Config config = ConfigUtil.CONFIG;
        if (SubtitleTranslateSupport.usesSsh(config.getSubtitleTranslateBackend())) {
            return askSsh(config, title, glossary, batch);
        }
        return askApi(config, title, glossary, batch);
    }

    private Optional<ModelReply> askSsh(Config config, String title, Map<String, String> glossary, List<Cue> batch) {
        Optional<List<String>> command = SubtitleTranslateSupport.sshCommand(
                config.getSubtitleTranslateSshHost(),
                config.getSubtitleTranslateSshPort(),
                config.getSubtitleTranslateSshUser(),
                config.getSubtitleTranslateSshKeyPath());
        if (command.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orca", StrUtil.blankToDefault(config.getSubtitleTranslateOrcaBin(), "").trim());
        payload.put("worktree", config.getSubtitleTranslateOrcaWorktree().trim());
        payload.put("agent", StrUtil.blankToDefault(config.getSubtitleTranslateOrcaAgent(), "grok").trim());
        payload.put("model", StrUtil.blankToDefault(config.getSubtitleTranslateOrcaModel(), "").trim());
        payload.put("effort", StrUtil.blankToDefault(config.getSubtitleTranslateOrcaEffort(), "").trim());
        payload.put("system", SubtitleTranslateSupport.systemPrompt());
        payload.put("prompt", SubtitleTranslateSupport.userPrompt(title, glossary, batch));
        String stdout = runStdin(command.get(), GsonStatic.toJson(payload));
        if (stdout == null) {
            return Optional.empty();
        }
        return SubtitleTranslateSupport.parseModelOutput(
                SubtitleTranslateSupport.modelText(stdout), batch.size());
    }

    private Optional<ModelReply> askApi(Config config, String title, Map<String, String> glossary, List<Cue> batch) {
        String base = StrUtil.blankToDefault(config.getSubtitleTranslateApiBase(), "https://api.deepseek.com");
        base = StrUtil.removeSuffix(base.trim(), "/");
        String url = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        String model = StrUtil.blankToDefault(config.getSubtitleTranslateModel(), "deepseek-chat").trim();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.2);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SubtitleTranslateSupport.systemPrompt()),
                Map.of("role", "user", "content", SubtitleTranslateSupport.userPrompt(title, glossary, batch))
        ));
        try {
            String response = HttpReq.post(url)
                    .header("Authorization", "Bearer " + config.getSubtitleTranslateApiKey().trim())
                    .header("Content-Type", "application/json")
                    .body(GsonStatic.toJson(body))
                    .timeout(120_000)
                    .execute()
                    .body();
            JsonObject root = JsonParser.parseString(response).getAsJsonObject();
            String content = root.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
            return SubtitleTranslateSupport.parseModelOutput(content, batch.size());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static List<SubtitleStream> parseProbe(String json) {
        List<SubtitleStream> streams = new ArrayList<>();
        if (StrUtil.isBlank(json)) {
            return streams;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return streams;
        }
        JsonArray array = root.getAsJsonArray("streams");
        if (array == null) {
            return streams;
        }
        for (JsonElement element : array) {
            JsonObject stream = element.getAsJsonObject();
            JsonObject tags = stream.has("tags") && stream.get("tags").isJsonObject()
                    ? stream.getAsJsonObject("tags") : new JsonObject();
            JsonObject disposition = stream.has("disposition") && stream.get("disposition").isJsonObject()
                    ? stream.getAsJsonObject("disposition") : new JsonObject();
            streams.add(new SubtitleStream(
                    stream.has("index") ? stream.get("index").getAsInt() : -1,
                    text(stream, "codec_name"),
                    text(tags, "language"),
                    text(tags, "title"),
                    flag(disposition, "hearing_impaired"),
                    flag(disposition, "default")
            ));
        }
        return streams;
    }

    private static String text(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            return "";
        }
        return object.get(name).getAsString();
    }

    private static boolean flag(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            return false;
        }
        JsonElement element = object.get(name);
        if (element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean();
        }
        return element.getAsInt() == 1;
    }

    private static boolean isVideo(String name) {
        String ext = FileUtil.extName(name);
        return ext != null && VIDEO_EXT.contains(ext.toLowerCase());
    }

    private static String tool(String name) {
        return name;
    }

    /**
     * @return 标准输出。命令不存在或失败时返回 null。提取字幕时只要进程成功就返回空串。
     */
    private static String run(List<String> command, File expectedFile) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            byte[] output = process.getInputStream().readAllBytes();
            process.getErrorStream().readAllBytes();
            boolean finished = process.waitFor(2, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            if (expectedFile != null) {
                return "";
            }
            return new String(output, StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return null;
        }
    }

    private static String runStdin(List<String> command, String stdin) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
            java.io.InputStream input = process.getInputStream();
            Thread reader = new Thread(() -> {
                try {
                    input.transferTo(captured);
                } catch (Exception ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(30, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            reader.join(5_000);
            if (process.exitValue() != 0) {
                return null;
            }
            return captured.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return null;
        }
    }
}

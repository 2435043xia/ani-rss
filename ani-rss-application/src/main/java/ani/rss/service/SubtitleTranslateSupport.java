package ani.rss.service;

import cn.hutool.core.util.StrUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字幕轨选择、术语表和模型输出解析。SSH 参数也在这里组装，不自己发起连接。
 */
public final class SubtitleTranslateSupport {

    static final int BATCH_SIZE = 40;

    private static final Pattern CUE_TIME = Pattern.compile("(\\d{2}:\\d{2}:\\d{2},\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2},\\d{3})");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+)\\s*[|｜.]\\s*(.*)$");
    private static final Pattern TERM = Pattern.compile("^(.+?)::(.+)$");

    private SubtitleTranslateSupport() {
    }

    public record SubtitleStream(int index, String codec, String language, String title,
                                 boolean hearingImpaired, boolean defaultTrack) {
    }

    public record Cue(String start, String end, String text) {
    }

    public enum Skip {
        NONE,
        HAS_CHINESE,
        NO_TEXT
    }

    public record Choice(Skip skip, SubtitleStream stream) {
        static Choice skipped(Skip skip) {
            return new Choice(skip, null);
        }
    }

    public record ModelReply(List<String> lines, Map<String, String> terms) {
    }

    public static boolean hasTmdbId(String id) {
        if (StrUtil.isBlank(id)) {
            return false;
        }
        String value = id.trim();
        return !"0".equals(value) && value.matches("[A-Za-z0-9_-]+");
    }

    public static File glossaryFile(File configDir, String configuredRoot, String tmdbId) {
        File root = StrUtil.isBlank(configuredRoot)
                ? new File(configDir, "terminology")
                : new File(configuredRoot.trim());
        return new File(root, tmdbId.trim() + ".txt");
    }

    public static Choice choose(List<SubtitleStream> streams) {
        List<SubtitleStream> text = new ArrayList<>();
        for (SubtitleStream stream : streams) {
            if (isTextCodec(stream.codec())) {
                text.add(stream);
            }
        }
        if (text.isEmpty()) {
            return Choice.skipped(Skip.NO_TEXT);
        }
        for (SubtitleStream stream : text) {
            if (isChinese(stream.language())) {
                return Choice.skipped(Skip.HAS_CHINESE);
            }
        }
        Optional<SubtitleStream> chosen = first(text, true, false, "ja");
        if (chosen.isEmpty()) {
            chosen = first(text, false, false, "ja");
        }
        if (chosen.isEmpty()) {
            chosen = first(text, true, false, "en");
        }
        if (chosen.isEmpty()) {
            chosen = first(text, false, false, "en");
        }
        if (chosen.isEmpty()) {
            chosen = first(text, true, true, "");
        }
        if (chosen.isEmpty()) {
            chosen = Optional.of(preferDefault(text).get(0));
        }
        return new Choice(Skip.NONE, chosen.get());
    }

    public static Map<String, String> readGlossary(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        if (text == null) {
            return map;
        }
        for (String raw : text.split("\\R")) {
            Matcher matcher = TERM.matcher(raw.trim());
            if (!matcher.matches()) {
                continue;
            }
            String key = matcher.group(1).trim();
            String value = matcher.group(2).trim();
            if (key.isEmpty() || value.isEmpty() || map.containsKey(key)) {
                continue;
            }
            map.put(key, value);
        }
        return map;
    }

    /**
     * 已有译名保持不变，只追加新词。
     */
    public static Map<String, String> mergeGlossary(Map<String, String> existing, Map<String, String> incoming) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (existing != null) {
            merged.putAll(existing);
        }
        if (incoming == null) {
            return merged;
        }
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (key.isEmpty() || value.isEmpty() || key.contains("\n") || value.contains("\n")) {
                continue;
            }
            merged.putIfAbsent(key, value);
        }
        return merged;
    }

    public static String writeGlossary(Map<String, String> glossary) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : glossary.entrySet()) {
            builder.append(entry.getKey()).append("::").append(entry.getValue()).append('\n');
        }
        return builder.toString();
    }

    public static List<Cue> parseSrt(String srt) {
        List<Cue> cues = new ArrayList<>();
        if (StrUtil.isBlank(srt)) {
            return cues;
        }
        String normalized = srt.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        for (String block : normalized.split("\\n\\s*\\n")) {
            String[] rows = block.strip().split("\\n");
            if (rows.length < 2) {
                continue;
            }
            int timeRow = 0;
            if (rows[0].trim().matches("\\d+")) {
                timeRow = 1;
            }
            if (timeRow >= rows.length) {
                continue;
            }
            Matcher matcher = CUE_TIME.matcher(rows[timeRow].trim());
            if (!matcher.find()) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (int i = timeRow + 1; i < rows.length; i++) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(rows[i]);
            }
            if (text.isEmpty()) {
                continue;
            }
            cues.add(new Cue(matcher.group(1), matcher.group(2), text.toString()));
        }
        return cues;
    }

    public static String renderSrt(List<Cue> cues) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            if (i > 0) {
                builder.append("\n\n");
            }
            builder.append(i + 1).append('\n')
                    .append(cue.start()).append(" --> ").append(cue.end()).append('\n')
                    .append(cue.text());
        }
        if (!cues.isEmpty()) {
            builder.append('\n');
        }
        return builder.toString();
    }

    public static String systemPrompt() {
        return """
                你在翻译番剧字幕。把每条字幕译成简体中文口语，保留原来的语气。
                必须沿用术语表里的译名，不要改已有译法。
                只输出译文，一行一条，格式为序号|译文。序号从 1 开始，条数必须和输入一致。
                译文本身不要换行。
                译文全部输出后，再输出术语块。只写这次新出现的人名和固定专名，已有术语不要重复。
                没有新词时术语块留空。
                <terminology>
                原文::译文
                </terminology>
                """;
    }

    public static String userPrompt(String title, Map<String, String> glossary, List<Cue> cues) {
        StringBuilder builder = new StringBuilder();
        builder.append("片名：").append(StrUtil.blankToDefault(title, "")).append('\n');
        builder.append("术语表：\n");
        if (glossary != null) {
            glossary.forEach((key, value) -> builder.append(key).append("::").append(value).append('\n'));
        }
        builder.append("字幕：\n");
        for (int i = 0; i < cues.size(); i++) {
            String line = cues.get(i).text().replace('\n', ' ').replace("|", " ").replace("｜", " ");
            builder.append(i + 1).append('|').append(line).append('\n');
        }
        return builder.toString();
    }

    public static Optional<ModelReply> parseModelOutput(String content, int expected) {
        if (StrUtil.isBlank(content) || expected < 1) {
            return Optional.empty();
        }
        String text = content.replace("\r\n", "\n").trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\n", "").replaceFirst("\\n```$", "");
        }
        String termsBlock = "";
        int start = text.indexOf("<terminology>");
        int end = text.indexOf("</terminology>");
        if (start >= 0 && end > start) {
            termsBlock = text.substring(start + "<terminology>".length(), end);
            text = text.substring(0, start);
        }
        Map<Integer, String> numbered = new LinkedHashMap<>();
        for (String raw : text.split("\\n")) {
            Matcher matcher = NUMBERED.matcher(raw.trim());
            if (!matcher.matches()) {
                continue;
            }
            int index = Integer.parseInt(matcher.group(1));
            String line = matcher.group(2).trim();
            if (index >= 1 && index <= expected && !line.isEmpty()) {
                numbered.put(index, line);
            }
        }
        if (numbered.size() != expected) {
            return Optional.empty();
        }
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= expected; i++) {
            lines.add(numbered.get(i));
        }
        return Optional.of(new ModelReply(lines, readGlossary(termsBlock)));
    }

    public static List<List<Cue>> batches(List<Cue> cues) {
        List<List<Cue>> groups = new ArrayList<>();
        for (int i = 0; i < cues.size(); i += BATCH_SIZE) {
            groups.add(List.copyOf(cues.subList(i, Math.min(cues.size(), i + BATCH_SIZE))));
        }
        return groups;
    }

    private static Optional<SubtitleStream> first(List<SubtitleStream> streams, boolean skipSdh, boolean anyLanguage, String language) {
        List<SubtitleStream> matched = new ArrayList<>();
        for (SubtitleStream stream : streams) {
            if (skipSdh && isSdh(stream)) {
                continue;
            }
            if (!anyLanguage && !language.equals(languageOf(stream.language()))) {
                continue;
            }
            matched.add(stream);
        }
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(preferDefault(matched).get(0));
    }

    private static List<SubtitleStream> preferDefault(List<SubtitleStream> streams) {
        List<SubtitleStream> ordered = new ArrayList<>();
        for (SubtitleStream stream : streams) {
            if (stream.defaultTrack()) {
                ordered.add(stream);
            }
        }
        for (SubtitleStream stream : streams) {
            if (!stream.defaultTrack()) {
                ordered.add(stream);
            }
        }
        return ordered;
    }

    static boolean isSdh(SubtitleStream stream) {
        if (stream.hearingImpaired()) {
            return true;
        }
        return stream.title() != null && stream.title().toLowerCase(Locale.ROOT).contains("sdh");
    }

    static boolean isTextCodec(String codec) {
        String value = norm(codec);
        return value.equals("subrip")
                || value.equals("ass")
                || value.equals("ssa")
                || value.equals("text")
                || value.equals("mov_text")
                || value.equals("webvtt");
    }

    static boolean isChinese(String language) {
        String value = norm(language);
        return value.equals("zh")
                || value.startsWith("zh-")
                || value.equals("chi")
                || value.equals("zho")
                || value.equals("cn")
                || value.equals("chinese");
    }

    private static String languageOf(String language) {
        String value = norm(language);
        if (value.equals("ja") || value.equals("jpn") || value.equals("jp") || value.equals("japanese")) {
            return "ja";
        }
        if (value.equals("en") || value.equals("eng") || value.equals("english")) {
            return "en";
        }
        return value;
    }

    private static String norm(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static final java.util.Set<String> EFFORTS = java.util.Set.of(
            "low", "medium", "high", "xhigh");

    private static final Pattern SSH_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$");
    private static final Pattern MODEL_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/@-]{0,199}$");
    private static final Pattern SAFE_PATH = Pattern.compile("^~?(?:/[A-Za-z0-9._-]+)+$");
    private static final String ORCA_RUNNER = loadOrcaRunner();

    private static String loadOrcaRunner() {
        try (java.io.InputStream input = SubtitleTranslateSupport.class.getResourceAsStream("/orca/subtitle_runner.py")) {
            if (input == null) throw new IllegalStateException("字幕 Orca 执行脚本未打包");
            String runner = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            try (java.io.InputStream rpc = SubtitleTranslateSupport.class.getResourceAsStream("/orca/catalog_rpc.cjs")) {
                if (rpc == null) throw new IllegalStateException("字幕 Orca 模型查询脚本未打包");
                return runner.replace("__ANI_RSS_CATALOG_RPC_B64__", java.util.Base64.getEncoder().encodeToString(rpc.readAllBytes()));
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("字幕 Orca 执行脚本无法读取", error);
        }
    }
    private static final String ORCA_RUNNER_B64 = java.util.Base64.getEncoder()
            .encodeToString(ORCA_RUNNER.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    public static boolean usesSsh(String backend) {
        return "ssh".equalsIgnoreCase(StrUtil.blankToDefault(backend, "").trim());
    }

    public static boolean allowedEffort(String effort) {
        String value = StrUtil.blankToDefault(effort, "").trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() || value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}");
    }

    public static boolean allowedAgent(String agent) {
        return StrUtil.blankToDefault(agent, "").trim().matches("[a-z][a-z0-9_-]{0,63}");
    }

    public static boolean allowedModel(String model) {
        return MODEL_NAME.matcher(StrUtil.blankToDefault(model, "").trim()).matches();
    }

    public static boolean allowedOrcaBin(String bin) {
        String value = StrUtil.blankToDefault(bin, "").trim();
        if (value.isEmpty()) {
            return true;
        }
        return safePath(value);
    }

    public static boolean allowedWorktree(String worktree) {
        String value = StrUtil.blankToDefault(worktree, "").trim();
        if (value.isEmpty() || value.length() > 240 || value.contains("..")) {
            return false;
        }
        if ("active".equals(value) || "current".equals(value)) {
            return true;
        }
        int colon = value.indexOf(':');
        if (colon < 1) {
            return false;
        }
        String kind = value.substring(0, colon);
        String rest = value.substring(colon + 1).trim();
        if (!java.util.Set.of("path", "name", "id", "branch", "issue").contains(kind) || rest.isEmpty()) {
            return false;
        }
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c < 0x20 || "\"'`$|&;<>\\".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }


    public static Optional<List<String>> sshCommand(String host, int port, String user, String keyPath) {
        String hostValue = StrUtil.blankToDefault(host, "").trim();
        String userValue = StrUtil.blankToDefault(user, "").trim();
        String keyValue = StrUtil.blankToDefault(keyPath, "").trim();
        if (!SSH_NAME.matcher(hostValue).matches() || hostValue.contains("..")) {
            return Optional.empty();
        }
        if (!SSH_NAME.matcher(userValue).matches()) {
            return Optional.empty();
        }
        if (port < 1 || port > 65535 || !safePath(keyValue)) {
            return Optional.empty();
        }
        String remote = "python3 -c \"import base64; exec(base64.b64decode('" + ORCA_RUNNER_B64 + "').decode())\"";
        return Optional.of(List.of(
                "ssh", "-T",
                "-i", keyValue,
                "-p", Integer.toString(port),
                "-o", "BatchMode=yes",
                "-o", "IdentitiesOnly=yes",
                "-o", "StrictHostKeyChecking=accept-new",
                "-o", "UserKnownHostsFile=" + keyValue + ".known_hosts",
                "-o", "ConnectTimeout=20",
                "-o", "ServerAliveInterval=30",
                "-o", "ServerAliveCountMax=4",
                userValue + "@" + hostValue,
                remote
        ));
    }

    public static String modelText(String stdout) {
        String extracted = grokText(stdout);
        return extracted != null ? extracted : stdout;
    }

    public static String grokText(String stdout) {
        if (StrUtil.isBlank(stdout)) {
            return null;
        }
        int start = stdout.indexOf('{');
        int end = stdout.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(stdout.substring(start, end + 1))
                    .getAsJsonObject();
            if (!root.has("text") || root.get("text").isJsonNull()) {
                return null;
            }
            String text = root.get("text").getAsString();
            return StrUtil.isBlank(text) ? null : text;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean safePath(String value) {
        return value != null && !value.contains("..") && SAFE_PATH.matcher(value).matches();
    }
}

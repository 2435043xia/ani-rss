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
    private static final Pattern MODEL_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$");
    private static final Pattern SAFE_PATH = Pattern.compile("^~?(?:/[A-Za-z0-9._-]+)+$");
    private static final String ORCA_RUNNER = """
            import json, os, re, signal, subprocess, sys, time, tempfile
            req = json.load(sys.stdin)
            orca = os.path.expanduser(str(req.get("orca") or "/usr/local/bin/orca"))
            worktree = str(req.get("worktree") or "").strip()
            root = None
            state = {"coord": None, "dispatch": None, "run": None, "task": None, "agent": None}

            def run_json(args, timeout):
                cmd = [orca]
                cmd.extend(args)
                cmd.append("--json")
                try:
                    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout)
                except Exception:
                    return None
                if not proc.stdout:
                    return None
                try:
                    payload = json.loads(proc.stdout.decode("utf-8", "replace"))
                except Exception:
                    return None
                if proc.returncode != 0 or not payload.get("ok", True):
                    return None
                result = payload.get("result")
                return result if isinstance(result, dict) else None

            def close_coord():
                handle = state["coord"]
                state["coord"] = None
                if not handle:
                    return
                try:
                    subprocess.run(
                        [orca, "terminal", "close", "--terminal", handle, "--json"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20,
                    )
                except Exception:
                    pass

            def stop_worker():
                dispatch = state["dispatch"]
                if not dispatch:
                    return
                try:
                    subprocess.run(
                        [orca, "orchestration", "worker-stop", "--dispatch", dispatch, "--json"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30,
                    )
                except Exception:
                    pass

            def release_worker():
                dispatch = state["dispatch"]
                if not dispatch:
                    return
                try:
                    subprocess.run(
                        [orca, "orchestration", "worker-release", "--dispatch", dispatch, "--json"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30,
                    )
                except Exception:
                    pass

            def cleanup_root():
                if not root:
                    return
                try:
                    for name in os.listdir(root):
                        try:
                            os.remove(os.path.join(root, name))
                        except OSError:
                            pass
                    os.rmdir(root)
                except OSError:
                    pass

            def close_agent():
                handle = state.get("agent")
                state["agent"] = None
                if not handle or handle == state.get("coord"):
                    return
                try:
                    subprocess.run(
                        [orca, "terminal", "close", "--terminal", handle, "--json"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20,
                    )
                except Exception:
                    pass

            def finish_task(status):
                task_id = state.get("task")
                coord = state.get("coord")
                if not task_id or not coord:
                    return
                args = ["orchestration", "task-update", "--id", task_id, "--status", status, "--from", coord]
                if state.get("run"):
                    args.extend(["--run", state["run"]])
                run_json(args, 20)

            def close_worker(settled):
                if settled:
                    release_worker()
                else:
                    stop_worker()
                close_agent()

            def stop(signum, frame):
                stop_worker()
                close_agent()
                finish_task("failed")
                close_coord()
                cleanup_root()
                sys.exit(1)

            signal.signal(signal.SIGTERM, stop)
            signal.signal(signal.SIGHUP, stop)

            def worker_state(show):
                worker = show.get("worker") if isinstance(show, dict) else None
                if not isinstance(worker, dict):
                    return ""
                return str(worker.get("state") or "")

            def read_out(path):
                try:
                    return open(path, encoding="utf-8").read()
                except Exception:
                    return ""

            def useful(text):
                return re.search(r"(?m)^\\d+\\|", text or "") is not None

            try:
                if not worktree:
                    sys.exit(1)
                created = run_json(["terminal", "create", "--title", "ani-rss-subtitle", "--worktree", worktree], 40)
                handle = ""
                if created:
                    term = created.get("terminal")
                    if isinstance(term, dict):
                        handle = str(term.get("handle") or "")
                if re.fullmatch(r"term_[A-Za-z0-9-]{8,80}", handle) is None:
                    sys.exit(1)
                state["coord"] = handle
                created_run = run_json(["orchestration", "run-create", "--from", handle, "--objective", "ani-rss subtitle"], 30)
                run_obj = (created_run or {}).get("run")
                run_id = ""
                if isinstance(run_obj, dict):
                    run_id = str(run_obj.get("id") or run_obj.get("runId") or "")
                elif isinstance(run_obj, str):
                    run_id = run_obj
                if not run_id and created_run:
                    run_id = str(created_run.get("runId") or created_run.get("id") or "")
                if re.fullmatch(r"run_[A-Za-z0-9]{6,80}", run_id) is None:
                    sys.exit(1)
                state["run"] = run_id
                root = tempfile.mkdtemp(prefix="ani-rss-orca-")
                out_path = os.path.join(root, "out.txt")
                spec = "\\n".join([
                    "你在给 ani-rss 翻译字幕。不要搜索网页，不要阅读或修改工作区，不要调用 Unity、MCP 或其他工具。",
                    "只把完整译文写入这个绝对路径，覆盖已有文件：",
                    out_path,
                    "文件里只能有译文。格式沿用下面的说明，每一条都是序号|译文，并以术语块结尾。",
                    "写完文件后只发送一次 worker_done，正文只写 wrote。不要搜索网页，不要读仓库。被拒绝时不要改文件，也不要重试。",
                    "",
                    str(req.get("system") or ""),
                    "",
                    str(req.get("prompt") or ""),
                ])
                started = run_json([
                    "orchestration", "worker-start",
                    "--from", handle,
                    "--agent", "grok",
                    "--worktree", worktree,
                    "--task-title", "ani-rss-subtitle",
                    "--spec", spec,
                    "--timeout-ms", "60000",
                ], 70)
                dispatch = str((started or {}).get("dispatchId") or "")
                if re.fullmatch(r"ctx_[A-Za-z0-9]{6,80}", dispatch) is None:
                    sys.exit(1)
                state["dispatch"] = dispatch
                task_id = str((started or {}).get("taskId") or "")
                if re.fullmatch(r"task_[A-Za-z0-9]{6,80}", task_id):
                    state["task"] = task_id
                deadline = time.time() + 1200
                state_name = ""
                ready = ""
                last_size = -1
                stable = 0
                extended = False
                while time.time() < deadline:
                    show = run_json(["orchestration", "worker-show", "--dispatch", dispatch], 20)
                    state_name = worker_state(show)
                    terminal = show.get("terminal") if isinstance(show, dict) else None
                    if isinstance(terminal, dict):
                        agent = str(terminal.get("handle") or "")
                        if re.fullmatch(r"term_[A-Za-z0-9-]{8,80}", agent):
                            state["agent"] = agent
                    text = read_out(out_path)
                    if "</terminology>" in text and useful(text):
                        size = len(text)
                        if size == last_size:
                            stable += 1
                        else:
                            stable = 0
                            last_size = size
                        if stable >= 2 and not ready:
                            ready = text
                            if not extended:
                                deadline = max(deadline, time.time() + 90)
                                extended = True
                    elif not ready:
                        stable = 0
                    if state_name in ("succeeded", "completed"):
                        if not ready and useful(text):
                            ready = text
                        break
                    if state_name in ("failed", "stopped"):
                        if not ready and useful(text):
                            ready = text
                        break
                    time.sleep(2)
                if not ready and state_name in ("succeeded", "completed"):
                    checked = run_json(["orchestration", "check", "--terminal", handle, "--run", run_id, "--all"], 20)
                    messages = (checked or {}).get("messages") or []
                    for message in messages:
                        if not isinstance(message, dict) or message.get("type") != "worker_done":
                            continue
                        body = str(message.get("body") or "")
                        if useful(body):
                            ready = body
                            break
                settled = state_name in ("succeeded", "completed", "failed", "stopped")
                close_worker(settled)
                if not ready:
                    finish_task("failed")
                    sys.exit(1)
                finish_task("completed")
                sys.stdout.write(ready)
            finally:
                close_coord()
                cleanup_root()
            """;
    private static final String ORCA_RUNNER_B64 = java.util.Base64.getEncoder()
            .encodeToString(ORCA_RUNNER.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    public static boolean usesSsh(String backend) {
        return "ssh".equalsIgnoreCase(StrUtil.blankToDefault(backend, "").trim());
    }

    public static boolean allowedEffort(String effort) {
        String value = StrUtil.blankToDefault(effort, "").trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() || EFFORTS.contains(value);
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

package ani.rss.service;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.MikanInfo;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 添加订阅时的 Bangumi 评分。
 * 公开接口是 api.bgm.tv：/calendar 只用来按名字对上条目，评分来自 /v0/subjects/{id} 和 /v0/search/subjects。
 */
@Slf4j
@Service
public class BgmScoreService {

    private static final long TTL_MILLIS = TimeUnit.HOURS.toMillis(6);
    private static final long CALENDAR_RETRY_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final int PARALLEL = 4;

    private final Object lock = new Object();
    private boolean loaded;
    private boolean dirty;
    private final Map<String, Entry> subjects = new HashMap<>();
    private final Map<String, Entry> queries = new HashMap<>();
    private CalendarIndex calendar = CalendarIndex.empty();
    private long calendarAt;
    private boolean calendarOk;

    public Map<String, SubjectScore> scores(Collection<String> subjectIds) {
        ensureLoaded();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (subjectIds != null) {
            for (String subjectId : subjectIds) {
                if (StrUtil.isNotBlank(subjectId)) {
                    ids.add(subjectId.trim());
                }
            }
        }
        Map<String, SubjectScore> found = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            SubjectScore cached = cachedSubject(id);
            if (cached != null) {
                found.put(id, cached);
            } else {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            log.info("从 Bangumi 拉取 {} 个条目评分", missing.size());
            found.putAll(fetchSubjects(missing));
            flush();
        }
        return found;
    }

    public void applyToMikan(List<MikanInfo> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        CalendarIndex index = calendar();
        List<MikanInfo> matched = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<MikanInfo> unmatched = new ArrayList<>();
        for (MikanInfo item : items) {
            String id = index.match(item.getTitle());
            if (StrUtil.isBlank(id)) {
                unmatched.add(item);
                continue;
            }
            item.setBgmId(id);
            matched.add(item);
            ids.add(id);
        }
        Map<String, SubjectScore> scoreMap = scores(ids);
        for (MikanInfo item : matched) {
            SubjectScore score = scoreMap.get(item.getBgmId());
            if (score != null) {
                item.setScore(score.getScore());
            }
        }
        fillBySearch(unmatched);
    }

    private void fillBySearch(List<MikanInfo> items) {
        if (items.isEmpty()) {
            return;
        }
        log.info("按标题搜索 {} 个 Mikan 条目的 Bangumi 评分", items.size());
        Semaphore semaphore = new Semaphore(PARALLEL);
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (MikanInfo item : items) {
            tasks.add(CompletableFuture.runAsync(() -> {
                semaphore.acquireUninterruptibly();
                try {
                    SubjectScore score = search(item.getTitle());
                    if (score != null && StrUtil.isNotBlank(score.getId())) {
                        item.setBgmId(score.getId());
                        item.setScore(score.getScore());
                    }
                } finally {
                    semaphore.release();
                }
            }));
        }
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        flush();
    }

    private Map<String, SubjectScore> fetchSubjects(List<String> ids) {
        Semaphore semaphore = new Semaphore(PARALLEL);
        Map<String, SubjectScore> found = new HashMap<>();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (String id : ids) {
            tasks.add(CompletableFuture.runAsync(() -> {
                semaphore.acquireUninterruptibly();
                try {
                    SubjectScore score = requestSubject(id);
                    if (score == null) {
                        return;
                    }
                    synchronized (found) {
                        found.put(id, score);
                    }
                    rememberSubject(score);
                } finally {
                    semaphore.release();
                }
            }));
        }
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        return found;
    }

    private SubjectScore search(String title) {
        String key = normalize(title);
        if (key.length() < 2) {
            return null;
        }
        SubjectScore cached = cachedQuery(key);
        if (cached != null) {
            return StrUtil.isBlank(cached.getId()) ? null : cached;
        }
        try {
            SubjectScore found = requestSearch(title);
            rememberQuery(key, found);
            if (found != null) {
                rememberSubject(found);
            }
            return found;
        } catch (Exception e) {
            log.warn("搜索 Bangumi 评分失败 {}: {}", title, e.getMessage());
            return null;
        }
    }

    private SubjectScore requestSubject(String id) {
        String url = api() + "/v0/subjects/" + id;
        try {
            return HttpReq.get(url).thenFunction(res -> {
                if (!res.isOk()) {
                    log.warn("Bangumi 条目 {} 返回 {}", id, res.getStatus());
                    return null;
                }
                JsonObject obj = GsonStatic.fromJson(res.body(), JsonObject.class);
                SubjectScore score = fromSubject(obj);
                if (score == null) {
                    return null;
                }
                if (StrUtil.isBlank(score.getId())) {
                    score.setId(id);
                }
                return score;
            });
        } catch (Exception e) {
            log.warn("拉取 Bangumi 条目 {} 失败: {}", id, e.getMessage());
            return null;
        }
    }

    private SubjectScore requestSearch(String title) {
        String url = api() + "/v0/search/subjects?limit=5";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyword", stripBrackets(title));
        body.put("sort", "match");
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("type", List.of(2));
        body.put("filter", filter);

        return HttpReq.post(url)
                .body(GsonStatic.toJson(body))
                .header(Header.CONTENT_TYPE, "application/json")
                .thenFunction(res -> {
                    if (!res.isOk()) {
                        throw new IllegalStateException("HTTP " + res.getStatus());
                    }
                    JsonObject obj = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonArray data = obj.getAsJsonArray("data");
                    if (data == null) {
                        return null;
                    }
                    return pick(data, title);
                });
    }

    private SubjectScore pick(JsonArray data, String title) {
        String key = normalize(title);
        SubjectScore loose = null;
        int looseLen = -1;
        for (JsonElement element : data) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            SubjectScore score = fromSubject(obj);
            if (score == null || StrUtil.isBlank(score.getId())) {
                continue;
            }
            String nameCn = normalize(text(obj.get("name_cn")));
            String name = normalize(text(obj.get("name")));
            if (key.equals(nameCn) || key.equals(name)) {
                return score;
            }
            int len = containsLength(key, nameCn, name);
            if (len > looseLen) {
                loose = score;
                looseLen = len;
            }
        }
        return looseLen >= 4 ? loose : null;
    }

    private CalendarIndex calendar() {
        synchronized (lock) {
            long ttl = calendarOk ? TTL_MILLIS : CALENDAR_RETRY_MILLIS;
            if (calendarAt > 0 && System.currentTimeMillis() - calendarAt < ttl) {
                return calendar;
            }
        }
        CalendarIndex loadedIndex = CalendarIndex.empty();
        boolean ok = false;
        try {
            loadedIndex = HttpReq.get(api() + "/calendar").thenFunction(res -> {
                if (!res.isOk()) {
                    throw new IllegalStateException("HTTP " + res.getStatus());
                }
                JsonArray days = GsonStatic.fromJson(res.body(), JsonArray.class);
                return CalendarIndex.from(days);
            });
            ok = true;
        } catch (Exception e) {
            log.warn("拉取 Bangumi 每日放送失败: {}", e.getMessage());
        }
        synchronized (lock) {
            calendar = loadedIndex;
            calendarAt = System.currentTimeMillis();
            calendarOk = ok;
            return calendar;
        }
    }

    private SubjectScore fromSubject(JsonObject obj) {
        if (obj == null) {
            return null;
        }
        String id = jsonId(obj.get("id"));
        double score = 0;
        JsonElement rating = obj.get("rating");
        if (rating != null && rating.isJsonObject()) {
            JsonElement value = rating.getAsJsonObject().get("score");
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                score = value.getAsDouble();
            }
        }
        String cover = "";
        JsonElement images = obj.get("images");
        if (images != null && images.isJsonObject()) {
            JsonObject imageObj = images.getAsJsonObject();
            cover = text(imageObj.get("small"));
            if (StrUtil.isBlank(cover)) {
                cover = text(imageObj.get("common"));
            }
        }
        return new SubjectScore()
                .setId(id)
                .setScore(score)
                .setCover(cover);
    }

    private SubjectScore cachedSubject(String id) {
        synchronized (lock) {
            Entry entry = subjects.get(id);
            if (entry == null || expired(entry.at)) {
                return null;
            }
            return new SubjectScore().setId(id).setScore(entry.score).setCover(entry.cover);
        }
    }

    private SubjectScore cachedQuery(String key) {
        synchronized (lock) {
            Entry entry = queries.get(key);
            if (entry == null || expired(entry.at)) {
                return null;
            }
            return new SubjectScore().setId(entry.id).setScore(entry.score).setCover(entry.cover);
        }
    }

    private void rememberSubject(SubjectScore score) {
        if (score == null || StrUtil.isBlank(score.getId())) {
            return;
        }
        synchronized (lock) {
            subjects.put(score.getId(), new Entry(score.getId(), score.getScore(), score.getCover(), System.currentTimeMillis()));
            dirty = true;
        }
    }

    private void rememberQuery(String key, SubjectScore score) {
        synchronized (lock) {
            if (score == null) {
                queries.put(key, new Entry("", 0, "", System.currentTimeMillis()));
            } else {
                queries.put(key, new Entry(StrUtil.blankToDefault(score.getId(), ""), score.getScore(), score.getCover(), System.currentTimeMillis()));
            }
            dirty = true;
        }
    }

    private void flush() {
        synchronized (lock) {
            if (!dirty) {
                return;
            }
            try {
                JsonObject root = new JsonObject();
                root.add("subjects", entriesJson(subjects));
                root.add("queries", entriesJson(queries));
                File file = cacheFile();
                FileUtil.mkdir(file.getParentFile());
                FileUtil.writeUtf8String(GsonStatic.toJson(root), file);
                dirty = false;
            } catch (Exception e) {
                log.warn("写入 Bangumi 评分缓存失败: {}", e.getMessage());
            }
        }
    }

    private void ensureLoaded() {
        synchronized (lock) {
            if (loaded) {
                return;
            }
            loaded = true;
            File file = cacheFile();
            if (!file.isFile()) {
                return;
            }
            try {
                JsonObject root = GsonStatic.fromJson(FileUtil.readUtf8String(file), JsonObject.class);
                readEntries(root.getAsJsonObject("subjects"), subjects);
                readEntries(root.getAsJsonObject("queries"), queries);
            } catch (Exception e) {
                log.warn("读取 Bangumi 评分缓存失败: {}", e.getMessage());
            }
        }
    }

    private JsonObject entriesJson(Map<String, Entry> source) {
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, Entry> item : source.entrySet()) {
            Entry entry = item.getValue();
            if (expired(entry.at)) {
                continue;
            }
            JsonObject value = new JsonObject();
            value.addProperty("id", entry.id);
            value.addProperty("score", entry.score);
            value.addProperty("cover", entry.cover);
            value.addProperty("at", entry.at);
            obj.add(item.getKey(), value);
        }
        return obj;
    }

    private void readEntries(JsonObject obj, Map<String, Entry> target) {
        if (obj == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> item : obj.entrySet()) {
            if (!item.getValue().isJsonObject()) {
                continue;
            }
            JsonObject value = item.getValue().getAsJsonObject();
            long at = value.has("at") ? value.get("at").getAsLong() : 0;
            if (expired(at)) {
                continue;
            }
            target.put(item.getKey(), new Entry(
                    text(value.get("id")),
                    value.has("score") ? value.get("score").getAsDouble() : 0,
                    text(value.get("cover")),
                    at
            ));
        }
    }

    private File cacheFile() {
        return new File(ConfigUtil.getConfigDir(), "bgm-score.json");
    }

    private boolean expired(long at) {
        return at <= 0 || System.currentTimeMillis() - at >= TTL_MILLIS;
    }

    private String api() {
        String api = ConfigUtil.CONFIG.getBgmApi();
        api = StrUtil.blankToDefault(api, "https://api.bgm.tv");
        return StrUtil.removeSuffix(api, "/");
    }

    static String normalize(String raw) {
        String stripped = stripBrackets(raw);
        if (StrUtil.isBlank(stripped)) {
            return "";
        }
        return stripped.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", "");
    }

    private static String stripBrackets(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[（(][^）)]*[）)]", "").trim();
    }

    private static int containsLength(String key, String nameCn, String name) {
        int len = -1;
        if (nameCn.length() >= 4 && (key.contains(nameCn) || nameCn.contains(key))) {
            len = nameCn.length();
        }
        if (name.length() >= 4 && (key.contains(name) || name.contains(key))) {
            len = Math.max(len, name.length());
        }
        return len;
    }

    private static String jsonId(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        if (element.getAsJsonPrimitive().isNumber()) {
            return element.getAsJsonPrimitive().getAsNumber().toString();
        }
        return element.getAsString();
    }

    private static String text(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        return element.getAsString();
    }

    @Data
    @Accessors(chain = true)
    public static class SubjectScore {
        private String id;
        private double score;
        private String cover;
    }

    private record Entry(String id, double score, String cover, long at) {
    }

    private static final class CalendarIndex {
        private final Map<String, String> names = new HashMap<>();
        private final Map<String, Boolean> ambiguous = new HashMap<>();

        private static CalendarIndex empty() {
            return new CalendarIndex();
        }

        private static CalendarIndex from(JsonArray days) {
            CalendarIndex index = new CalendarIndex();
            if (days == null) {
                return index;
            }
            for (JsonElement dayElement : days) {
                if (dayElement == null || !dayElement.isJsonObject()) {
                    continue;
                }
                JsonArray items = dayElement.getAsJsonObject().getAsJsonArray("items");
                if (items == null) {
                    continue;
                }
                for (JsonElement itemElement : items) {
                    if (itemElement == null || !itemElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject item = itemElement.getAsJsonObject();
                    String id = jsonId(item.get("id"));
                    if (StrUtil.isBlank(id)) {
                        continue;
                    }
                    index.putName(text(item.get("name_cn")), id);
                    index.putName(text(item.get("name")), id);
                }
            }
            return index;
        }

        private void putName(String name, String id) {
            String key = normalize(name);
            if (key.length() < 2) {
                return;
            }
            String existing = names.get(key);
            if (existing == null) {
                if (!ambiguous.containsKey(key)) {
                    names.put(key, id);
                }
                return;
            }
            if (!existing.equals(id)) {
                names.remove(key);
                ambiguous.put(key, Boolean.TRUE);
            }
        }

        private String match(String title) {
            String key = normalize(title);
            if (key.length() < 2) {
                return "";
            }
            String exact = names.get(key);
            if (StrUtil.isNotBlank(exact)) {
                return exact;
            }
            String bestId = "";
            int bestLen = -1;
            boolean tie = false;
            for (Map.Entry<String, String> item : names.entrySet()) {
                String name = item.getKey();
                if (name.length() < 4) {
                    continue;
                }
                if (!key.contains(name) && !name.contains(key)) {
                    continue;
                }
                if (name.length() > bestLen) {
                    bestLen = name.length();
                    bestId = item.getValue();
                    tie = false;
                } else if (name.length() == bestLen && !item.getValue().equals(bestId)) {
                    tie = true;
                }
            }
            return tie ? "" : bestId;
        }
    }
}

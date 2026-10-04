package ani.rss.service;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.EmbyViews;
import ani.rss.entity.NotificationConfig;
import ani.rss.entity.PathReplaceRule;
import ani.rss.entity.vo.EmbyPathChoice;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.PathReplaceUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class EmbyRefreshService {
    public static final class SeriesListing {
        private final List<EmbyPathChoice> hits;
        private final String error;

        public SeriesListing(List<EmbyPathChoice> hits, String error) {
            this.hits = hits == null ? List.of() : List.copyOf(hits);
            this.error = error == null ? "" : error;
        }

        public List<EmbyPathChoice> hits() {
            return hits;
        }

        public String error() {
            return error;
        }
    }

    /**
     * 扫描媒体库
     */
    public void refresh(NotificationConfig notificationConfig) {
        List<String> viewIds = notificationConfig.getEmbyRefreshViewIds();
        List<EmbyViews> views = getViews(notificationConfig);

        List<String> newViewIds = views
                .stream()
                .filter(view -> viewIds.contains(view.getId()))
                .peek(view -> refresh(view, notificationConfig))
                .map(EmbyViews::getId)
                .toList();

        notificationConfig.setEmbyRefreshViewIds(newViewIds);
    }

    /**
     * 扫描媒体库
     *
     * @param embyViews 媒体库
     */
    public void refresh(EmbyViews embyViews, NotificationConfig notificationConfig) {
        String embyHost = notificationConfig.getEmbyHost();
        String embyApiKey = notificationConfig.getEmbyApiKey();

        Assert.notBlank(embyHost, "embyHost 为空");
        Assert.notBlank(embyApiKey, "embyApiKey 为空");

        String s = "Recursive=true&ImageRefreshMode=Default&MetadataRefreshMode=Default&ReplaceAllImages=false&ReplaceAllMetadata=false";

        String id = embyViews.getId();
        HttpReq.post(embyHost + "/emby/Items/" + id + "/Refresh?" + s)
                .header("X-Emby-Token", embyApiKey)
                .then(res -> {
                    if (res.isOk()) {
                        log.info("正在扫描 Emby 媒体库 id: {} name: {}", embyViews.getId(), embyViews.getName());
                    } else {
                        int status = res.getStatus();
                        log.error("扫描 Emby 媒体库出错 id: {} name: {} status: {}", embyViews.getId(), embyViews.getName(), status);
                    }
                });
    }

    /**
     * 获取媒体库列表
     *
     * @return 媒体库列表
     */
    public List<EmbyViews> getViews(NotificationConfig notificationConfig) {
        String embyHost = notificationConfig.getEmbyHost();
        String embyApiKey = notificationConfig.getEmbyApiKey();

        Assert.notBlank(embyHost, "embyHost 为空");
        Assert.notBlank(embyApiKey, "embyApiKey 为空");

        JsonArray items = HttpReq.get(embyHost + "/Library/MediaFolders")
                .header("X-Emby-Token", embyApiKey)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject body = GsonStatic.fromJson(res.body(), JsonObject.class);
                    return body.get("Items").getAsJsonArray();
                });
        return GsonStatic.fromJsonList(items, EmbyViews.class);
    }

    public static List<EmbyPathChoice> parseSeries(String embyHost, String json, List<PathReplaceRule> rules) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        JsonObject body;
        try {
            body = GsonStatic.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            return List.of();
        }
        if (body == null || !body.has("Items") || !body.get("Items").isJsonArray()) {
            return List.of();
        }
        List<EmbyPathChoice> hits = new ArrayList<>();
        for (JsonElement element : body.getAsJsonArray("Items")) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String path = text(item, "Path");
            if (StrUtil.isBlank(path)) {
                continue;
            }
            String tmdbId = "";
            if (item.has("ProviderIds") && item.get("ProviderIds").isJsonObject()) {
                tmdbId = text(item.getAsJsonObject("ProviderIds"), "Tmdb");
            }
            hits.add(new EmbyPathChoice()
                    .setEmbyHost(embyHost)
                    .setSeriesName(text(item, "Name"))
                    .setTmdbId(tmdbId)
                    .setOriginalPath(path)
                    .setReplacedPath(PathReplaceUtil.apply(path, rules)));
        }
        return hits;
    }

    public SeriesListing listSeries(NotificationConfig config) {
        if (config == null || StrUtil.isBlank(config.getEmbyHost()) || StrUtil.isBlank(config.getEmbyApiKey())) {
            return new SeriesListing(List.of(), "");
        }
        List<String> viewIds = config.getEmbyRefreshViewIds();
        if (viewIds == null || viewIds.isEmpty()) {
            return new SeriesListing(List.of(), "");
        }
        List<EmbyPathChoice> hits = new ArrayList<>();
        StringBuilder errors = new StringBuilder();
        for (String viewId : viewIds) {
            if (StrUtil.isBlank(viewId)) {
                continue;
            }
            String url = config.getEmbyHost() + "/emby/Items?ParentId=" + viewId
                    + "&Recursive=true&IncludeItemTypes=Series&Fields=ProviderIds,Path";
            try {
                String body = HttpReq.get(url)
                        .header("X-Emby-Token", config.getEmbyApiKey())
                        .thenFunction(res -> {
                            if (!res.isOk()) {
                                throw new IllegalStateException(String.valueOf(res.getStatus()));
                            }
                            return res.body();
                        });
                hits.addAll(parseSeries(config.getEmbyHost(), body, config.getEmbyPathReplaceRules()));
            } catch (Exception e) {
                if (!errors.isEmpty()) {
                    errors.append("\n");
                }
                String detail = e.getMessage();
                if (StrUtil.isBlank(detail)) {
                    detail = e.getClass().getSimpleName();
                }
                errors.append(config.getEmbyHost()).append(" ").append(detail);
            }
        }
        return new SeriesListing(hits, errors.toString());
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }
}

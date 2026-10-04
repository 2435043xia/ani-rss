package ani.rss.util.other;

import ani.rss.cache.CacheUtils;
import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.CustomTmdbConfig;
import ani.rss.util.basic.HttpReq;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import wushuo.tmdb.api.TmdbUtil;
import wushuo.tmdb.api.entity.*;
import wushuo.tmdb.api.enums.TmdbTypeEnum;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * tmdb封装
 */
@Slf4j
public class TmdbUtils {
    private static final Config CONFIG = ConfigUtil.CONFIG;
    public final static TmdbUtil TMDB_UTIL = new TmdbUtil(new CustomTmdbConfig());

    /**
     * 获取番剧在tmdb的名称
     *
     * @param ani 订阅
     * @return 名称
     */
    public static String getFinalName(Ani ani) {
        Boolean ova = ani.getOva();
        String name = ani.getTitle();
        name = RenameUtil.renameDel(name, false);
        if (StrUtil.isBlank(name)) {
            return "";
        }

        Optional<Tmdb> tmdb;
        try {
            if (ova) {
                tmdb = getTmdbMovie(name);
            } else {
                tmdb = getTmdbTv(name);
            }
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
            return "";
        }

        tmdb.ifPresent(ani::setTmdb);

        if (tmdb.isEmpty()) {
            return "";
        }
        return getFinalName(tmdb.get());
    }

    /**
     * 获取添加tmdbid与年份后的名称
     *
     * @param tmdb tmdb
     * @return 名称
     */
    public static String getFinalName(Tmdb tmdb) {
        boolean tmdbOriginalNameEnable = CONFIG.getTmdbOriginalName();

        String themoviedbName = tmdbOriginalNameEnable ?
                tmdb.getOriginalName() :
                tmdb.getName();
        return getFinalName(themoviedbName, tmdb);
    }

    /**
     * 获取添加tmdbid与年份后的名称
     *
     * @param title 标题
     * @param tmdb  tmdb
     * @return 名称
     */
    public static String getFinalName(String title, Tmdb tmdb) {
        if (Objects.isNull(tmdb)) {
            return title;
        }
        boolean titleYear = CONFIG.getTitleYear();
        if (titleYear) {
            title = RenameUtil.renameDel(title, false);
            title = StrFormatter.format("{} ({})", title, DateUtil.year(tmdb.getDate()));
        }

        boolean tmdbId = CONFIG.getTmdbId();
        boolean tmdbIdPlexMode = CONFIG.getTmdbIdPlexMode();
        if (tmdbId) {
            if (tmdbIdPlexMode) {
                title = StrFormatter.format("{} {tmdb-{}}", title, tmdb.getId());
            } else {
                title = StrFormatter.format("{} [tmdbid={}]", title, tmdb.getId());
            }
        }
        return RenameUtil.getName(title);
    }

    /**
     * 获取所有标题
     *
     * @param tmdb     tmdb
     * @param tmdbType 类型
     * @return 标题列表
     */
    public static List<TmdbTitle> getTitles(Tmdb tmdb, TmdbTypeEnum tmdbType) {
        return TMDB_UTIL.getTitles(tmdb, tmdbType);
    }

    /**
     * 获取罗马音
     *
     * @param tmdb     tmdb
     * @param tmdbType 类型
     */
    public static void getRomaji(Tmdb tmdb, TmdbTypeEnum tmdbType) {
        if (Objects.isNull(tmdb)) {
            return;
        }

        Boolean tmdbRomaji = CONFIG.getTmdbRomaji();
        if (!tmdbRomaji) {
            // 未开启罗马音
            return;
        }

        List<TmdbTitle> titles = getTitles(tmdb, tmdbType);

        for (TmdbTitle tmdbTitle : titles) {
            String iso31661 = tmdbTitle.getIso31661();
            String type = tmdbTitle.getType();
            String title = tmdbTitle.getTitle();
            if (!iso31661.equals("JP")) {
                continue;
            }
            if (List.of("romaji", "romanization").contains(type.toLowerCase())) {
                title = RenameUtil.getName(title);
                // 判断为罗马音
                tmdb.setName(title);
                return;
            }
        }

        String romaji = "";
        try {
            romaji = AniListUtil.getRomaji(tmdb.getName());
            romaji = RenameUtil.getName(romaji);
        } catch (Exception e) {
            log.error("通过AniList获取罗马音失败");
            log.error(e.getMessage(), e);
        }
        if (StrUtil.isNotBlank(romaji)) {
            tmdb.setName(romaji);
        }
    }

    /**
     * 根据标题获得tmdb
     *
     * @param titleName 标题名
     * @return tmdb
     */
    public static Optional<Tmdb> getTmdbMovie(String titleName) {
        Optional<Tmdb> tmdb = getTmdb(titleName, TmdbTypeEnum.MOVIE);
        tmdb.ifPresent(it -> getRomaji(it, TmdbTypeEnum.MOVIE));
        return tmdb;
    }

    /**
     * 根据标题获得tmdb
     *
     * @param titleName 标题名
     * @return tmdb
     */
    public static Optional<Tmdb> getTmdbTv(String titleName) {
        Optional<Tmdb> tmdb = getTmdb(titleName, TmdbTypeEnum.TV);
        tmdb.ifPresent(it -> getRomaji(it, TmdbTypeEnum.TV));
        return tmdb;
    }

    /**
     * 根据名称获取tmdb信息
     *
     * @param titleName 标题名
     * @param tmdbType  类型
     * @return tmdb
     */
    public static Optional<Tmdb> getTmdb(String titleName, TmdbTypeEnum tmdbType) {
        return TMDB_UTIL.getTmdb(titleName, tmdbType);
    }

    /**
     * 获取季信息
     *
     * @param tmdb   tmdb
     * @param season 季
     * @return tmdb
     */
    public static Optional<TmdbSeason> getTmdbSeason(Tmdb tmdb, Integer season) {
        return TMDB_UTIL.getTmdbSeason(tmdb, season);
    }

    /**
     * 获取每集的标题
     *
     * @param ani 订阅
     * @return 每集的标题
     */
    public static Map<Integer, TmdbEpisode> getEpisodeTitleMap(Ani ani) {
        Map<Integer, TmdbEpisode> episodeTitleMap = new HashMap<>();

        if (Objects.isNull(ani)) {
            return episodeTitleMap;
        }

        Tmdb tmdb = ani.getTmdb();
        Integer season = ani.getSeason();
        Boolean ova = ani.getOva();

        if (ova) {
            return episodeTitleMap;
        }

        if (Objects.isNull(tmdb)) {
            return episodeTitleMap;
        }

        String tmdbId = tmdb.getId();
        String tmdbGroupId = tmdb.getTmdbGroupId();

        String key = StrFormatter.format("TMDB_getEpisodeTitleMap:{}:{}:{}", tmdbId, tmdbGroupId, season);

        Map<Integer, TmdbEpisode> cacheMap = CacheUtils.get(key);
        if (Objects.nonNull(cacheMap)) {
            return cacheMap;
        }

        episodeTitleMap = getEpisodeTitleMap(tmdb, season);
        if (episodeTitleMap.isEmpty()) {
            CacheUtils.put(key, episodeTitleMap, 1000 * 10);
        } else {
            CacheUtils.put(key, episodeTitleMap, TimeUnit.MINUTES.toMillis(5));
        }
        return episodeTitleMap;
    }

    /**
     * 获取每集的标题
     *
     * @param tmdb   tmdb
     * @param season 季
     * @return 每集的标题
     */
    public static Map<Integer, TmdbEpisode> getEpisodeTitleMap(Tmdb tmdb, Integer season) {
        return TMDB_UTIL.getEpisodeTitleMap(tmdb, season);
    }

    /**
     * 获取剧集组
     *
     * @param tmdb tmdb
     * @return 剧集组列表
     */
    public static List<TmdbGroup> getTmdbGroup(Tmdb tmdb) {
        return TMDB_UTIL.getTmdbGroup(tmdb);
    }

    /**
     * 获取图片
     *
     * @param tmdb     tmdb
     * @param tmdbType 类型
     * @return 图片信息
     */
    public static TmdbImages getTmdbImages(Tmdb tmdb, TmdbTypeEnum tmdbType) {
        return TMDB_UTIL.getTmdbImages(tmdb, tmdbType);
    }

    public static Optional<Tmdb> getTmdb(Tmdb tmdb, TmdbTypeEnum tmdbTypeEnum) {
        return TMDB_UTIL.getTmdb(tmdb, tmdbTypeEnum);
    }

    public static final class AlignEpisodes {
        private final List<TmdbEpisode> episodes;
        private final boolean incomplete;

        private AlignEpisodes(List<TmdbEpisode> episodes, boolean incomplete) {
            this.episodes = episodes;
            this.incomplete = incomplete;
        }

        public List<TmdbEpisode> episodes() {
            return episodes;
        }

        public boolean incomplete() {
            return incomplete;
        }
    }

    public static AlignEpisodes seasonList(boolean anySeasonFailed, List<TmdbEpisode> collected) {
        if (anySeasonFailed) {
            return new AlignEpisodes(List.of(), true);
        }
        return new AlignEpisodes(collected == null ? List.of() : collected, false);
    }

    /**
     * 对齐用的全部剧集。剧集组保留原始 season_number，不用会改写季号的季查询。
     * 任一季请求失败时不返回已读到的其它季。
     */
    public static AlignEpisodes listEpisodesForAlign(Tmdb tmdb) {
        if (tmdb == null || StrUtil.isBlank(tmdb.getId())) {
            return seasonList(false, List.of());
        }
        CustomTmdbConfig config = new CustomTmdbConfig();
        String api = StrUtil.removeSuffix(StrUtil.blankToDefault(config.getTmdbApi(), "https://api.themoviedb.org"), "/");
        String key = config.getTmdbApiKey();
        String language = config.getTmdbLanguage();
        try {
            if (StrUtil.isNotBlank(tmdb.getTmdbGroupId())) {
                return seasonList(false, episodesFromGroup(api, key, language, tmdb.getTmdbGroupId()));
            }
            return episodesFromSeasons(api, key, language, tmdb);
        } catch (Exception e) {
            log.error("TMDB 对齐读取剧集失败");
            return seasonList(false, List.of());
        }
    }

    private static List<TmdbEpisode> episodesFromGroup(String api, String key, String language, String groupId) {
        JsonObject body = getJson(api + "/3/tv/episode_group/" + groupId, key, language);
        if (body == null || !body.has("groups") || !body.get("groups").isJsonArray()) {
            return List.of();
        }
        List<TmdbEpisode> episodes = new ArrayList<>();
        for (JsonElement group : body.getAsJsonArray("groups")) {
            if (group == null || !group.isJsonObject()) {
                continue;
            }
            JsonObject groupObject = group.getAsJsonObject();
            if (!groupObject.has("episodes") || !groupObject.get("episodes").isJsonArray()) {
                continue;
            }
            for (JsonElement element : groupObject.getAsJsonArray("episodes")) {
                if (element != null && element.isJsonObject()) {
                    TmdbEpisode episode = toEpisode(element.getAsJsonObject());
                    if (episode != null) {
                        episodes.add(episode);
                    }
                }
            }
        }
        return episodes;
    }

    private static AlignEpisodes episodesFromSeasons(String api, String key, String language, Tmdb tmdb) {
        JsonObject body = getJson(api + "/3/tv/" + tmdb.getId(), key, language);
        if (body == null || !body.has("seasons") || !body.get("seasons").isJsonArray()) {
            return seasonList(false, List.of());
        }
        List<TmdbEpisode> episodes = new ArrayList<>();
        boolean failed = false;
        for (JsonElement element : body.getAsJsonArray("seasons")) {
            if (element == null || !element.isJsonObject() || !element.getAsJsonObject().has("season_number")) {
                continue;
            }
            int seasonNumber = element.getAsJsonObject().get("season_number").getAsInt();
            if (seasonNumber == 0) {
                continue;
            }
            try {
                Optional<TmdbSeason> season = getTmdbSeason(tmdb, seasonNumber);
                if (season.isPresent() && season.get().getEpisodes() != null) {
                    episodes.addAll(season.get().getEpisodes());
                }
            } catch (Exception ignored) {
                failed = true;
            }
        }
        if (failed) {
            log.error("TMDB 对齐读取剧集失败");
        }
        return seasonList(failed, episodes);
    }

    private static JsonObject getJson(String url, String key, String language) {
        return HttpReq.get(url)
                .timeout(5000)
                .form("api_key", key)
                .form("language", StrUtil.blankToDefault(language, ""))
                .thenFunction(res -> {
                    if (!res.isOk()) {
                        throw new IllegalStateException(String.valueOf(res.getStatus()));
                    }
                    return GsonStatic.fromJson(res.body(), JsonObject.class);
                });
    }

    private static TmdbEpisode toEpisode(JsonObject object) {
        Integer seasonNumber = integer(object, "season_number");
        Integer episodeNumber = integer(object, "episode_number");
        if (seasonNumber == null || episodeNumber == null) {
            return null;
        }
        TmdbEpisode episode = new TmdbEpisode()
                .setSeasonNumber(seasonNumber)
                .setEpisodeNumber(episodeNumber)
                .setName(text(object, "name"))
                .setOrder(integer(object, "order"));
        String airDate = text(object, "air_date");
        if (StrUtil.isNotBlank(airDate)) {
            try {
                episode.setAirDate(Date.from(LocalDate.parse(airDate)
                        .atStartOfDay(EpisodeAlignUtil.ZONE)
                        .toInstant()));
            } catch (Exception ignored) {
                episode.setAirDate(null);
            }
        }
        return episode;
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static Integer integer(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsInt();
        } catch (Exception e) {
            return null;
        }
    }
}

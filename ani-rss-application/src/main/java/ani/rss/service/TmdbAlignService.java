package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.NotificationConfig;
import ani.rss.entity.vo.EmbyPathChoice;
import ani.rss.entity.vo.TmdbAlignChoice;
import ani.rss.entity.vo.TmdbAlignPreview;
import ani.rss.enums.NotificationTypeEnum;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.EmbyMatchUtil;
import ani.rss.util.other.EpisodeAlignUtil;
import ani.rss.util.other.ItemsUtil;
import ani.rss.util.other.RenameUtil;
import ani.rss.util.other.SeasonConflictUtil;
import ani.rss.util.other.TmdbUtils;
import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Service;
import wushuo.tmdb.api.entity.Tmdb;
import wushuo.tmdb.api.entity.TmdbEpisode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class TmdbAlignService {
    private final EmbyRefreshService embyRefreshService;

    public TmdbAlignService(EmbyRefreshService embyRefreshService) {
        this.embyRefreshService = embyRefreshService;
    }

    public TmdbAlignPreview load(Ani ani) {
        if (ani == null || Boolean.TRUE.equals(ani.getOva())) {
            return assemble(ani, List.of(), List.of(), List.of(), List.of(), "", "");
        }
        Tmdb tmdb = ani.getTmdb();
        if (tmdb == null || StrUtil.isBlank(tmdb.getId())) {
            String groupId = tmdb == null ? null : tmdb.getTmdbGroupId();
            Optional<Tmdb> found = TmdbUtils.getTmdbTv(RenameUtil.renameDel(StrUtil.blankToDefault(ani.getTitle(), ""), false));
            if (found.isEmpty()) {
                TmdbAlignPreview missed = new TmdbAlignPreview();
                missed.setBlockReason("TMDB 对不上");
                missed.setCanConfirm(false);
                return missed;
            }
            tmdb = found.get();
            if (StrUtil.isNotBlank(groupId)) {
                tmdb.setTmdbGroupId(groupId);
            }
            ani.setTmdb(tmdb);
        }
        ani.setThemoviedbName(TmdbUtils.getFinalName(tmdb));
        List<Item> items = ItemsUtil.getItems(ani, ani.getUrl(), ani.getSubgroup());
        List<TmdbEpisode> episodes = TmdbUtils.listEpisodesForAlign(tmdb);
        List<EmbyPathChoice> hits = new ArrayList<>();
        StringBuilder errors = new StringBuilder();
        List<NotificationConfig> configs = ConfigUtil.CONFIG.getNotificationConfigList();
        if (configs != null) {
            for (NotificationConfig config : configs) {
                if (config == null || config.getNotificationType() != NotificationTypeEnum.EMBY_REFRESH) {
                    continue;
                }
                if (StrUtil.isBlank(config.getEmbyHost()) || StrUtil.isBlank(config.getEmbyApiKey())) {
                    continue;
                }
                hits.addAll(embyRefreshService.listSeries(config));
                String error = embyRefreshService.listSeriesError(config);
                if (StrUtil.isNotBlank(error)) {
                    if (!errors.isEmpty()) {
                        errors.append("\n");
                    }
                    errors.append(error);
                }
            }
        }
        List<EmbyPathChoice> selected = EmbyMatchUtil.select(hits, tmdb.getId(), tmdb.getName(), tmdb.getOriginalName());
        Config config = ConfigUtil.CONFIG;
        return assemble(ani, AniUtil.ANI_LIST, items, episodes, selected, errors.toString(), config.getDownloadPathTemplate());
    }

    public static TmdbAlignPreview assemble(Ani ani, List<Ani> subscriptions, List<Item> masterItems,
                                            List<TmdbEpisode> episodes, List<EmbyPathChoice> embyHits,
                                            String embyMessage, String globalDownloadTemplate) {
        TmdbAlignPreview preview = new TmdbAlignPreview();
        preview.setEmbyMessage(StrUtil.blankToDefault(embyMessage, ""));
        if (ani == null) {
            preview.setBlockReason("TMDB 对不上");
            preview.setCanConfirm(false);
            return preview;
        }
        if (Boolean.TRUE.equals(ani.getOva())) {
            preview.setBlockReason("剧场版不按季对齐");
            preview.setCanConfirm(false);
            return preview;
        }
        preview.setCurrentSeason(ani.getSeason());
        preview.setCurrentOffset(ani.getOffset());
        String tmdbName = StrUtil.blankToDefault(ani.getThemoviedbName(), "");
        preview.setThemoviedbName(tmdbName);
        preview.setTmdb(ani.getTmdb());
        preview.setRenameTemplate(EpisodeAlignUtil.renameTemplate(tmdbName));

        Optional<Item> earliest = EpisodeAlignUtil.earliest(masterItems);
        if (earliest.isEmpty()) {
            preview.setBlockReason("主 RSS 没有带日期和集号的条目");
            preview.setCanConfirm(false);
            return preview;
        }
        Item item = earliest.get();
        preview.setRssTitle(item.getTitle());
        preview.setRssEpisode(item.getEpisode());
        preview.setRssPubDate(day(item.getPubDate()));
        preview.setRssNote(EpisodeAlignUtil.sameDayNote(masterItems, item));

        List<TmdbEpisode> closest = EpisodeAlignUtil.closest(episodes, item.getPubDate());
        if (closest.isEmpty()) {
            preview.setBlockReason("TMDB 没有对上的集");
            preview.setCanConfirm(false);
            return preview;
        }
        List<TmdbAlignChoice> choices = new ArrayList<>();
        for (TmdbEpisode episode : closest) {
            int season = episode.getSeasonNumber();
            int episodeNumber = episode.getEpisodeNumber();
            int offset = EpisodeAlignUtil.offset(episodeNumber, item.getEpisode());
            Optional<Ani> conflict = SeasonConflictUtil.find(subscriptions, ani, season);
            choices.add(new TmdbAlignChoice()
                    .setSeasonNumber(season)
                    .setEpisodeNumber(episodeNumber)
                    .setAirDate(day(episode.getAirDate()))
                    .setEpisodeName(episode.getName())
                    .setNewSeason(season)
                    .setNewOffset(offset)
                    .setSampleFileName(EpisodeAlignUtil.sampleName(tmdbName, season, episodeNumber))
                    .setFallbackPath(fallbackPath(globalDownloadTemplate, tmdbName, season))
                    .setSeasonConflict(conflict.isPresent())
                    .setConflictWith(conflict.map(Ani::getTitle).orElse("")));
        }
        preview.setEpisodeChoices(choices);
        preview.setEpisodeChoiceRequired(choices.size() > 1);

        List<EmbyPathChoice> embyChoices = embyHits == null ? List.of() : embyHits;
        preview.setEmbyChoices(embyChoices);
        preview.setEmbyChoiceRequired(embyChoices.size() > 1);

        boolean oneEpisode = choices.size() == 1 && !Boolean.TRUE.equals(choices.get(0).getSeasonConflict());
        boolean embyOk = embyChoices.size() <= 1;
        preview.setCanConfirm(StrUtil.isBlank(preview.getBlockReason()) && oneEpisode && embyOk);
        return preview;
    }

    public static void apply(Ani ani, TmdbAlignPreview preview, int episodeIndex, Integer embyIndex) {
        TmdbAlignChoice choice = preview.getEpisodeChoices().get(episodeIndex);
        ani.setSeason(choice.getNewSeason());
        ani.setOffset(choice.getNewOffset());
        ani.setCustomRenameTemplateEnable(true);
        ani.setCustomRenameTemplate(preview.getRenameTemplate());
        ani.setCustomDownloadPath(true);
        if (embyIndex != null) {
            ani.setCustomDownloadPathTemplate(preview.getEmbyChoices().get(embyIndex).getReplacedPath());
        } else {
            ani.setCustomDownloadPathTemplate(choice.getFallbackPath());
        }
        ani.setThemoviedbName(preview.getThemoviedbName());
        boolean idBlank = ani.getTmdb() == null || StrUtil.isBlank(ani.getTmdb().getId());
        if (idBlank && preview.getTmdb() != null) {
            String groupId = ani.getTmdb() == null ? null : ani.getTmdb().getTmdbGroupId();
            ani.setTmdb(preview.getTmdb());
            if (StrUtil.isNotBlank(groupId)) {
                ani.getTmdb().setTmdbGroupId(groupId);
            }
        }
    }

    private static String fallbackPath(String template, String tmdbName, int season) {
        String path = StrUtil.blankToDefault(template, "");
        path = path.replace("${title}", StrUtil.blankToDefault(tmdbName, ""));
        path = path.replace("${seasonFormat}", String.format("%02d", season));
        path = path.replace("${season}", String.valueOf(season));
        return path;
    }

    private static String day(Date date) {
        if (date == null) {
            return "";
        }
        LocalDate localDate = date.toInstant().atZone(EpisodeAlignUtil.ZONE).toLocalDate();
        return localDate.toString();
    }
}

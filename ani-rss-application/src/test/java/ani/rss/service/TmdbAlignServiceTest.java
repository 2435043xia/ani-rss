package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.StandbyRss;
import ani.rss.entity.vo.EmbyPathChoice;
import ani.rss.entity.vo.TmdbAlignChoice;
import ani.rss.entity.vo.TmdbAlignPreview;
import ani.rss.util.other.EpisodeAlignUtil;
import ani.rss.util.other.TmdbUtils;
import org.junit.jupiter.api.Test;
import wushuo.tmdb.api.entity.Tmdb;
import wushuo.tmdb.api.entity.TmdbEpisode;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TmdbAlignServiceTest {

    private static final String TEMPLATE = "/downloads/${title}/Season ${seasonFormat}";

    private static Date at(int year, int month, int day) {
        return Date.from(LocalDate.of(year, month, day)
                .atStartOfDay(EpisodeAlignUtil.ZONE)
                .toInstant());
    }

    private static Ani show() {
        return new Ani()
                .setId("1")
                .setTitle("RSS标题")
                .setThemoviedbName("葬送的芙莉莲")
                .setSeason(3)
                .setOffset(-24)
                .setOva(false);
    }

    private static Item rss(double episode, Date pubDate) {
        return new Item()
                .setTitle("rss")
                .setEpisode(episode)
                .setPubDate(pubDate);
    }

    private static TmdbEpisode episode(int season, int number, Date airDate) {
        return new TmdbEpisode()
                .setSeasonNumber(season)
                .setEpisodeNumber(number)
                .setAirDate(airDate)
                .setName("ep" + number);
    }

    private static TmdbAlignPreview align(Ani ani, List<Ani> subscriptions, List<Item> items,
                                          List<TmdbEpisode> episodes, List<EmbyPathChoice> emby,
                                          String embyMessage) {
        return TmdbAlignService.assemble(ani, subscriptions, items, episodes, emby, embyMessage, TEMPLATE);
    }

    @Test
    void rss25MatchesTmdbSeason1Episode25() {
        Date day = at(2024, 1, 10);
        Ani ani = show();
        TmdbAlignPreview preview = align(ani, List.of(ani), List.of(rss(25, day)),
                List.of(episode(1, 25, day)), List.of(), "");
        assertTrue(preview.getCanConfirm());
        assertEquals(1, preview.getEpisodeChoices().size());
        TmdbAlignChoice choice = preview.getEpisodeChoices().get(0);
        assertEquals(1, choice.getNewSeason());
        assertEquals(0, choice.getNewOffset());
        assertEquals("葬送的芙莉莲 S01E25", choice.getSampleFileName());
        assertEquals("/downloads/葬送的芙莉莲/Season 01", choice.getFallbackPath());
        assertEquals("葬送的芙莉莲 S${seasonFormat}E${episodeFormat}", preview.getRenameTemplate());
    }

    @Test
    void twoClosestEpisodesRequireAChoice() {
        Date day = at(2024, 1, 10);
        Ani ani = show();
        TmdbAlignPreview preview = align(ani, List.of(ani), List.of(rss(25, day)), List.of(
                episode(1, 25, at(2024, 1, 9)),
                episode(1, 26, at(2024, 1, 11))
        ), List.of(), "");
        assertTrue(preview.getEpisodeChoiceRequired());
        assertFalse(preview.getCanConfirm());
        assertEquals(2, preview.getEpisodeChoices().size());
    }

    @Test
    void sameTitleAndSeasonBlocksConfirm() {
        Date day = at(2024, 1, 10);
        Ani current = show().setTitle("葬送的芙莉莲");
        Ani other = new Ani().setId("2").setTitle("葬送的芙莉莲").setSeason(1);
        TmdbAlignPreview preview = align(current, List.of(current, other), List.of(rss(25, day)),
                List.of(episode(1, 25, day)), List.of(), "");
        TmdbAlignChoice choice = preview.getEpisodeChoices().get(0);
        assertTrue(choice.getSeasonConflict());
        assertEquals("葬送的芙莉莲", choice.getConflictWith());
        assertFalse(preview.getCanConfirm());
    }

    @Test
    void embyFailureStillAllowsConfirm() {
        Date day = at(2024, 1, 10);
        Ani ani = show();
        TmdbAlignPreview preview = align(ani, List.of(ani), List.of(rss(25, day)),
                List.of(episode(1, 25, day)), List.of(), "http://emby 500");
        assertTrue(preview.getEmbyChoices().isEmpty());
        assertTrue(preview.getEmbyMessage().contains("500"));
        assertTrue(preview.getCanConfirm());
    }

    @Test
    void ovaIsBlocked() {
        TmdbAlignPreview preview = align(show().setOva(true), List.of(), List.of(rss(1, at(2024, 1, 10))),
                List.of(episode(1, 1, at(2024, 1, 10))), List.of(), "");
        assertEquals("剧场版不按季对齐", preview.getBlockReason());
        assertFalse(preview.getCanConfirm());
    }

    @Test
    void applyWritesSeasonOffsetTemplateAndEmbyPathWithoutTouchingTitle() {
        Date day = at(2024, 1, 10);
        Ani ani = show()
                .setTmdb(new Tmdb().setId("").setTmdbGroupId("keep"))
                .setStandbyRssList(List.of(new StandbyRss().setOffset(12)));
        TmdbAlignPreview preview = align(ani, List.of(ani), List.of(rss(25, day)),
                List.of(episode(1, 25, day)),
                List.of(new EmbyPathChoice().setReplacedPath("/mnt/smb/downloads/葬送的芙莉莲")),
                "");
        preview.setTmdb(new Tmdb().setId("99").setTmdbGroupId("other"));
        preview.setThemoviedbName("葬送的芙莉莲");

        TmdbAlignService.apply(ani, preview, 0, 0);

        assertEquals("RSS标题", ani.getTitle());
        assertEquals(12, ani.getStandbyRssList().get(0).getOffset());
        assertEquals(1, ani.getSeason());
        assertEquals(0, ani.getOffset());
        assertTrue(ani.getCustomRenameTemplateEnable());
        assertEquals("葬送的芙莉莲 S${seasonFormat}E${episodeFormat}", ani.getCustomRenameTemplate());
        assertTrue(ani.getCustomDownloadPath());
        assertEquals("/mnt/smb/downloads/葬送的芙莉莲/Season 01", ani.getCustomDownloadPathTemplate());
        assertEquals("葬送的芙莉莲", ani.getThemoviedbName());
        assertEquals("99", ani.getTmdb().getId());
        assertEquals("keep", ani.getTmdb().getTmdbGroupId());
    }

    @Test
    void rssNumbersSubtractsTheTitleEpisode() {
        // 标题集号 25，订阅偏移 -24 时，getItems 会把条目集号存成 1。
        // 用 1 去减会得到偏移 24，重命名后的集号不再是 TMDB 的 25。
        assertEquals(24, EpisodeAlignUtil.offset(25, 1));
        assertEquals(0, EpisodeAlignUtil.offset(25, 25));

        Ani ani = new Ani()
                .setTitle("葬送的芙莉莲")
                .setUrl("https://rss.example/show")
                .setSubgroup("字幕组")
                .setOffset(-24)
                .setMatch(List.of("1080"))
                .setExclude(List.of("720"))
                .setGlobalExclude(true);
        Ani probe = TmdbAlignService.rssNumbers(ani);
        assertNotSame(ani, probe);
        assertEquals(0, probe.getOffset());
        assertEquals(-24, ani.getOffset());
        assertEquals("葬送的芙莉莲", probe.getTitle());
        assertEquals("https://rss.example/show", probe.getUrl());
        assertEquals("字幕组", probe.getSubgroup());
        assertEquals(List.of("1080"), probe.getMatch());
        assertEquals(List.of("720"), probe.getExclude());
        assertEquals(Boolean.TRUE, probe.getGlobalExclude());
    }

    @Test
    void incompleteSeasonListBlocksConfirm() {
        Date day = at(2024, 1, 10);
        Ani ani = show();
        TmdbAlignPreview assembled = align(ani, List.of(ani), List.of(rss(25, day)),
                List.of(episode(1, 25, day)), List.of(), "");
        assertTrue(assembled.getCanConfirm());
        TmdbAlignPreview blocked = TmdbAlignService.finish(true, assembled);
        assertEquals("TMDB 季列表不完整", blocked.getBlockReason());
        assertFalse(blocked.getCanConfirm());
        assertTrue(blocked.getEpisodeChoices().isEmpty());
        assertTrue(TmdbAlignService.finish(false, assembled).getCanConfirm());
    }

    @Test
    void failedSeasonDropsPartialEpisodes() {
        TmdbEpisode kept = episode(1, 25, at(2024, 1, 10));
        TmdbUtils.AlignEpisodes failed = TmdbUtils.seasonList(true, List.of(kept));
        assertTrue(failed.incomplete());
        assertTrue(failed.episodes().isEmpty());
        TmdbUtils.AlignEpisodes complete = TmdbUtils.seasonList(false, List.of(kept));
        assertFalse(complete.incomplete());
        assertEquals(1, complete.episodes().size());
    }

    @Test
    void embySeriesFolderGainsTheTemplateSeason() {
        Date day = at(2024, 1, 10);
        String series = "/downloads/anirss/anime/野生的大魔王出现了！ (2025)";
        TmdbAlignPreview preview = align(show(), List.of(show()), List.of(rss(1, day)),
                List.of(episode(2, 1, day)),
                List.of(new EmbyPathChoice().setReplacedPath(series)),
                "");
        TmdbAlignChoice choice = preview.getEpisodeChoices().get(0);
        assertEquals("/Season 02", choice.getSeasonFolder());
        assertEquals(series + "/Season 02", TmdbAlignService.withSeasonFolder(series, choice.getSeasonFolder()));
        assertEquals(series + "/Season 1", TmdbAlignService.withSeasonFolder(series + "/Season 1", "/Season 02"));
    }
}

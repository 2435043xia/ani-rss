package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.StandbyRss;
import ani.rss.entity.vo.EmbyPathChoice;
import ani.rss.entity.vo.TmdbAlignChoice;
import ani.rss.entity.vo.TmdbAlignPreview;
import ani.rss.util.other.EpisodeAlignUtil;
import org.junit.jupiter.api.Test;
import wushuo.tmdb.api.entity.Tmdb;
import wushuo.tmdb.api.entity.TmdbEpisode;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals("/mnt/smb/downloads/葬送的芙莉莲", ani.getCustomDownloadPathTemplate());
        assertEquals("葬送的芙莉莲", ani.getThemoviedbName());
        assertEquals("99", ani.getTmdb().getId());
        assertEquals("keep", ani.getTmdb().getTmdbGroupId());
    }
}

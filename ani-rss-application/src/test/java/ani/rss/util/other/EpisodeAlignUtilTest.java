package ani.rss.util.other;

import ani.rss.entity.Item;
import org.junit.jupiter.api.Test;
import wushuo.tmdb.api.entity.TmdbEpisode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpisodeAlignUtilTest {

    private static Date at(int year, int month, int day, int hour) {
        return Date.from(LocalDate.of(year, month, day)
                .atTime(hour, 0)
                .atZone(EpisodeAlignUtil.ZONE)
                .toInstant());
    }

    private static Item item(String title, double episode, Date pubDate) {
        Item item = new Item();
        item.setTitle(title);
        item.setEpisode(episode);
        item.setPubDate(pubDate);
        return item;
    }

    private static TmdbEpisode episode(int season, int number, Date airDate) {
        TmdbEpisode episode = new TmdbEpisode();
        episode.setSeasonNumber(season);
        episode.setEpisodeNumber(number);
        episode.setAirDate(airDate);
        episode.setName("ep" + number);
        return episode;
    }

    @Test
    void earlierCalendarDayWins() {
        Item later = item("later", 1, at(2024, 1, 2, 1));
        Item earlier = item("earlier", 9, at(2024, 1, 1, 23));
        Item chosen = EpisodeAlignUtil.earliest(List.of(later, earlier)).orElseThrow();
        assertEquals("earlier", chosen.getTitle());
    }

    @Test
    void sameCalendarDayUsesSmallerEpisodeAndIgnoresClock() {
        Item high = item("high", 3, at(2024, 1, 1, 1));
        Item low = item("low", 1, at(2024, 1, 1, 23));
        Item chosen = EpisodeAlignUtil.earliest(List.of(high, low)).orElseThrow();
        assertEquals("low", chosen.getTitle());
        assertEquals("发布日期相同，使用集号更小的一条",
                EpisodeAlignUtil.sameDayNote(List.of(high, low), chosen));
    }

    @Test
    void skipsItemsWithoutDateOrEpisode() {
        Item blank = item("blank", 1, null);
        assertTrue(EpisodeAlignUtil.earliest(List.of(blank)).isEmpty());
    }

    @Test
    void closerAirDateWinsAndTieReturnsBoth() {
        Date rss = at(2024, 1, 10, 15);
        TmdbEpisode near = episode(1, 25, at(2024, 1, 9, 0));
        TmdbEpisode far = episode(1, 1, at(2024, 1, 1, 0));
        List<TmdbEpisode> one = EpisodeAlignUtil.closest(List.of(near, far), rss);
        assertEquals(1, one.size());
        assertEquals(25, one.get(0).getEpisodeNumber());

        TmdbEpisode other = episode(2, 3, at(2024, 1, 11, 0));
        List<TmdbEpisode> tied = EpisodeAlignUtil.closest(List.of(near, other), rss);
        assertEquals(2, tied.size());
    }

    @Test
    void sameShanghaiDayHasDistanceZeroAcrossClockTimes() {
        Date rss = at(2024, 1, 10, 18);
        Date midnight = Date.from(LocalDate.of(2024, 1, 10)
                .atStartOfDay(ZoneId.of("UTC"))
                .toInstant());
        TmdbEpisode sameDay = episode(1, 25, midnight);
        TmdbEpisode nextDay = episode(1, 26, at(2024, 1, 11, 0));
        List<TmdbEpisode> found = EpisodeAlignUtil.closest(List.of(nextDay, sameDay), rss);
        assertEquals(1, found.size());
        assertEquals(25, found.get(0).getEpisodeNumber());
    }

    @Test
    void offsetUsesIntegerPartTowardZero() {
        assertEquals(0, EpisodeAlignUtil.offset(25, 25));
        assertEquals(-24, EpisodeAlignUtil.offset(1, 25));
        assertEquals(0, EpisodeAlignUtil.offset(25, 25.5));
    }

    @Test
    void namesUseTmdbTitleNotSubscriptionTitle() {
        assertEquals("葬送的芙莉莲 S01E25", EpisodeAlignUtil.sampleName("葬送的芙莉莲", 1, 25));
        assertEquals("葬送的芙莉莲 S${seasonFormat}E${episodeFormat}",
                EpisodeAlignUtil.renameTemplate("葬送的芙莉莲"));
    }
}

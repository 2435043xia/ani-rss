package ani.rss.util.other;

import ani.rss.entity.Item;
import wushuo.tmdb.api.entity.TmdbEpisode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public final class EpisodeAlignUtil {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private EpisodeAlignUtil() {
    }

    public static Optional<Item> earliest(List<Item> items) {
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        return items.stream()
                .filter(item -> item.getPubDate() != null && item.getEpisode() != null)
                .min(Comparator
                        .comparing((Item item) -> toLocalDate(item.getPubDate()))
                        .thenComparing(Item::getEpisode));
    }

    public static String sameDayNote(List<Item> items, Item chosen) {
        if (items == null || chosen == null || chosen.getPubDate() == null) {
            return "";
        }
        LocalDate day = toLocalDate(chosen.getPubDate());
        long count = items.stream()
                .filter(item -> item.getPubDate() != null && item.getEpisode() != null)
                .filter(item -> toLocalDate(item.getPubDate()).equals(day))
                .count();
        if (count > 1) {
            return "发布日期相同，使用集号更小的一条";
        }
        return "";
    }

    public static List<TmdbEpisode> closest(List<TmdbEpisode> episodes, Date pubDate) {
        if (episodes == null || pubDate == null) {
            return List.of();
        }
        LocalDate rssDay = toLocalDate(pubDate);
        long best = Long.MAX_VALUE;
        List<TmdbEpisode> tied = new ArrayList<>();
        for (TmdbEpisode episode : episodes) {
            if (episode.getAirDate() == null || episode.getSeasonNumber() == null
                    || episode.getEpisodeNumber() == null) {
                continue;
            }
            long days = Math.abs(ChronoUnit.DAYS.between(rssDay, toLocalDate(episode.getAirDate())));
            if (days < best) {
                best = days;
                tied = new ArrayList<>();
                tied.add(episode);
            } else if (days == best) {
                tied.add(episode);
            }
        }
        return tied;
    }

    public static int offset(int tmdbEpisodeNumber, double rssEpisode) {
        return tmdbEpisodeNumber - (int) rssEpisode;
    }

    public static String sampleName(String tmdbName, int season, int episodeNumber) {
        return String.format("%s S%02dE%02d", tmdbName, season, episodeNumber);
    }

    public static String renameTemplate(String tmdbName) {
        return tmdbName + " S${seasonFormat}E${episodeFormat}";
    }

    private static LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(ZONE).toLocalDate();
    }
}

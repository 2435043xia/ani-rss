package ani.rss.util.other;

import ani.rss.entity.vo.EmbyPathChoice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbyMatchUtilTest {

    private static EmbyPathChoice hit(String host, String name, String tmdbId) {
        return new EmbyPathChoice()
                .setEmbyHost(host)
                .setSeriesName(name)
                .setTmdbId(tmdbId)
                .setOriginalPath("/emby/" + name)
                .setReplacedPath("/ani/" + name);
    }

    @Test
    void idMatchDropsNameMatches() {
        List<EmbyPathChoice> selected = EmbyMatchUtil.select(List.of(
                hit("a", "Other", "99"),
                hit("b", "Show", "123"),
                hit("c", "Show", "")
        ), "123", "Show", "Show");
        assertEquals(1, selected.size());
        assertEquals("b", selected.get(0).getEmbyHost());
    }

    @Test
    void nameMatchUsesRawNameOrOriginalNameOnly() {
        List<EmbyPathChoice> selected = EmbyMatchUtil.select(List.of(
                hit("a", "Show", ""),
                hit("b", "Show [tmdbid=1]", ""),
                hit("c", "ショー", "")
        ), "", "Show", "ショー");
        assertEquals(List.of("a", "c"), selected.stream().map(EmbyPathChoice::getEmbyHost).toList());
    }
}

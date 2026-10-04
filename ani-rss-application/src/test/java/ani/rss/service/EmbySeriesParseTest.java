package ani.rss.service;

import ani.rss.entity.PathReplaceRule;
import ani.rss.entity.vo.EmbyPathChoice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbySeriesParseTest {

    @Test
    void readsTmdbIdAndReplacesPath() {
        String json = """
                {"Items":[
                  {"Name":"葬送的芙莉莲","Path":"/volume1/anime/葬送的芙莉莲/Season 1","ProviderIds":{"Tmdb":"123"}},
                  {"Name":"无路径","Path":"","ProviderIds":{"Tmdb":"9"}}
                ]}
                """;
        List<EmbyPathChoice> hits = EmbyRefreshService.parseSeries(
                "http://emby",
                json,
                List.of(new PathReplaceRule().setFrom("/volume1/anime").setTo("/mnt/smb/downloads"))
        );
        assertEquals(1, hits.size());
        assertEquals("123", hits.get(0).getTmdbId());
        assertEquals("http://emby", hits.get(0).getEmbyHost());
        assertEquals("/volume1/anime/葬送的芙莉莲/Season 1", hits.get(0).getOriginalPath());
        assertEquals("/mnt/smb/downloads/葬送的芙莉莲/Season 1", hits.get(0).getReplacedPath());
    }
}

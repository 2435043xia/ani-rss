package ani.rss.util.other;

import ani.rss.entity.PathReplaceRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathReplaceUtilTest {

    private static PathReplaceRule rule(String from, String to) {
        return new PathReplaceRule().setFrom(from).setTo(to);
    }

    @Test
    void longestPrefixWinsOnce() {
        String path = "/volume1/anime/葬送的芙莉莲/Season 1";
        String replaced = PathReplaceUtil.apply(path, List.of(
                rule("/volume1", "/mnt"),
                rule("/volume1/anime/", "/mnt/smb/downloads")
        ));
        assertEquals("/mnt/smb/downloads/葬送的芙莉莲/Season 1", replaced);
    }

    @Test
    void trailingSlashDoesNotMatter() {
        String path = "/volume1/anime/Show";
        assertEquals("/mnt/smb/downloads/Show",
                PathReplaceUtil.apply(path, List.of(rule("/volume1/anime/", "/mnt/smb/downloads/"))));
    }

    @Test
    void noMatchReturnsOriginal() {
        String path = "/other/Show";
        assertEquals(path, PathReplaceUtil.apply(path, List.of(rule("/volume1/anime", "/mnt"))));
    }

    @Test
    void doesNotReplaceAPrefixFoundOnlyInTheMiddle() {
        String path = "/volume1/anime/Show";
        assertEquals(path, PathReplaceUtil.apply(path, List.of(rule("/anime", "/mnt"))));
    }

    @Test
    void doesNotChainWhenReplacementStillContainsThePrefix() {
        String once = PathReplaceUtil.apply("/volume1/anime",
                List.of(rule("/volume1", "/volume1/mnt")));
        assertEquals("/volume1/mnt/anime", once);
    }

    @Test
    void blankFromIsIgnored() {
        String path = "/volume1/anime/Show";
        assertEquals(path, PathReplaceUtil.apply(path, List.of(rule("  ", "/mnt"), rule(null, "/mnt"))));
        assertEquals(path, PathReplaceUtil.apply(path, null));
    }
}

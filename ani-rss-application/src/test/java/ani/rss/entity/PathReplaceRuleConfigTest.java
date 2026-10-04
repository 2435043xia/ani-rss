package ani.rss.entity;

import ani.rss.commons.GsonStatic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PathReplaceRuleConfigTest {

    @Test
    void rulesRoundTripAndMissingFieldStaysNull() {
        NotificationConfig config = new NotificationConfig()
                .setEmbyHost("http://emby")
                .setEmbyPathReplaceRules(List.of(
                        new PathReplaceRule().setFrom("/volume1/anime").setTo("/mnt/smb/downloads")
                ));
        NotificationConfig loaded = GsonStatic.fromJson(GsonStatic.toJson(config), NotificationConfig.class);
        assertEquals("/mnt/smb/downloads", loaded.getEmbyPathReplaceRules().get(0).getTo());

        NotificationConfig old = GsonStatic.fromJson("{\"embyHost\":\"http://emby\"}", NotificationConfig.class);
        assertNull(old.getEmbyPathReplaceRules());
    }
}

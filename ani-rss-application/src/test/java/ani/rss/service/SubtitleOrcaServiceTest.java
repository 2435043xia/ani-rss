package ani.rss.service;

import ani.rss.entity.Config;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubtitleOrcaServiceTest {
    @TempDir Path directory;
    private Config config() throws Exception {
        Path key = directory.resolve("test-key"); Files.writeString(key, "synthetic test key, never used");
        Config config = new Config();
        config.setSubtitleTranslateSshHost("test-host"); config.setSubtitleTranslateSshPort(22);
        config.setSubtitleTranslateSshUser("test-user"); config.setSubtitleTranslateSshKeyPath(key.toString());
        config.setSubtitleTranslateOrcaBin("/usr/local/bin/orca"); config.setSubtitleTranslateOrcaAgent("codex");
        config.setSubtitleTranslateApiKey("must-not-be-sent");
        return config;
    }
    private static class Stub extends SubtitleOrcaService {
        String response, input; List<String> command;
        @Override protected String execute(List<String> command, String input) {
            this.command = command; this.input = input; return response;
        }
    }
    @Test void liveModelAndEffortsAreReturnedWithoutRenaming() throws Exception {
        Stub service = new Stub();
        service.response = """
                {"ok":true,"providers":[{"id":"codex","label":"codex","supportsModelSelection":true,
                "models":[{"id":"custom/new-gpt-model","label":"New model","efforts":[{"value":"ultra","label":"Ultra"}]}]}],
                "errors":[],"checkedAt":1234,"launchModelSelectionSupported":true}
                """;
        var catalog = service.models(config());
        assertEquals("custom/new-gpt-model", catalog.providers().getFirst().models().getFirst().id());
        assertEquals("ultra", catalog.providers().getFirst().models().getFirst().efforts().getFirst().value());
        var sent = JsonParser.parseString(service.input).getAsJsonObject();
        assertEquals(3, sent.size()); assertEquals("catalogs", sent.get("action").getAsString());
        assertFalse(service.input.contains("must-not-be-sent"));
        assertTrue(service.command.contains("BatchMode=yes"));
    }
    @Test void unavailableAndMalformedCatalogsNeverProducePlaceholderModels() throws Exception {
        for (String text : List.of("not json", "{}", "{\"ok\":false,\"error\":\"unavailable\"}")) {
            Stub service = new Stub(); service.response = text;
            assertThrows(IllegalArgumentException.class, () -> service.models(config()));
        }
    }
    @Test void missingKeyAndUnsafeHostNeverStartSsh() throws Exception {
        Stub service = new Stub(); Config config = config();
        config.setSubtitleTranslateSshHost("host;echo unsafe");
        assertThrows(IllegalArgumentException.class, () -> service.models(config)); assertNull(service.command);
        config.setSubtitleTranslateSshHost("test-host"); config.setSubtitleTranslateSshKeyPath("/missing-key");
        assertThrows(IllegalArgumentException.class, () -> service.models(config)); assertNull(service.command);
    }
    @Test void emptyDiscoveryIsExplicitInsteadOfInventingAProvider() throws Exception {
        Stub service = new Stub(); service.response = "{\"ok\":true,\"providers\":[],\"errors\":[]}";
        assertTrue(service.models(config()).providers().isEmpty());
    }
}

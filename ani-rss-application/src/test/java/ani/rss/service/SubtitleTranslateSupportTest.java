package ani.rss.service;

import ani.rss.service.SubtitleTranslateSupport.Choice;
import ani.rss.service.SubtitleTranslateSupport.Cue;
import ani.rss.service.SubtitleTranslateSupport.ModelReply;
import ani.rss.service.SubtitleTranslateSupport.Skip;
import ani.rss.service.SubtitleTranslateSupport.SubtitleStream;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubtitleTranslateSupportTest {

    @Test
    void tmdbIdRejectsBlankAndZero() {
        assertFalse(SubtitleTranslateSupport.hasTmdbId(null));
        assertFalse(SubtitleTranslateSupport.hasTmdbId("  "));
        assertFalse(SubtitleTranslateSupport.hasTmdbId("0"));
        assertFalse(SubtitleTranslateSupport.hasTmdbId("../1399"));
        assertTrue(SubtitleTranslateSupport.hasTmdbId(" 1399 "));
    }

    @Test
    void glossaryLivesUnderConfigDirUnlessConfigured() {
        File configDir = new File("/config");
        assertEquals(new File("/config/terminology/1399.txt"),
                SubtitleTranslateSupport.glossaryFile(configDir, "", "1399"));
        assertEquals(new File("/data/terms/1399.txt"),
                SubtitleTranslateSupport.glossaryFile(configDir, " /data/terms ", "1399"));
    }

    @Test
    void prefersJapaneseDialogueOverSdhAndEnglish() {
        Choice choice = SubtitleTranslateSupport.choose(List.of(
                stream(2, "subrip", "eng", "English", false, true),
                stream(3, "subrip", "jpn", "SDH", true, false),
                stream(4, "subrip", "jpn", "Japanese", false, false)
        ));
        assertEquals(Skip.NONE, choice.skip());
        assertEquals(4, choice.stream().index());
    }

    @Test
    void chineseTextTrackSkipsTranslation() {
        Choice choice = SubtitleTranslateSupport.choose(List.of(
                stream(2, "ass", "zh-CN", "简体", false, true),
                stream(3, "subrip", "jpn", "Japanese", false, false)
        ));
        assertEquals(Skip.HAS_CHINESE, choice.skip());
    }

    @Test
    void imageTracksAreNotText() {
        Choice choice = SubtitleTranslateSupport.choose(List.of(
                stream(2, "hdmv_pgs_subtitle", "jpn", "Japanese", false, true)
        ));
        assertEquals(Skip.NO_TEXT, choice.skip());
    }

    @Test
    void existingGlossaryTermsStay() {
        Map<String, String> merged = SubtitleTranslateSupport.mergeGlossary(
                Map.of("ゼルギス", "泽尔吉斯"),
                Map.of("ゼルギス", "泽尔吉斯王", "ロメリア", "罗梅莉亚"));
        assertEquals("泽尔吉斯", merged.get("ゼルギス"));
        assertEquals("罗梅莉亚", merged.get("ロメリア"));
        assertTrue(SubtitleTranslateSupport.writeGlossary(merged).contains("ロメリア::罗梅莉亚"));
    }

    @Test
    void modelOutputKeepsLineCountAndNewTerms() {
        String content = """
                1|我是魔王
                2|退下
                <terminology>
                魔王::魔王
                ゼルギス::泽尔吉斯
                </terminology>
                """;
        Optional<ModelReply> reply = SubtitleTranslateSupport.parseModelOutput(content, 2);
        assertTrue(reply.isPresent());
        assertEquals(List.of("我是魔王", "退下"), reply.get().lines());
        assertEquals("泽尔吉斯", reply.get().terms().get("ゼルギス"));
        assertTrue(SubtitleTranslateSupport.parseModelOutput("1|只有一句", 2).isEmpty());
    }

    @Test
    void srtRoundTripKeepsTimestamps() {
        String srt = """
                1
                00:00:01,000 --> 00:00:02,500
                魔王ゼルギス

                2
                00:00:03,000 --> 00:00:04,000
                第二行
                """;
        List<Cue> cues = SubtitleTranslateSupport.parseSrt(srt);
        assertEquals(2, cues.size());
        assertEquals("00:00:01,000", cues.get(0).start());
        assertEquals("魔王ゼルギス", cues.get(0).text());
        List<Cue> translated = List.of(
                new Cue(cues.get(0).start(), cues.get(0).end(), "魔王泽尔吉斯"),
                new Cue(cues.get(1).start(), cues.get(1).end(), "第二行")
        );
        String rendered = SubtitleTranslateSupport.renderSrt(translated);
        assertTrue(rendered.contains("00:00:01,000 --> 00:00:02,500"));
        assertTrue(rendered.contains("魔王泽尔吉斯"));
    }

    @Test
    void existingChineseSidecarIsSkipped() throws Exception {
        File dir = Files.createTempDirectory("subtitle-backfill").toFile();
        File video = new File(dir, "Show S01E01.mkv");
        File note = new File(dir, "note.txt");
        assertTrue(video.createNewFile());
        assertTrue(note.createNewFile());
        assertTrue(SubtitleTranslateService.needsTranslation(video));
        assertFalse(SubtitleTranslateService.needsTranslation(note));
        assertTrue(new File(dir, "Show S01E01.zh.srt").createNewFile());
        assertFalse(SubtitleTranslateService.needsTranslation(video));
    }

    @Test
    void sshCommandRejectsShellMetacharacters() {
        assertTrue(SubtitleTranslateSupport.sshCommand("bad;host", 22, "user", "/config/id_ed25519").isEmpty());
        assertTrue(SubtitleTranslateSupport.sshCommand("macmini.local", 22, "blumenkranz", "/config/id_ed25519").isPresent());
        List<String> command = SubtitleTranslateSupport.sshCommand(
                "macmini.local", 22, "blumenkranz", "/config/id_ed25519").orElseThrow();
        assertEquals("blumenkranz@macmini.local", command.get(command.size() - 2));
        assertTrue(command.contains("BatchMode=yes"));
        String remote = command.get(command.size() - 1);
        assertTrue(remote.startsWith("python3 -c"));
        int encodedStart = remote.indexOf("b64decode('") + "b64decode('".length();
        int encodedEnd = remote.indexOf("')", encodedStart);
        String runner = new String(java.util.Base64.getDecoder().decode(remote.substring(encodedStart, encodedEnd)),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(runner.contains("\"terminal\", \"create\""));
        assertTrue(runner.contains("\"--worktree\""));
        assertTrue(runner.contains("\"worker-start\""));
        assertTrue(runner.contains("\"--agent\""));
        assertTrue(runner.contains("\"grok\""));
        assertTrue(SubtitleTranslateSupport.allowedWorktree("name:misc"));
        assertTrue(SubtitleTranslateSupport.allowedWorktree("path:/Users/blumenkranz/develop/goland/misc"));
        assertFalse(SubtitleTranslateSupport.allowedWorktree(""));
        assertFalse(SubtitleTranslateSupport.allowedWorktree("name:misc;rm"));
        assertTrue(runner.contains("\"terminal\", \"close\""));
        assertTrue(runner.contains("\"worker-release\""));
        assertFalse(runner.contains("'-m'"));
        assertFalse(runner.contains("'--reasoning-effort'"));
        assertFalse(runner.contains("~/.grok/bin/grok"));
        assertTrue(runner.contains("/usr/local/bin/orca"));
        assertFalse(runner.contains("req.get(\"grok\")"));
        assertFalse(SubtitleTranslateSupport.allowedEffort("high;rm"));
        assertTrue(SubtitleTranslateSupport.allowedEffort("max"));
        assertTrue(SubtitleTranslateSupport.allowedEffort("ultra"));
        assertTrue(SubtitleTranslateSupport.allowedModel("ark/gpt-6.1-sol"));
        assertFalse(SubtitleTranslateSupport.allowedModel("bad;model"));
        assertTrue(SubtitleTranslateSupport.allowedAgent("codex"));
        assertFalse(SubtitleTranslateSupport.allowedAgent("codex;rm"));
        assertTrue(runner.contains("\"--model\""));
        assertTrue(runner.contains("\"--effort\""));
        assertTrue(SubtitleTranslateSupport.allowedEffort(""));
        assertTrue(SubtitleTranslateSupport.allowedEffort("xhigh"));
        assertTrue(SubtitleTranslateSupport.allowedOrcaBin(""));
        assertTrue(SubtitleTranslateSupport.allowedOrcaBin("/usr/local/bin/orca"));
        assertFalse(SubtitleTranslateSupport.allowedOrcaBin("/usr/local/bin/../orca"));
    }

    @Test
    void grokJsonTextFeedsTheSubtitleParser() {
        String text = SubtitleTranslateSupport.grokText(
                "{\"text\":\"1|你好\\n<terminology>\\n</terminology>\",\"stopReason\":\"EndTurn\"}");
        Optional<ModelReply> reply = SubtitleTranslateSupport.parseModelOutput(text, 1);
        assertTrue(reply.isPresent());
        assertEquals("你好", reply.get().lines().get(0));
        assertEquals(text, SubtitleTranslateSupport.modelText(
                "{\"text\":\"1|你好\\n<terminology>\\n</terminology>\",\"stopReason\":\"EndTurn\"}"));
        String raw = SubtitleTranslateSupport.modelText("1|你好\n<terminology>\n</terminology>\n");
        Optional<ModelReply> direct = SubtitleTranslateSupport.parseModelOutput(raw, 1);
        assertTrue(direct.isPresent());
        assertEquals("你好", direct.get().lines().get(0));
    }

    private static SubtitleStream stream(int index, String codec, String language, String title,
                                         boolean sdh, boolean defaultTrack) {
        return new SubtitleStream(index, codec, language, title, sdh, defaultTrack);
    }
}

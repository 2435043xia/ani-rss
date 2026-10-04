# TMDB Align Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a subscription-editor button that previews, then fills season, episode offset, a TMDB-name rename template, and a custom download path from Emby or from the TMDB name, without changing the subscription title.

**Architecture:** Pure functions pick the RSS item, the TMDB episode, the offset, the Emby hit, and the path prefix. `TmdbAlignService.assemble` turns those inputs into a preview DTO and is unit-tested without the network. The controller loads RSS, TMDB, and Emby, then calls `assemble`. The Vue dialog only copies the chosen preview fields into the open subscription form. The existing save button persists them.

**Tech Stack:** Java 25, Spring Boot, JUnit 5 (from `spring-boot-starter-parent`), Hutool HTTP, Gson, Vue 3, Element Plus. Tests run with Maven on the builder host. Do not use `@SpringBootTest` for these tests.

**Spec:** `docs/superpowers/specs/2026-10-05-tmdb-emby-align-design.md`

## Global Constraints

- Work on branch `feat/tmdb-align` in `/home/builder/develop/ani-rss`. Run commands with `ssh builder`.
- Do not change the subscription title. The existing 「使用 TMDB」 button still copies the TMDB name into the title.
- Do not change standby RSS offsets.
- Do not append a `Season` directory to an Emby path.
- Do not match Emby items by path. Match `ProviderIds.Tmdb` first. If none match, match the series name to `tmdb.name` or `tmdb.originalName` after trim.
- Path rules are literal prefixes, one replacement, longest prefix wins. A trailing slash does not change the prefix.
- The preview endpoint does not save the subscription.
- Confirm writes into the open editor form only. Persistence stays on the editor's existing save.
- Calendar dates use `Asia/Shanghai`.
- Do not change git config. Commits in this repo need `GIT_AUTHOR_NAME=2435043xia`, `GIT_AUTHOR_EMAIL=2435043xia@gmail.com`, `GIT_COMMITTER_NAME=2435043xia`, `GIT_COMMITTER_EMAIL=2435043xia@gmail.com`.
- Do not commit the unrelated dirty files until the task that owns them. Those files are `EmbyRefreshNotificationView.vue`, `ConfigView.vue`, `LibraryView.vue`, and `library/EmbyLibraryItem.vue`.
- Image publishing waits until Task 8. Registry is `registry.888987.xyz`. Tag is the pom version plus `-tmdb-align` (now `3.2.39-tmdb-align`). Do not push `latest` and do not reuse a tag that is already running.
- Do not print API keys, passwords, or Emby tokens.

## Review Focus

- A rule whose prefix appears only in the middle of a path must leave the path unchanged. `/anime` does not rewrite `/volume1/anime/Show`.
- An RSS timestamp in the afternoon and a TMDB `air_date` at midnight are the same day when both fall on the same Shanghai calendar date, so the distance is 0.
- An RSS episode of `25.5` uses the integer part toward zero (`25`) before the offset subtraction.
- An Emby series named `Show [tmdb-1]` or `Show [tmdbid=1]` is not a name match for TMDB name `Show`.
- An OVA subscription cannot be confirmed, even when RSS items and episodes exist.

---

### Task 1: Episode alignment math

**Files:**
- Create: `ani-rss-application/src/main/java/ani/rss/util/other/EpisodeAlignUtil.java`
- Test: `ani-rss-application/src/test/java/ani/rss/util/other/EpisodeAlignUtilTest.java`

**Interfaces:**
- Consumes: `ani.rss.entity.Item` (`pubDate`, `episode`, `title`) and `wushuo.tmdb.api.entity.TmdbEpisode` (`airDate`, `seasonNumber`, `episodeNumber`, `name`).
- Produces:
  - `public static Optional<Item> earliest(List<Item> items)`
  - `public static String sameDayNote(List<Item> items, Item chosen)`
  - `public static List<TmdbEpisode> closest(List<TmdbEpisode> episodes, Date pubDate)`
  - `public static int offset(int tmdbEpisodeNumber, double rssEpisode)`
  - `public static String sampleName(String tmdbName, int season, int episodeNumber)`
  - `public static String renameTemplate(String tmdbName)`
  - `public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai")`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run on the builder:

```bash
cd /home/builder/develop/ani-rss && mvn -pl ani-rss-application -Dtest=EpisodeAlignUtilTest -DfailIfNoTests=false test
```

Expected: FAIL because `EpisodeAlignUtil` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
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
```

The UTC-midnight case in `sameShanghaiDayHasDistanceZeroAcrossClockTimes` is only distance 0 when that instant's Shanghai date is still 2024-01-10. `2024-01-10T00:00:00Z` is `2024-01-10 08:00` in Shanghai, so the test as written expects episode 25. Keep that assertion.

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
cd /home/builder/develop/ani-rss && mvn -pl ani-rss-application -Dtest=EpisodeAlignUtilTest -DfailIfNoTests=false test
```

Expected: `BUILD SUCCESS` and `EpisodeAlignUtilTest` tests pass.

- [ ] **Step 5: Commit**

```bash
cd /home/builder/develop/ani-rss
git add ani-rss-application/src/main/java/ani/rss/util/other/EpisodeAlignUtil.java \
  ani-rss-application/src/test/java/ani/rss/util/other/EpisodeAlignUtilTest.java
GIT_AUTHOR_NAME=2435043xia GIT_AUTHOR_EMAIL=2435043xia@gmail.com \
GIT_COMMITTER_NAME=2435043xia GIT_COMMITTER_EMAIL=2435043xia@gmail.com \
git commit -m "feat: 计算 TMDB 对齐用的季和偏移"
```

### Task 2: Path prefix replacement

**Files:**
- Create: `ani-rss-application/src/main/java/ani/rss/entity/PathReplaceRule.java`
- Create: `ani-rss-application/src/main/java/ani/rss/util/other/PathReplaceUtil.java`
- Test: `ani-rss-application/src/test/java/ani/rss/util/other/PathReplaceUtilTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `PathReplaceRule` with `String from` and `String to`, Lombok `@Data` and `@Accessors(chain = true)`, `implements Serializable`.
  - `public static String apply(String path, List<PathReplaceRule> rules)`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/builder/develop/ani-rss && mvn -pl ani-rss-application -Dtest=PathReplaceUtilTest -DfailIfNoTests=false test
```

Expected: FAIL because the classes do not exist.

- [ ] **Step 3: Write minimal implementation**

`PathReplaceRule.java`:

```java
package ani.rss.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
public class PathReplaceRule implements Serializable {
    private String from;
    private String to;
}
```

`PathReplaceUtil.java`:

```java
package ani.rss.util.other;

import ani.rss.entity.PathReplaceRule;

import java.util.List;

public final class PathReplaceUtil {
    private PathReplaceUtil() {
    }

    public static String apply(String path, List<PathReplaceRule> rules) {
        if (path == null || path.isEmpty() || rules == null || rules.isEmpty()) {
            return path;
        }
        String normalizedPath = path.replace('\\', '/');
        PathReplaceRule best = null;
        int bestLength = -1;
        for (PathReplaceRule rule : rules) {
            String prefix = prefixOf(rule);
            if (prefix == null) {
                continue;
            }
            if (matches(normalizedPath, prefix) && prefix.length() > bestLength) {
                best = rule;
                bestLength = prefix.length();
            }
        }
        if (best == null) {
            return path;
        }
        String prefix = prefixOf(best);
        String rest = normalizedPath.substring(prefix.length());
        String target = stripTrailingSlash(best.getTo().replace('\\', '/').trim());
        if (rest.isEmpty()) {
            return target;
        }
        if (!rest.startsWith("/")) {
            rest = "/" + rest;
        }
        return target + rest;
    }

    private static String prefixOf(PathReplaceRule rule) {
        if (rule == null || rule.getFrom() == null || rule.getTo() == null) {
            return null;
        }
        String prefix = stripTrailingSlash(rule.getFrom().replace('\\', '/').trim());
        if (prefix.isEmpty()) {
            return null;
        }
        return prefix;
    }

    private static boolean matches(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/builder/develop/ani-rss && mvn -pl ani-rss-application -Dtest=PathReplaceUtilTest -DfailIfNoTests=false test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/builder/develop/ani-rss
git add ani-rss-application/src/main/java/ani/rss/entity/PathReplaceRule.java \
  ani-rss-application/src/main/java/ani/rss/util/other/PathReplaceUtil.java \
  ani-rss-application/src/test/java/ani/rss/util/other/PathReplaceUtilTest.java
GIT_AUTHOR_NAME=2435043xia GIT_AUTHOR_EMAIL=2435043xia@gmail.com \
GIT_COMMITTER_NAME=2435043xia GIT_COMMITTER_EMAIL=2435043xia@gmail.com \
git commit -m "feat: 按最长前缀把 Emby 路径换成 ani-rss 路径"
```

### Task 3: Emby choice and season conflict

**Files:**
- Create: `ani-rss-application/src/main/java/ani/rss/entity/vo/EmbyPathChoice.java`
- Create: `ani-rss-application/src/main/java/ani/rss/util/other/EmbyMatchUtil.java`
- Create: `ani-rss-application/src/main/java/ani/rss/util/other/SeasonConflictUtil.java`
- Test: `ani-rss-application/src/test/java/ani/rss/util/other/EmbyMatchUtilTest.java`
- Test: `ani-rss-application/src/test/java/ani/rss/util/other/SeasonConflictUtilTest.java`

**Interfaces:**
- Consumes: `PathReplaceRule` is not used here. Callers apply `PathReplaceUtil` before building choices.
- Produces:
  - `EmbyPathChoice`: `embyHost`, `seriesName`, `tmdbId`, `originalPath`, `replacedPath`. Lombok `@Data` `@Accessors(chain = true)`.
  - `public static List<EmbyPathChoice> EmbyMatchUtil.select(List<EmbyPathChoice> all, String tmdbId, String name, String originalName)`
  - `public static Optional<Ani> SeasonConflictUtil.find(List<Ani> all, Ani current, int season)`

- [ ] **Step 1: Write the failing tests**

`EmbyMatchUtilTest`:

```java
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
```

`SeasonConflictUtilTest`:

```java
package ani.rss.util.other;

import ani.rss.entity.Ani;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonConflictUtilTest {

    @Test
    void sameTitleAndNewSeasonOnAnotherSubscriptionConflicts() {
        Ani current = new Ani().setId("1").setTitle("葬送的芙莉莲").setSeason(3);
        Ani other = new Ani().setId("2").setTitle("葬送的芙莉莲").setSeason(1);
        Optional<Ani> conflict = SeasonConflictUtil.find(List.of(current, other), current, 1);
        assertTrue(conflict.isPresent());
        assertEquals("2", conflict.get().getId());
    }

    @Test
    void currentSubscriptionIsNotAConflictWithItself() {
        Ani current = new Ani().setId("1").setTitle("葬送的芙莉莲").setSeason(1);
        assertTrue(SeasonConflictUtil.find(List.of(current), current, 1).isEmpty());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /home/builder/develop/ani-rss && mvn -pl ani-rss-application -Dtest=EmbyMatchUtilTest,SeasonConflictUtilTest -DfailIfNoTests=false test
```

Expected: FAIL because the types do not exist.

- [ ] **Step 3: Write minimal implementation**

`EmbyPathChoice.java`:

```java
package ani.rss.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class EmbyPathChoice {
    private String embyHost;
    private String seriesName;
    private String tmdbId;
    private String originalPath;
    private String replacedPath;
}
```

`EmbyMatchUtil.java`:

```java
package ani.rss.util.other;

import ani.rss.entity.vo.EmbyPathChoice;
import cn.hutool.core.util.StrUtil;

import java.util.List;

public final class EmbyMatchUtil {
    private EmbyMatchUtil() {
    }

    public static List<EmbyPathChoice> select(List<EmbyPathChoice> all, String tmdbId,
                                              String name, String originalName) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        if (StrUtil.isNotBlank(tmdbId)) {
            List<EmbyPathChoice> byId = all.stream()
                    .filter(hit -> tmdbId.equals(StrUtil.blankToDefault(hit.getTmdbId(), "")))
                    .toList();
            if (!byId.isEmpty()) {
                return byId;
            }
        }
        return all.stream()
                .filter(hit -> sameName(hit.getSeriesName(), name) || sameName(hit.getSeriesName(), originalName))
                .toList();
    }

    private static boolean sameName(String left, String right) {
        if (StrUtil.isBlank(left) || StrUtil.isBlank(right)) {
            return false;
        }
        return left.trim().equals(right.trim());
    }
}
```

`SeasonConflictUtil.java`:

```java
package ani.rss.util.other;

import ani.rss.entity.Ani;
import cn.hutool.core.util.StrUtil;

import java.util.List;
import java.util.Optional;

public final class SeasonConflictUtil {
    private SeasonConflictUtil() {
    }

    public static Optional<Ani> find(List<Ani> all, Ani current, int season) {
        if (all == null || current == null || StrUtil.isBlank(current.getTitle())) {
            return Optional.empty();
        }
        return all.stream()
                .filter(ani -> ani != current)
                .filter(ani -> StrUtil.isBlank(current.getId()) || !current.getId().equals(ani.getId()))
                .filter(ani -> current.getTitle().equals(ani.getTitle()))
                .filter(ani -> ani.getSeason() != null && ani.getSeason() == season)
                .findFirst();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /home/builder/develop/ani-rss && mvn -pl ani-rss-application -Dtest=EmbyMatchUtilTest,SeasonConflictUtilTest -DfailIfNoTests=false test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/builder/develop/ani-rss
git add ani-rss-application/src/main/java/ani/rss/entity/vo/EmbyPathChoice.java \
  ani-rss-application/src/main/java/ani/rss/util/other/EmbyMatchUtil.java \
  ani-rss-application/src/main/java/ani/rss/util/other/SeasonConflictUtil.java \
  ani-rss-application/src/test/java/ani/rss/util/other/EmbyMatchUtilTest.java \
  ani-rss-application/src/test/java/ani/rss/util/other/SeasonConflictUtilTest.java
GIT_AUTHOR_NAME=2435043xia GIT_AUTHOR_EMAIL=2435043xia@gmail.com \
GIT_COMMITTER_NAME=2435043xia GIT_COMMITTER_EMAIL=2435043xia@gmail.com \
git commit -m "feat: 选择 Emby 剧集并检查季冲突"
```

### Task 4: Store path rules on each Emby entry

**Files:**
- Modify: `ani-rss-application/src/main/java/ani/rss/entity/NotificationConfig.java` after `embyDelayed`
- Modify: `ani-rss-ui/src/view/config/library/EmbyLibraryItem.vue`
- Test: `ani-rss-application/src/test/java/ani/rss/entity/PathReplaceRuleConfigTest.java`

The library page files are already in the working tree and uncommitted. This task commits them together with the new rule editor. Do not revert `LibraryView.vue`, `ConfigView.vue`, or `EmbyRefreshNotificationView.vue`.

**Interfaces:**
- Consumes: `PathReplaceRule`.
- Produces: `NotificationConfig.embyPathReplaceRules` as `List<PathReplaceRule>`. Gson field name `embyPathReplaceRules`. Old config files that omit the field load as null. `PathReplaceUtil.apply` already treats null as "no rules".

- [ ] **Step 1: Write the failing test**

```java
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
```

`GsonStatic.toJson(Object)` and `GsonStatic.fromJson(String, Class)` are the helpers. Do not add a JSON library.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/builder/develop/ani-rss && mvn -pl ani-rss-application -Dtest=PathReplaceRuleConfigTest -DfailIfNoTests=false test
```

Expected: FAIL because `getEmbyPathReplaceRules` does not exist.

- [ ] **Step 3: Add the field and the editor**

On `NotificationConfig`, next to `embyDelayed`:

```java
@Schema(description = "Emby 路径替换规则")
private List<PathReplaceRule> embyPathReplaceRules;
```

In `EmbyLibraryItem.vue`, under the API key field, add a block titled `路径替换`. Bind `notificationConfig.embyPathReplaceRules`. Each row has two inputs, Emby 前缀 and ani-rss 前缀, plus a delete button. An `添加规则` button pushes `{from: '', to: ''}`. In `onMounted`, when `embyPathReplaceRules` is null, set it to `[]`.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/builder/develop/ani-rss && mvn -pl ani-rss-application -Dtest=PathReplaceRuleConfigTest -DfailIfNoTests=false test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/builder/develop/ani-rss
git add ani-rss-application/src/main/java/ani/rss/entity/NotificationConfig.java \
  ani-rss-application/src/test/java/ani/rss/entity/PathReplaceRuleConfigTest.java \
  ani-rss-ui/src/view/config/library/EmbyLibraryItem.vue \
  ani-rss-ui/src/view/config/LibraryView.vue \
  ani-rss-ui/src/view/config/notification/EmbyRefreshNotificationView.vue \
  ani-rss-ui/src/view/home/ConfigView.vue
GIT_AUTHOR_NAME=2435043xia GIT_AUTHOR_EMAIL=2435043xia@gmail.com \
GIT_COMMITTER_NAME=2435043xia GIT_COMMITTER_EMAIL=2435043xia@gmail.com \
git commit -m "feat: 在每条 Emby 上保存路径替换规则"
```

### Task 5: Read Emby series items

**Files:**
- Modify: `ani-rss-application/src/main/java/ani/rss/service/EmbyRefreshService.java`
- Test: `ani-rss-application/src/test/java/ani/rss/service/EmbySeriesParseTest.java`

**Interfaces:**
- Consumes: `EmbyPathChoice`, `PathReplaceUtil`, `NotificationConfig.embyPathReplaceRules`.
- Produces:
  - `public static List<EmbyPathChoice> EmbyRefreshService.parseSeries(String embyHost, String json, List<PathReplaceRule> rules)`
  - `public List<EmbyPathChoice> listSeries(NotificationConfig config)`
  - `public String listSeriesError(NotificationConfig config)` returns an empty string on success. A failed host returns `host + " " + status or exception message` and does not throw to the preview caller.

`parseSeries` reads `Items[]`. For each object read `Name`, `Path`, and `ProviderIds.Tmdb` (missing key becomes `""`). Skip items whose `Path` is blank. Set `originalPath` to `Path` and `replacedPath` to `PathReplaceUtil.apply(Path, rules)`.

`listSeries` requires a non-blank host and API key. For each id in `embyRefreshViewIds`, GET `{embyHost}/emby/Items?ParentId={id}&Recursive=true&IncludeItemTypes=Series&Fields=ProviderIds,Path` with header `X-Emby-Token`. Use `HttpReq` the same way `getViews` does. On a non-200 response, record the host and status in `listSeriesError` and continue with the next library. Do not log the token.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/builder/develop/ani-rss && mvn -pl ani-rss-application -Dtest=EmbySeriesParseTest -DfailIfNoTests=false test
```

Expected: FAIL because `parseSeries` does not exist.

- [ ] **Step 3: Implement parseSeries and listSeries**

Parse with `GsonStatic.fromJson(json, JsonObject.class)`. A body that is not JSON, or an `Items` array that is missing, returns an empty list. `listSeries` concatenates `parseSeries` for every configured library id. No unit test calls `listSeries`.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/builder/develop/ani-rss && mvn -pl ani-rss-application -Dtest=EmbySeriesParseTest -DfailIfNoTests=false test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/builder/develop/ani-rss
git add ani-rss-application/src/main/java/ani/rss/service/EmbyRefreshService.java \
  ani-rss-application/src/test/java/ani/rss/service/EmbySeriesParseTest.java
GIT_AUTHOR_NAME=2435043xia GIT_AUTHOR_EMAIL=2435043xia@gmail.com \
GIT_COMMITTER_NAME=2435043xia GIT_COMMITTER_EMAIL=2435043xia@gmail.com \
git commit -m "feat: 读取 Emby 剧集的 TMDB id 和路径"
```

### Task 6: Preview assembly and endpoint

**Files:**
- Create: `ani-rss-application/src/main/java/ani/rss/entity/vo/TmdbAlignChoice.java`
- Create: `ani-rss-application/src/main/java/ani/rss/entity/vo/TmdbAlignPreview.java`
- Create: `ani-rss-application/src/main/java/ani/rss/service/TmdbAlignService.java`
- Modify: `ani-rss-application/src/main/java/ani/rss/controller/ThemoviedbController.java`
- Modify: `ani-rss-application/src/main/java/ani/rss/util/other/TmdbUtils.java`
- Test: `ani-rss-application/src/test/java/ani/rss/service/TmdbAlignServiceTest.java`

**Interfaces:**
- Consumes: `EpisodeAlignUtil`, `EmbyMatchUtil`, `SeasonConflictUtil`, `EmbyPathChoice`, `EmbyRefreshService.listSeries`, `ItemsUtil.getItems(Ani, String, String)`, `TmdbUtils`.
- Produces:
  - `TmdbAlignChoice`: `seasonNumber`, `episodeNumber`, `airDate`, `episodeName`, `newSeason`, `newOffset`, `sampleFileName`, `fallbackPath`, `seasonConflict`, `conflictWith`.
  - `TmdbAlignPreview`: `canConfirm`, `blockReason`, `rssTitle`, `rssEpisode`, `rssPubDate`, `rssNote`, `episodeChoices`, `episodeChoiceRequired`, `currentSeason`, `currentOffset`, `renameTemplate`, `themoviedbName`, `tmdb`, `embyChoices`, `embyChoiceRequired`, `embyMessage`.
  - `public TmdbAlignPreview TmdbAlignService.assemble(Ani ani, List<Ani> subscriptions, List<Item> masterItems, List<TmdbEpisode> episodes, List<EmbyPathChoice> embyHits, String embyMessage, String globalDownloadTemplate)`
  - `public TmdbAlignPreview TmdbAlignService.load(Ani ani)`
  - `public static void TmdbAlignService.apply(Ani ani, TmdbAlignPreview preview, int episodeIndex, Integer embyIndex)`
  - `POST /api/previewTmdbAlign` accepts `Ani` and returns `Result.success(load(ani))`. Annotate with `@Auth`.

`assemble` rules:

- `Boolean.TRUE.equals(ani.getOva())`: `canConfirm` false, `blockReason` `剧场版不按季对齐`. Leave title-sized fields empty. Do not read episodes.
- No item from `EpisodeAlignUtil.earliest`: `canConfirm` false, `blockReason` `主 RSS 没有带日期和集号的条目`.
- `episodes` empty after `closest`: `canConfirm` false, `blockReason` `TMDB 没有对上的集`.
- Otherwise build one `TmdbAlignChoice` per closest episode. `newSeason` is `seasonNumber`. `newOffset` is `EpisodeAlignUtil.offset`. `sampleFileName` uses the TMDB episode number, because `rssEpisode + offset` equals that number. `fallbackPath` is `globalDownloadTemplate` with the literal `${title}` replaced by `themoviedbName`, then `${season}` and `${seasonFormat}` replaced using `newSeason`. Do not call `DownloadService`, because that replaces `${title}` from the subscription title.
- `renameTemplate` is `EpisodeAlignUtil.renameTemplate(themoviedbName)`.
- `themoviedbName` comes from the `Ani.themoviedbName` argument when it is not blank. `load` sets it with `TmdbUtils.getFinalName(tmdb)` before `assemble`.
- `episodeChoiceRequired` is true when there is more than one choice. `canConfirm` is false in that case.
- A choice whose `SeasonConflictUtil.find` is present gets `seasonConflict` true, `conflictWith` set to that subscription's title, and forces `canConfirm` false when it is the only choice.
- `embyChoices` is the already filtered hit list. `embyChoiceRequired` is true when size is greater than 1, and then `canConfirm` is false.
- Size 0 leaves `embyMessage` as given. `canConfirm` can still be true when season and offset are unique and there is no conflict.
- `canConfirm` is true only when `blockReason` is empty, exactly one episode choice exists, that choice has `seasonConflict` false, and `embyChoices.size()` is 0 or 1.

`apply` writes only:

- `ani.season` from the chosen episode
- `ani.offset` from the chosen episode
- `ani.customRenameTemplateEnable` true and `ani.customRenameTemplate` from `preview.renameTemplate`
- `ani.customDownloadPath` true
- `ani.customDownloadPathTemplate` from `embyChoices.get(embyIndex).replacedPath` when `embyIndex` is not null, otherwise from the choice's `fallbackPath`
- `ani.tmdb` only when the current `tmdb.id` is blank. Copy `preview.tmdb`. If the current object had a `tmdbGroupId`, put that id back.
- `ani.themoviedbName` from `preview.themoviedbName`

`apply` does not assign `ani.title` or any `StandbyRss.offset`.

`load`:

- Copy the request `Ani`. Do not save it.
- OVA returns the blocked preview immediately.
- If `tmdb` is null or `tmdb.id` is blank, call `TmdbUtils.getTmdbTv` with `RenameUtil.renameDel(title, false)`. Empty result uses `blockReason` `TMDB 对不上`.
- If the subscription already has a `tmdbGroupId`, keep it on the tmdb object used for the lookup.
- Master RSS items come from `ItemsUtil.getItems(ani, ani.getUrl(), subgroup)`. Do not use the overload that merges standby RSS.
- Episodes come from a new `TmdbUtils.listEpisodesForAlign(Tmdb tmdb)`. When `tmdbGroupId` is not blank, GET `{tmdbApi}/3/tv/episode_group/{tmdbGroupId}` with the API key from `CustomTmdbConfig`. Flatten `groups[].episodes` into `TmdbEpisode` with Gson. When `tmdbGroupId` is blank, GET `{tmdbApi}/3/tv/{id}`, read `seasons[].season_number`, skip season 0, and append `TmdbUtils.getTmdbSeason` episodes. Use `CustomTmdbConfig` for the API base and key. Do not hardcode a key. This method is not unit-tested.
- Emby hits: for every `NotificationConfig` in `ConfigUtil.CONFIG.getNotificationConfigList()` whose `notificationType` is `EMBY_REFRESH` and whose host and API key are not blank, call `listSeries`. Pass failures through `listSeriesError` into `embyMessage`, joined by newlines. Run `EmbyMatchUtil.select` on the combined hits. An id match from any server suppresses name matches from the others.
- `globalDownloadTemplate` is `ConfigUtil.CONFIG.getDownloadPathTemplate()`.
- Subscriptions for the conflict check are `AniUtil.ANI_LIST`. Do not add a subscription.

- [ ] **Step 1: Write the failing test**

Cover these `assemble` and `apply` cases in `TmdbAlignServiceTest` with plain JUnit, no Spring:

- RSS episode 25 dated 2024-01-10 and one TMDB episode season 1 number 25 on 2024-01-10: `newSeason` 1, `newOffset` 0, sample `葬送的芙莉莲 S01E25`, `canConfirm` true. `fallbackPath` for template `/downloads/${title}/Season ${seasonFormat}` is `/downloads/葬送的芙莉莲/Season 01`.
- Two closest episodes: `episodeChoiceRequired` true, `canConfirm` false.
- Another subscription with the same title and season 1: the only choice has `seasonConflict` true and `conflictWith` equal to that title, `canConfirm` false.
- No Emby hits and message `http://emby 500`: `embyChoices` empty, `embyMessage` contains `500`, `canConfirm` true when the episode is unique.
- `ova` true: `blockReason` `剧场版不按季对齐`, `canConfirm` false.
- `apply` on an `Ani` with title `RSS标题`, offset `-24`, and one standby RSS whose offset is `12`: after apply, title is still `RSS标题`, standby offset is still `12`, season is `1`, offset is `0`, custom rename and custom path switches are true, and the path is the Emby `replacedPath` when an Emby index is passed.

Build the TMDB episode with `setSeasonNumber`, `setEpisodeNumber`, `setAirDate`, and `setName`. Build RSS items with `setEpisode` and `setPubDate` using `EpisodeAlignUtil.ZONE`.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/builder/develop/ani-rss && mvn -pl ani-rss-application -Dtest=TmdbAlignServiceTest -DfailIfNoTests=false test
```

Expected: FAIL because `TmdbAlignService` does not exist.

- [ ] **Step 3: Implement the DTO, assemble, apply, load, and controller**

Keep `load` and `listEpisodesForAlign` free of unit-test network calls. `assemble` and `apply` contain the decisions the test checks.

Controller method:

```java
@Auth
@Operation(summary = "预览 TMDB 对齐")
@PostMapping("/previewTmdbAlign")
public Result<TmdbAlignPreview> previewTmdbAlign(@RequestBody Ani ani) {
    return Result.success(tmdbAlignService.load(ani));
}
```

Inject `TmdbAlignService` with the same Spring style as the other services used by controllers. `TmdbAlignService` is a `@Service`. `assemble` and `apply` can be static so the test does not start Spring.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/builder/develop/ani-rss && mvn -pl ani-rss-application -Dtest=TmdbAlignServiceTest,EpisodeAlignUtilTest,PathReplaceUtilTest,EmbyMatchUtilTest,SeasonConflictUtilTest -DfailIfNoTests=false test
```

Expected: PASS for all of those classes.

- [ ] **Step 5: Commit**

```bash
cd /home/builder/develop/ani-rss
git add ani-rss-application/src/main/java/ani/rss/entity/vo/TmdbAlignChoice.java \
  ani-rss-application/src/main/java/ani/rss/entity/vo/TmdbAlignPreview.java \
  ani-rss-application/src/main/java/ani/rss/service/TmdbAlignService.java \
  ani-rss-application/src/main/java/ani/rss/controller/ThemoviedbController.java \
  ani-rss-application/src/main/java/ani/rss/util/other/TmdbUtils.java \
  ani-rss-application/src/test/java/ani/rss/service/TmdbAlignServiceTest.java
GIT_AUTHOR_NAME=2435043xia GIT_AUTHOR_EMAIL=2435043xia@gmail.com \
GIT_COMMITTER_NAME=2435043xia GIT_COMMITTER_EMAIL=2435043xia@gmail.com \
git commit -m "feat: 提供 TMDB 对齐预览"
```

### Task 7: Preview dialog

**Files:**
- Modify: `ani-rss-ui/src/js/http.js` next to `getThemoviedbName`
- Create: `ani-rss-ui/src/view/home/TmdbAlignPreview.vue`
- Modify: `ani-rss-ui/src/view/home/AniView.vue` at the 「使用 TMDB」 button

**Interfaces:**
- Consumes: `POST api/previewTmdbAlign` and the preview JSON from Task 6. Field names are the Java bean names: `canConfirm`, `blockReason`, `rssTitle`, `rssEpisode`, `rssPubDate`, `rssNote`, `episodeChoices`, `episodeChoiceRequired`, `currentSeason`, `currentOffset`, `renameTemplate`, `themoviedbName`, `tmdb`, `embyChoices`, `embyChoiceRequired`, `embyMessage`. Each episode choice uses `newSeason`, `newOffset`, `sampleFileName`, `fallbackPath`, `seasonConflict`, `conflictWith`, `seasonNumber`, `episodeNumber`, `airDate`, `episodeName`. Each Emby choice uses `embyHost`, `seriesName`, `originalPath`, `replacedPath`.
- Produces: a button labeled `TMDB 对齐` that does not call the title assignment used by `使用 TMDB`.

- [ ] **Step 1: Add the API function**

```javascript
export let previewTmdbAlign = (ani) => api.post('api/previewTmdbAlign', ani)
```

- [ ] **Step 2: Add the dialog**

`TmdbAlignPreview.vue` shows the rows from the spec, in order: RSS item, TMDB episodes, current and new season and offset, sample file name, rename template, Emby choices or the fallback path, conflict text, `blockReason`, and `embyMessage`.

When `episodeChoices.length > 1`, show a radio group. When `embyChoices.length > 1`, show a radio group. The confirm button is enabled when `blockReason` is empty, a single episode is selected, that choice has `seasonConflict` false, and either there is at most one Emby choice or one Emby radio is selected. Server `canConfirm` true is the initial enabled state for the unique case.

Confirm copies fields onto `props.ani` and does not POST a save:

```javascript
const choice = preview.value.episodeChoices[episodeIndex.value]
props.ani.season = choice.newSeason
props.ani.offset = choice.newOffset
props.ani.customRenameTemplateEnable = true
props.ani.customRenameTemplate = preview.value.renameTemplate
props.ani.themoviedbName = preview.value.themoviedbName
props.ani.customDownloadPath = true
if (preview.value.embyChoices.length > 0) {
  props.ani.customDownloadPathTemplate = preview.value.embyChoices[embyIndex.value].replacedPath
} else {
  props.ani.customDownloadPathTemplate = choice.fallbackPath
}
if (!props.ani.tmdb?.id && preview.value.tmdb) {
  const groupId = props.ani.tmdb?.tmdbGroupId
  props.ani.tmdb = preview.value.tmdb
  if (groupId) {
    props.ani.tmdb.tmdbGroupId = groupId
  }
}
```

Do not assign `props.ani.title`. Do not iterate `standbyRssList`.

Cancel closes the dialog and does not assign those fields.

- [ ] **Step 3: Add the button**

In `AniView.vue`, next to the `使用 TMDB` button, add:

```vue
<el-button bg text @click="tmdbAlignRef?.show(props.ani)">TMDB 对齐</el-button>
```

Register the component and a template ref. `show` calls `http.previewTmdbAlign` with the current `ani` and opens the dialog. Show the request error from the existing `http` helper and do not change the form when the request fails.

- [ ] **Step 4: Verify the form contract**

There is no Vue test runner in this repo. After the frontend build used by the app is available, open one existing subscription, click `TMDB 对齐`, and check the preview rows. Click cancel and confirm the title, season, offset, custom path, and rename template are unchanged. Click confirm on a subscription whose new season does not collide, then confirm the form shows the new season, offset, rename template, and path, while the title and standby RSS offsets stay as they were. Do not click the editor save during this check unless you intend to persist.

Also open 设置 → 媒体库管理 and add one prefix rule. Reload the settings page and confirm the rule is still there after the existing save.

- [ ] **Step 5: Commit**

```bash
cd /home/builder/develop/ani-rss
git add ani-rss-ui/src/js/http.js \
  ani-rss-ui/src/view/home/TmdbAlignPreview.vue \
  ani-rss-ui/src/view/home/AniView.vue
GIT_AUTHOR_NAME=2435043xia GIT_AUTHOR_EMAIL=2435043xia@gmail.com \
GIT_COMMITTER_NAME=2435043xia GIT_COMMITTER_EMAIL=2435043xia@gmail.com \
git commit -m "feat: 订阅编辑增加 TMDB 对齐预览"
```

### Task 8: Package a new image tag

Do this only after Task 7's manual check passed.

**Files:**
- Modify: none required for the tag itself. Read `<version>` from the root `pom.xml`. It is `3.2.39` unless a later commit changed it.

**Interfaces:**
- Consumes: a built `ani-rss-application/target/ani-rss.jar` and `docker/Dockerfile`.
- Produces: image `registry.888987.xyz/ani-rss:<version>-tmdb-align`.

- [ ] **Step 1: Build the jar and the UI the Dockerfile expects**

Use the repository's existing Maven package command that produces `ani-rss-application/target/ani-rss.jar`. Do not download a release jar over the existing source build.

- [ ] **Step 2: Build and push only the new tag**

```bash
cd /home/builder/develop/ani-rss
VERSION=$(grep -m1 '<version>' pom.xml | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/')
TAG="registry.888987.xyz/ani-rss:${VERSION}-tmdb-align"
docker build -f docker/Dockerfile -t "$TAG" .
docker push "$TAG"
```

Do not tag `latest`. Do not retag an image that is already running. If the push is rejected, stop and report the registry error.

- [ ] **Step 3: Report the tag**

Tell the user the full tag and that the running container was not switched.

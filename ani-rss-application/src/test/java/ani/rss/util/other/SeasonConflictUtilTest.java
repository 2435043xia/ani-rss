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

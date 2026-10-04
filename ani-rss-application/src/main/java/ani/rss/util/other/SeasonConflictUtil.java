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

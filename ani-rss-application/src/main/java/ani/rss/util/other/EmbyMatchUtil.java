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

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

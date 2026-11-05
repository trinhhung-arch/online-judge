package dev.oj.platform.web;

import java.util.List;
import java.util.function.Function;

public record CursorPage<T>(List<T> items, String nextCursor) {

    public CursorPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean hasMore() {
        return nextCursor != null;
    }

    public static <T> CursorPage<T> last(List<T> items) {
        return new CursorPage<>(items, null);
    }

    public static <T> CursorPage<T> of(List<T> fetched, int pageSize, Function<T, String> cursorOf) {
        if (fetched.size() <= pageSize) {
            return last(fetched);
        }
        List<T> page = List.copyOf(fetched.subList(0, pageSize));
        return new CursorPage<>(page, cursorOf.apply(page.get(pageSize - 1)));
    }

    public static int clampSize(Integer requested, int defaultSize, int maxSize) {
        if (requested == null || requested < 1) {
            return defaultSize;
        }
        return Math.min(requested, maxSize);
    }
}

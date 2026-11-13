package ro.kf.sdk;

import java.util.List;
import java.util.Map;

/**
 * One page of a list read ({@link ReadsClient#list}, protocol 1.1 §15.1): the objects of the port in the platform's
 * fixed order ({@code id} ascending), as JSON maps (numbers with a fraction as {@link java.math.BigDecimal}, exactly as
 * sent; {@code null} kept).
 *
 * @param items the objects of this page, never null
 * @param page  the page number, from 0
 * @param size  the page size the platform applied
 * @param total how many objects the whole list has
 */
public record ReadPage(List<Map<String, Object>> items, int page, int size, long total) {

    public ReadPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Whether a later page has objects: {@code (page + 1) * size < total}. */
    public boolean hasNext() {
        return (long) (page + 1) * size < total;
    }
}

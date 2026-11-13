package ro.kf.sdk;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** One line of a model's material list. */
public record Material(String material, BigDecimal quantity, String unit) {

    static Material fromMap(Map<?, ?> m) {
        Object q = m.get("quantity");
        return new Material(m.get("material") == null ? null : String.valueOf(m.get("material")),
                q == null ? null : new BigDecimal(q.toString()),
                m.get("unit") == null ? null : String.valueOf(m.get("unit")));
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("material", material);
        m.put("quantity", quantity);
        m.put("unit", unit);
        return m;
    }
}

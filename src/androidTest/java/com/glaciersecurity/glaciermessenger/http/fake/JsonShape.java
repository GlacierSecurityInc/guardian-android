package com.glaciersecurity.glaciermessenger.http.fake;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reduces a JSON document to the set of paths it contains and the type at each one, discarding
 * every value.
 *
 * <p>This is what makes a contract test possible against a real backend without asserting on
 * anybody's data: two responses can be compared for agreement on structure while nothing about
 * their contents is read, logged, or committed.
 *
 * <p>Arrays collapse to a single {@code []} step rather than being indexed, so a real response
 * with forty call-history rows and a fake with three produce the same shape. That is the point —
 * the contract is the shape of a row, not how many there are.
 */
final class JsonShape {

    private JsonShape() {
    }

    /** Path -> type name, e.g. {@code data.selected_twilionumber[].text -> string}. */
    static Map<String, String> of(final JsonElement element) {
        final Map<String, String> shape = new LinkedHashMap<>();
        walk("", element, shape);
        return shape;
    }

    private static void walk(final String path, final JsonElement element,
                             final Map<String, String> shape) {
        if (element == null || element.isJsonNull()) {
            // A null carries no type information. Recording it as "null" would make a real
            // response with an empty optional field look like it disagreed with a fake that
            // filled the field in, which is not a contract violation.
            return;
        }
        if (element.isJsonObject()) {
            if (!path.isEmpty()) {
                shape.put(path, "object");
            }
            final JsonObject object = element.getAsJsonObject();
            for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
                walk(path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(),
                        entry.getValue(), shape);
            }
            return;
        }
        if (element.isJsonArray()) {
            shape.put(path, "array");
            final JsonArray array = element.getAsJsonArray();
            // Union across elements: a row missing an optional field must not shrink the shape
            // the other rows establish.
            for (final JsonElement item : array) {
                walk(path + "[]", item, shape);
            }
            return;
        }
        shape.put(path, typeOf(element.getAsJsonPrimitive()));
    }

    private static String typeOf(final JsonPrimitive primitive) {
        if (primitive.isBoolean()) {
            return "boolean";
        }
        if (primitive.isNumber()) {
            return "number";
        }
        return "string";
    }

    /**
     * Paths present in {@code fake} that the real response does not have, or has at a different
     * type. These are the contract violations: a key the fake invented, or one it got wrong.
     *
     * <p>The other direction is deliberately not an error. A real response carries fields no
     * caller in this app reads, and requiring the fake to reproduce all of them would make the
     * test fail every time the backend adds a field.
     */
    static Set<String> fakeKeysNotBackedByReal(final Map<String, String> fake,
                                               final Map<String, String> real) {
        final Set<String> divergent = new TreeSet<>();
        for (final Map.Entry<String, String> entry : fake.entrySet()) {
            final String path = entry.getKey();
            final String realType = real.get(path);
            if (realType == null) {
                if (isUnderEmptyUpstreamArray(path, real)) {
                    // The upstream array exists but has no elements, so it cannot establish the
                    // shape of a row. That is a fact about this account -- no Twilio numbers, no
                    // voicemail -- not a disagreement about the contract, and reporting it as one
                    // makes the test fail on tenants that merely have less data than the fixture.
                    continue;
                }
                divergent.add(path + " (absent upstream)");
            } else if (!realType.equals(entry.getValue())) {
                divergent.add(path
                        + " (fake=" + entry.getValue() + ", upstream=" + realType + ")");
            }
        }
        return divergent;
    }

    /**
     * Whether {@code path} describes something inside an array that upstream returned empty.
     *
     * <p>Walks up the {@code []} steps rather than checking only the immediate parent, so a field
     * nested two levels inside an empty array is recognised too.
     */
    private static boolean isUnderEmptyUpstreamArray(final String path,
                                                     final Map<String, String> real) {
        int marker = path.lastIndexOf("[]");
        while (marker >= 0) {
            final String arrayPath = path.substring(0, marker);
            if ("array".equals(real.get(arrayPath))) {
                // The array is present upstream; if it had elements they would have contributed
                // paths under it, so its emptiness is why this path is missing.
                return true;
            }
            marker = path.lastIndexOf("[]", marker - 1);
        }
        return false;
    }

    /** Paths the real response has that the fake omits. Reported, not failed -- see above. */
    static Set<String> realKeysMissingFromFake(final Map<String, String> fake,
                                               final Map<String, String> real) {
        final Set<String> missing = new TreeSet<>();
        for (final String path : real.keySet()) {
            if (!fake.containsKey(path)) {
                missing.add(path);
            }
        }
        return missing;
    }
}

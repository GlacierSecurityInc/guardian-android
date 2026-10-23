package com.glaciersecurity.glaciermessenger.testutil;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An in-memory {@link SharedPreferences} for local unit tests.
 *
 * <p>There is no mocking framework on this project's offline dependency set, and
 * {@code returnDefaultValues} would make every getter answer {@code false}/{@code null}, which
 * cannot tell "stored false" apart from "nothing stored". So the store is real: values written
 * through the editor are visible to the getters, and {@link #contains} means what it says.
 *
 * <p>Editor writes are deferred until {@link Editor#apply()} or {@link Editor#commit()}, like
 * the real thing: code that stages a preference and then forgets to apply it must not appear to
 * have persisted anything. {@link FakeEditor#staged()} exposes the pending writes so a test can
 * assert on what was staged without applying it.
 */
public final class FakeSharedPreferences implements SharedPreferences {

    private final Map<String, Object> values = new HashMap<>();

    public FakeSharedPreferences putBooleanDirectly(final String key, final boolean value) {
        values.put(key, value);
        return this;
    }

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(values);
    }

    @Override
    public String getString(final String key, final String defValue) {
        final Object value = values.get(key);
        return value instanceof String ? (String) value : defValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(final String key, final Set<String> defValues) {
        final Object value = values.get(key);
        return value instanceof Set ? (Set<String>) value : defValues;
    }

    @Override
    public int getInt(final String key, final int defValue) {
        final Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : defValue;
    }

    @Override
    public long getLong(final String key, final long defValue) {
        final Object value = values.get(key);
        return value instanceof Long ? (Long) value : defValue;
    }

    @Override
    public float getFloat(final String key, final float defValue) {
        final Object value = values.get(key);
        return value instanceof Float ? (Float) value : defValue;
    }

    @Override
    public boolean getBoolean(final String key, final boolean defValue) {
        final Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : defValue;
    }

    @Override
    public boolean contains(final String key) {
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new FakeEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(
            final OnSharedPreferenceChangeListener listener) {}

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
            final OnSharedPreferenceChangeListener listener) {}

    /**
     * Collects changes and applies them to the enclosing store only on {@code apply()} or
     * {@code commit()}. A clear() staged before later puts is applied first, as the real editor
     * does, regardless of call order.
     */
    public final class FakeEditor implements Editor {

        private final Map<String, Object> staged = new HashMap<>();
        private final Set<String> removals = new HashSet<>();
        private boolean cleared = false;

        /** The writes this editor is holding, not yet applied. */
        public Map<String, Object> staged() {
            return new HashMap<>(staged);
        }

        private Editor put(final String key, final Object value) {
            removals.remove(key);
            staged.put(key, value);
            return this;
        }

        @Override
        public Editor putString(final String key, final String value) {
            return put(key, value);
        }

        @Override
        public Editor putStringSet(final String key, final Set<String> value) {
            return put(key, value);
        }

        @Override
        public Editor putInt(final String key, final int value) {
            return put(key, value);
        }

        @Override
        public Editor putLong(final String key, final long value) {
            return put(key, value);
        }

        @Override
        public Editor putFloat(final String key, final float value) {
            return put(key, value);
        }

        @Override
        public Editor putBoolean(final String key, final boolean value) {
            return put(key, value);
        }

        @Override
        public Editor remove(final String key) {
            staged.remove(key);
            removals.add(key);
            return this;
        }

        @Override
        public Editor clear() {
            cleared = true;
            staged.clear();
            removals.clear();
            return this;
        }

        @Override
        public boolean commit() {
            if (cleared) {
                values.clear();
            }
            for (final String key : removals) {
                values.remove(key);
            }
            values.putAll(staged);
            return true;
        }

        @Override
        public void apply() {
            commit();
        }
    }
}

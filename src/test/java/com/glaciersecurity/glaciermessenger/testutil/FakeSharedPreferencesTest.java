package com.glaciersecurity.glaciermessenger.testutil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

/**
 * The fake is a test helper, but code under test is judged against its behaviour -- so the ways
 * it has to match the real {@link SharedPreferences} are pinned here.
 */
public class FakeSharedPreferencesTest {

    private FakeSharedPreferences preferences;

    @Before
    public void setUp() {
        preferences = new FakeSharedPreferences();
    }

    /**
     * The point of deferring: production code that stages a write and never applies it must not
     * look like it persisted anything.
     */
    @Test
    public void aStagedWriteIsInvisibleUntilItIsApplied() {
        final SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("flag", true);

        assertFalse(preferences.getBoolean("flag", false));
        assertFalse(preferences.contains("flag"));

        editor.apply();

        assertTrue(preferences.getBoolean("flag", false));
        assertTrue(preferences.contains("flag"));
    }

    @Test
    public void commitAppliesTheSameWayApplyDoes() {
        preferences.edit().putString("name", "acme").commit();

        assertEquals("acme", preferences.getString("name", null));
    }

    @Test
    public void stagedExposesPendingWritesWithoutApplyingThem() {
        final FakeSharedPreferences.FakeEditor editor =
                (FakeSharedPreferences.FakeEditor) preferences.edit();
        editor.putBoolean("flag", true).putInt("count", 3);

        assertEquals(Boolean.TRUE, editor.staged().get("flag"));
        assertEquals(Integer.valueOf(3), editor.staged().get("count"));
        assertFalse(preferences.contains("flag"));
    }

    @Test
    public void aRemovalIsAlsoDeferred() {
        preferences.putBooleanDirectly("flag", true);
        final SharedPreferences.Editor editor = preferences.edit().remove("flag");

        assertTrue(preferences.contains("flag"));

        editor.apply();

        assertFalse(preferences.contains("flag"));
    }

    /** clear() applies before the puts staged alongside it, whatever order they were called in. */
    @Test
    public void clearAppliesBeforeWritesStagedInTheSameEditor() {
        preferences.putBooleanDirectly("old", true);

        preferences.edit().putString("new", "kept").clear().putString("newer", "also kept").apply();

        assertFalse(preferences.contains("old"));
        assertFalse("clear() drops writes staged before it", preferences.contains("new"));
        assertEquals("also kept", preferences.getString("newer", null));
    }

    @Test
    public void getterDefaultsApplyWhenNothingIsStored() {
        assertEquals("fallback", preferences.getString("missing", "fallback"));
        assertEquals(7, preferences.getInt("missing", 7));
        assertEquals(7L, preferences.getLong("missing", 7L));
        assertTrue(preferences.getBoolean("missing", true));
        assertTrue(preferences.getAll().isEmpty());
    }

    /** A stored false is not the same as nothing stored -- the distinction a mock would lose. */
    @Test
    public void aStoredFalseIsDistinguishableFromNothingStored() {
        preferences.edit().putBoolean("flag", false).apply();

        assertTrue(preferences.contains("flag"));
        assertFalse(preferences.getBoolean("flag", true));
    }
}

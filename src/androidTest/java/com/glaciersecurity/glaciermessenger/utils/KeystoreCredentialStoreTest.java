package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.KeyStore;

/**
 * {@link KeystoreCredentialStore} is the one piece of this app that cannot be tested anywhere but
 * on a device. It wraps values with an AES/GCM key held in {@code AndroidKeyStore}, and there is
 * no AndroidKeyStore provider on the JVM -- under Robolectric every call here fails at
 * {@code KeyStore.getInstance("AndroidKeyStore")}, so the JVM tier cannot even reach the code.
 *
 * <p>What makes it worth the trouble is that two separate things depend on its exact failure
 * behaviour. {@code DatabaseBackend.loadOwnIdentityKeyPair} reads a {@code null} from
 * {@link KeystoreCredentialStore#decryptString} as "this row is not Keystore-wrapped yet, migrate
 * it", so a change that made decryptString throw instead of returning null would silently stop
 * migrating OMEMO identity keys. And {@link KeystoreCredentialStore#remove} has to delete the key
 * as well as the ciphertext, or a later credential reusing the id would decrypt with a stale key.
 */
@RunWith(AndroidJUnit4.class)
public class KeystoreCredentialStoreTest {

    private static final String ID = "instrumentation_credential";
    private static final String OTHER_ID = "instrumentation_credential_other";

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    /**
     * AndroidKeyStore entries outlive the app's data directory: {@code pm clear} between tests
     * removes the SharedPreferences holding the ciphertext but leaves the keys behind, so without
     * this a run would slowly fill the device's keystore with per-test aliases.
     */
    @After
    public void removeKeysThisTestCreated() {
        KeystoreCredentialStore.remove(context, ID);
        KeystoreCredentialStore.remove(context, OTHER_ID);
    }

    @Test
    public void aStoredCredentialComesBackUnchanged() {
        final String secret = "correct horse battery staple";

        assertThat(KeystoreCredentialStore.store(context, ID, secret)).isTrue();

        assertThat(KeystoreCredentialStore.retrieve(context, ID)).isEqualTo(secret);
    }

    @Test
    public void whatLandsInSharedPreferencesIsNotThePlaintext() {
        final String secret = "correct horse battery staple";
        KeystoreCredentialStore.store(context, ID, secret);

        // Read the raw preference rather than going back through retrieve(), so this asserts the
        // thing that actually matters: what an attacker with the app's data directory finds.
        final String stored =
                context.getSharedPreferences("glacier_keystore_creds", Context.MODE_PRIVATE)
                        .getString("glacier_cred_" + ID, null);

        assertWithMessage("ciphertext for '%s' in glacier_keystore_creds", ID)
                .that(stored)
                .isNotNull();
        assertThat(stored).isNotEqualTo(secret);
        assertThat(new String(Base64.decode(stored, Base64.NO_WRAP))).doesNotContain(secret);
    }

    @Test
    public void twoCredentialIdsDoNotShareAKey() {
        KeystoreCredentialStore.store(context, ID, "first");
        KeystoreCredentialStore.store(context, OTHER_ID, "second");

        assertThat(KeystoreCredentialStore.retrieve(context, ID)).isEqualTo("first");
        assertThat(KeystoreCredentialStore.retrieve(context, OTHER_ID)).isEqualTo("second");
    }

    @Test
    public void aCiphertextFromOneIdDoesNotDecryptUnderAnother() {
        final String encoded = KeystoreCredentialStore.encryptString(ID, "first");
        assertThat(encoded).isNotNull();

        // Give OTHER_ID a key of its own, so this is a genuine wrong-key decrypt rather than the
        // no-key-at-all path tested below.
        KeystoreCredentialStore.encryptString(OTHER_ID, "second");

        assertThat(KeystoreCredentialStore.decryptString(OTHER_ID, encoded)).isNull();
    }

    @Test
    public void retrievingAnUnknownCredentialIsNullRatherThanAThrow() {
        assertThat(KeystoreCredentialStore.retrieve(context, "no_such_credential")).isNull();
    }

    @Test
    public void removeDeletesTheKeystoreEntryAndNotJustTheCiphertext() throws Exception {
        KeystoreCredentialStore.store(context, ID, "first");

        KeystoreCredentialStore.remove(context, ID);

        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        assertWithMessage("keystore alias after remove()")
                .that(keyStore.containsAlias("glacier_cred_" + ID))
                .isFalse();
        assertThat(KeystoreCredentialStore.retrieve(context, ID)).isNull();
    }

    @Test
    public void removeAllClearsEveryCredential() {
        KeystoreCredentialStore.store(context, ID, "first");
        KeystoreCredentialStore.store(context, OTHER_ID, "second");

        KeystoreCredentialStore.removeAll(context);

        assertThat(KeystoreCredentialStore.retrieve(context, ID)).isNull();
        assertThat(KeystoreCredentialStore.retrieve(context, OTHER_ID)).isNull();
    }

    /** A database key must survive logout, or the history file it unlocks is lost for good. */
    @Test
    public void removeAllKeepsDatabaseKeys() {
        final String databaseId = "database_instrumentation_key";
        try {
            KeystoreCredentialStore.store(context, ID, "first");
            KeystoreCredentialStore.store(context, databaseId, "key material");

            KeystoreCredentialStore.removeAll(context);

            assertThat(KeystoreCredentialStore.retrieve(context, ID)).isNull();
            assertThat(KeystoreCredentialStore.retrieve(context, databaseId))
                    .isEqualTo("key material");
        } finally {
            KeystoreCredentialStore.remove(context, databaseId);
        }
    }

    @Test
    public void aTamperedCiphertextIsRejectedRatherThanReturnedGarbled() {
        final String encoded = KeystoreCredentialStore.encryptString(ID, "first");
        final byte[] blob = Base64.decode(encoded, Base64.NO_WRAP);
        // Flip a bit in the final byte, which is inside the GCM tag.
        blob[blob.length - 1] ^= 0x01;
        final String tampered = Base64.encodeToString(blob, Base64.NO_WRAP);

        assertWithMessage("GCM must authenticate, not just decrypt")
                .that(KeystoreCredentialStore.decryptString(ID, tampered))
                .isNull();
    }

    @Test
    public void encryptStringIsNotDeterministic() {
        // A fresh IV per call. Two encryptions of the same plaintext under the same id that came
        // back equal would mean the IV is fixed, which is the one way to break AES-GCM outright.
        final String first = KeystoreCredentialStore.encryptString(ID, "same plaintext");
        final String second = KeystoreCredentialStore.encryptString(ID, "same plaintext");

        assertThat(first).isNotEqualTo(second);
        assertThat(KeystoreCredentialStore.decryptString(ID, first)).isEqualTo("same plaintext");
        assertThat(KeystoreCredentialStore.decryptString(ID, second)).isEqualTo("same plaintext");
    }

    @Test
    public void decryptStringReturnsNullWhenNoKeyExistsForTheId() {
        // This is the contract DatabaseBackend.loadOwnIdentityKeyPair depends on to recognise a
        // row whose key is not Keystore-wrapped yet and migrate it. If this ever starts throwing,
        // or starts returning the input unchanged, that migration stops happening and the failure
        // is silent -- the key simply never gets wrapped.
        assertThat(KeystoreCredentialStore.decryptString("never_used_id", "cGxhaW50ZXh0"))
                .isNull();
    }

    @Test
    public void aValueSurvivesAnAliasBeingReusedAfterRemoval() {
        KeystoreCredentialStore.store(context, ID, "first");
        KeystoreCredentialStore.remove(context, ID);

        // Same id again: must generate a new key rather than fail because the alias once existed.
        assertThat(KeystoreCredentialStore.store(context, ID, "second")).isTrue();
        assertThat(KeystoreCredentialStore.retrieve(context, ID)).isEqualTo("second");
    }
}

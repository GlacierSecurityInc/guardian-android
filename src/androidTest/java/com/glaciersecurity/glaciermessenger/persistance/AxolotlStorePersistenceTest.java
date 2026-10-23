package com.glaciersecurity.glaciermessenger.persistance;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.crypto.axolotl.FingerprintStatus;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.testutil.DeviceTestFixtures;
import com.glaciersecurity.glaciermessenger.utils.CryptoHelper;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.util.List;
import java.util.Set;

/**
 * The OMEMO key store, against real SQLite and the real libsignal native library.
 *
 * <p>Neither half of that exists off a device. {@code org.signal:libsignal-client} on the JVM
 * classpath has no {@code libsignal_jni.so} to load -- the build explicitly strips the desktop
 * natives out of the APK as dead weight -- so every {@code Curve.generateKeyPair()} here would
 * die in a JVM test before it reached a single assertion. That is why this is the tier that
 * covers the store.
 *
 * <p>The identity-key tests matter most: {@code storeOwnIdentityKeyPair} wraps the private key
 * with an AndroidKeyStore key and {@code loadOwnIdentityKeyPair} unwraps it, so a round trip
 * through the database is also a round trip through the Keystore. A break there loses the user's
 * OMEMO identity, which cannot be regenerated without every contact re-verifying them.
 */
@RunWith(AndroidJUnit4.class)
public class AxolotlStorePersistenceTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private DatabaseBackend db;
    private Account account;

    @BeforeClass
    public static void setUpStringprep() {
        DeviceTestFixtures.setUpStringprep();
    }

    @Before
    public void createAccount() {
        db = DatabaseBackend.getInstance(context);
        account = DeviceTestFixtures.newAccount();
        db.createAccount(account);
    }

    private static IdentityKeyPair generateIdentityKeyPair() {
        final ECKeyPair keys = Curve.generateKeyPair();
        return new IdentityKeyPair(new IdentityKey(keys.getPublicKey()), keys.getPrivateKey());
    }

    private static SignedPreKeyRecord generateSignedPreKey(
            final IdentityKeyPair identityKeyPair, final int id) throws Exception {
        final ECKeyPair keyPair = Curve.generateKeyPair();
        final byte[] signature =
                Curve.calculateSignature(
                        identityKeyPair.getPrivateKey(), keyPair.getPublicKey().serialize());
        return new SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature);
    }

    // ---- pre-keys ----------------------------------------------------------------------

    @Test
    public void aPreKeyRoundTripsThroughTheDatabase() throws Exception {
        final PreKeyRecord record = new PreKeyRecord(41, Curve.generateKeyPair());

        db.storePreKey(account, record);

        assertThat(db.containsPreKey(account, 41)).isTrue();
        final PreKeyRecord loaded = db.loadPreKey(account, 41);
        assertThat(loaded.getId()).isEqualTo(41);
        assertThat(loaded.serialize()).isEqualTo(record.serialize());
    }

    @Test
    public void deletingAPreKeyRemovesExactlyThatOne() throws Exception {
        db.storePreKey(account, new PreKeyRecord(41, Curve.generateKeyPair()));
        db.storePreKey(account, new PreKeyRecord(42, Curve.generateKeyPair()));

        assertThat(db.deletePreKey(account, 41)).isEqualTo(1);

        assertThat(db.containsPreKey(account, 41)).isFalse();
        assertThat(db.containsPreKey(account, 42)).isTrue();
    }

    @Test
    public void preKeysDoNotLeakBetweenAccounts() throws Exception {
        final Account other = DeviceTestFixtures.newAccount("otheruser");
        db.createAccount(other);
        db.storePreKey(account, new PreKeyRecord(41, Curve.generateKeyPair()));

        assertWithMessage("a pre-key stored for one account must not be visible to another")
                .that(db.containsPreKey(other, 41))
                .isFalse();
    }

    // ---- signed pre-keys ---------------------------------------------------------------

    @Test
    public void aSignedPreKeyRoundTripsAndIsCounted() throws Exception {
        final IdentityKeyPair identity = generateIdentityKeyPair();
        final SignedPreKeyRecord record = generateSignedPreKey(identity, 7);

        db.storeSignedPreKey(account, record);

        assertThat(db.getSignedPreKeysCount(account)).isEqualTo(1);
        assertThat(db.loadSignedPreKey(account, 7).serialize()).isEqualTo(record.serialize());
        assertThat(db.loadSignedPreKeys(account)).hasSize(1);
    }

    @Test
    public void aStoredSignedPreKeyStillVerifiesAgainstTheIdentityThatSignedIt() throws Exception {
        final IdentityKeyPair identity = generateIdentityKeyPair();
        db.storeSignedPreKey(account, generateSignedPreKey(identity, 7));

        final SignedPreKeyRecord loaded = db.loadSignedPreKey(account, 7);

        // Base64 in, Base64 out: a truncation or an encoding flag change would corrupt the
        // signature, and nothing else in the suite would notice until a real OMEMO session
        // failed to build against a live contact.
        assertThat(
                        Curve.verifySignature(
                                identity.getPublicKey().getPublicKey(),
                                loaded.getKeyPair().getPublicKey().serialize(),
                                loaded.getSignature()))
                .isTrue();
    }

    @Ignore(
            "Known defect: DatabaseBackend.containsSignedPreKey calls getCursorForPreKey, so it"
                + " queries the `prekeys` table instead of `signed_prekeys`. Pre-key ids and"
                + " signed-pre-key ids come from different sequences, so the answer is unrelated"
                + " to the question -- false for a signed pre-key that exists, true for one that"
                + " does not. Reached from SQLiteAxolotlStore.containsSignedPreKey, which is"
                + " libsignal's SignedPreKeyStore interface method. Un-ignore when the one-line"
                + " fix lands.")
    @Test
    public void containsSignedPreKeyLooksInTheSignedPreKeyTable() throws Exception {
        final IdentityKeyPair identity = generateIdentityKeyPair();
        db.storeSignedPreKey(account, generateSignedPreKey(identity, 7));

        assertThat(db.containsSignedPreKey(account, 7)).isTrue();
    }

    @Test
    public void deletingASignedPreKeyDropsTheCount() throws Exception {
        final IdentityKeyPair identity = generateIdentityKeyPair();
        db.storeSignedPreKey(account, generateSignedPreKey(identity, 7));

        db.deleteSignedPreKey(account, 7);

        assertThat(db.loadSignedPreKey(account, 7)).isNull();
        assertThat(db.getSignedPreKeysCount(account)).isEqualTo(0);
    }

    // ---- sessions ----------------------------------------------------------------------

    @Test
    public void aSessionRoundTripsAndIsFoundByDeviceId() {
        final SignalProtocolAddress address =
                new SignalProtocolAddress("alice@" + DeviceTestFixtures.DOMAIN, 42);
        final SessionRecord session = new SessionRecord();

        db.storeSession(account, address, session);

        assertThat(db.containsSession(account, address)).isTrue();
        assertThat(db.loadSession(account, address).serialize()).isEqualTo(session.serialize());
        assertThat(db.getSubDeviceSessions(account, address)).contains(42);
    }

    @Test
    public void deleteAllSessionsRemovesEveryDeviceForThatContact() {
        final String name = "alice@" + DeviceTestFixtures.DOMAIN;
        db.storeSession(account, new SignalProtocolAddress(name, 42), new SessionRecord());
        db.storeSession(account, new SignalProtocolAddress(name, 43), new SessionRecord());

        db.deleteAllSessions(account, new SignalProtocolAddress(name, 42));

        assertThat(db.containsSession(account, new SignalProtocolAddress(name, 42))).isFalse();
        assertThat(db.containsSession(account, new SignalProtocolAddress(name, 43))).isFalse();
    }

    @Test
    public void deleteSessionRemovesOnlyTheNamedDevice() {
        final String name = "alice@" + DeviceTestFixtures.DOMAIN;
        db.storeSession(account, new SignalProtocolAddress(name, 42), new SessionRecord());
        db.storeSession(account, new SignalProtocolAddress(name, 43), new SessionRecord());

        db.deleteSession(account, new SignalProtocolAddress(name, 42));

        assertThat(db.containsSession(account, new SignalProtocolAddress(name, 42))).isFalse();
        assertThat(db.containsSession(account, new SignalProtocolAddress(name, 43))).isTrue();
    }

    // ---- identity keys -----------------------------------------------------------------

    @Test
    public void theOwnIdentityKeyPairSurvivesKeystoreWrapping() {
        final IdentityKeyPair identity = generateIdentityKeyPair();

        db.storeOwnIdentityKeyPair(account, identity);

        final IdentityKeyPair loaded = db.loadOwnIdentityKeyPair(account);
        assertWithMessage("own identity key after a Keystore wrap/unwrap round trip")
                .that(loaded)
                .isNotNull();
        assertThat(loaded.serialize()).isEqualTo(identity.serialize());
    }

    @Test
    public void theOwnPrivateKeyIsNotSittingInTheDatabaseInTheClear() {
        final IdentityKeyPair identity = generateIdentityKeyPair();
        db.storeOwnIdentityKeyPair(account, identity);

        final String encodedIdentity =
                android.util.Base64.encodeToString(
                        identity.serialize(), android.util.Base64.DEFAULT);
        try (android.database.Cursor cursor =
                db.getReadableDatabase()
                        .rawQuery("SELECT key FROM identities WHERE ownkey=1", null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            assertWithMessage("identities.key must hold Keystore ciphertext, not the plain key")
                    .that(cursor.getString(0))
                    .isNotEqualTo(encodedIdentity);
        }
    }

    @Test
    public void aContactIdentityKeyIsStoredWithItsTrustState() {
        final IdentityKey key = generateIdentityKeyPair().getPublicKey();
        final String name = "alice@" + DeviceTestFixtures.DOMAIN;

        db.storeIdentityKey(account, name, key, FingerprintStatus.createActiveUndecided());

        final Set<IdentityKey> keys = db.loadIdentityKeys(account, name);
        assertThat(keys).hasSize(1);
        assertThat(keys.iterator().next().serialize()).isEqualTo(key.serialize());
    }

    @Test
    public void trustCanBeRaisedAndIsReadBack() {
        final IdentityKey key = generateIdentityKeyPair().getPublicKey();
        final String name = "alice@" + DeviceTestFixtures.DOMAIN;
        db.storeIdentityKey(account, name, key, FingerprintStatus.createActiveUndecided());
        // The fingerprint column is written by storeIdentityKey as the hex of the EC public
        // key, not libsignal's own display fingerprint -- setIdentityKeyTrust looks the row up
        // by exactly that string.
        final String fingerprint = CryptoHelper.bytesToHex(key.getPublicKey().serialize());

        db.setIdentityKeyTrust(account, fingerprint, FingerprintStatus.createActiveTrusted());

        final FingerprintStatus status = db.getFingerprintStatus(account, fingerprint);
        assertThat(status).isNotNull();
        assertThat(status.isTrusted()).isTrue();
        assertThat(db.numTrustedKeys(account, name)).isEqualTo(1);
    }

    @Test
    public void wipeAxolotlDbClearsEverySectionOfTheStore() throws Exception {
        final IdentityKeyPair identity = generateIdentityKeyPair();
        db.storeOwnIdentityKeyPair(account, identity);
        db.storePreKey(account, new PreKeyRecord(41, Curve.generateKeyPair()));
        db.storeSignedPreKey(account, generateSignedPreKey(identity, 7));
        db.storeSession(
                account,
                new SignalProtocolAddress("alice@" + DeviceTestFixtures.DOMAIN, 42),
                new SessionRecord());

        db.wipeAxolotlDb(account);

        assertThat(db.containsPreKey(account, 41)).isFalse();
        // Deliberately not containsSignedPreKey: it reads the wrong table (see the ignored test
        // above), so here it would report "gone" whether the wipe worked or not.
        assertThat(db.getSignedPreKeysCount(account)).isEqualTo(0);
        assertThat(
                        db.containsSession(
                                account,
                                new SignalProtocolAddress(
                                        "alice@" + DeviceTestFixtures.DOMAIN, 42)))
                .isFalse();
        final List<String> addresses = db.getKnownSignalAddresses(account);
        assertThat(addresses).isEmpty();
    }
}

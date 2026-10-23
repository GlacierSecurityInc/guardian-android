package com.glaciersecurity.glaciermessenger.crypto.axolotl;

import com.glaciersecurity.glaciermessenger.utils.Log;
import android.util.LruCache;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.util.KeyHelper;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.utils.CryptoHelper;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

public class SQLiteAxolotlStore implements SignalProtocolStore {

    public static final String PREKEY_TABLENAME = "prekeys";
    public static final String SIGNED_PREKEY_TABLENAME = "signed_prekeys";
    public static final String SESSION_TABLENAME = "sessions";
    public static final String IDENTITIES_TABLENAME = "identities";
    public static final String ACCOUNT = "account";
    public static final String DEVICE_ID = "device_id";
    public static final String ID = "id";
    public static final String KEY = "key";
    public static final String FINGERPRINT = "fingerprint";
    public static final String NAME = "name";
    public static final String TRUSTED = "trusted"; // no longer used
    public static final String TRUST = "trust";
    public static final String ACTIVE = "active";
    public static final String LAST_ACTIVATION = "last_activation";
    public static final String OWN = "ownkey";
    public static final String CERTIFICATE = "certificate";

    public static final String JSONKEY_REGISTRATION_ID = "axolotl_reg_id";
    public static final String JSONKEY_CURRENT_PREKEY_ID = "axolotl_cur_prekey_id";

    private static final int NUM_TRUSTS_TO_CACHE = 100;

    private final Account account;
    private final XmppConnectionService mXmppConnectionService;

    private IdentityKeyPair identityKeyPair;
    private int localRegistrationId;
    private int currentPreKeyId = 0;

    private final HashSet<Integer> preKeysMarkedForRemoval = new HashSet<>();

    private final LruCache<String, FingerprintStatus> trustCache =
            new LruCache<String, FingerprintStatus>(NUM_TRUSTS_TO_CACHE) {
                @Override
                protected FingerprintStatus create(String fingerprint) {
                    return mXmppConnectionService.databaseBackend.getFingerprintStatus(account, fingerprint);
                }
            };

    // per-JID lock map
    //
    // saveIdentity() and isTrustedIdentity() each perform a two-step sequence:
    //   1. hasVerifiedKeys(jid)          ← read
    //   2. storeIdentityKey(…, status)   ← write
    //
    // Without synchronisation, two threads racing for the same JID can both
    // see "no verified keys" in step 1 and both write Trust.TRUSTED in step 2,
    // even if one thread's verified write should have changed the outcome for
    // the other.
    //
    // Fix: one ReentrantLock per normalised JID name, stored in a
    // ConcurrentHashMap so lock acquisition for different JIDs is independent
    // (no global bottleneck). Lock objects are never removed from the map;
    // their number is bounded by roster size and they are negligibly small.
    private final ConcurrentHashMap<String, ReentrantLock> jidTrustLocks =
            new ConcurrentHashMap<>();

    /** Returns (creating if necessary) the per-JID trust lock. */
    private ReentrantLock trustLockFor(final String jidName) {
        return jidTrustLocks.computeIfAbsent(jidName, k -> new ReentrantLock());
    }
    // ─────────────────────────────────────────────────────────────────────────

    private static IdentityKeyPair generateIdentityKeyPair() {
        Log.i(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Generating axolotl IdentityKeyPair...");
        ECKeyPair identityKeyPairKeys = Curve.generateKeyPair();
        return new IdentityKeyPair(new IdentityKey(identityKeyPairKeys.getPublicKey()),
                identityKeyPairKeys.getPrivateKey());
    }

    private static int generateRegistrationId() {
        Log.i(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Generating axolotl registration ID...");
        return KeyHelper.generateRegistrationId(true);
    }

    public SQLiteAxolotlStore(Account account, XmppConnectionService service) {
        this.account = account;
        this.mXmppConnectionService = service;
        this.localRegistrationId = loadRegistrationId();
        this.currentPreKeyId = loadCurrentPreKeyId();
    }

    public int getCurrentPreKeyId() {
        return currentPreKeyId;
    }

    // --------------------------------------
    // IdentityKeyStore
    // --------------------------------------

    private IdentityKeyPair loadIdentityKeyPair() {
        synchronized (mXmppConnectionService) {
            IdentityKeyPair ownKey = mXmppConnectionService.databaseBackend.loadOwnIdentityKeyPair(account);

            if (ownKey != null) {
                return ownKey;
            } else {
                Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Could not retrieve own IdentityKeyPair");
                ownKey = generateIdentityKeyPair();
                mXmppConnectionService.databaseBackend.storeOwnIdentityKeyPair(account, ownKey);
            }
            return ownKey;
        }
    }

    private int loadRegistrationId() {
        return loadRegistrationId(false);
    }

    private int loadRegistrationId(boolean regenerate) {
        String regIdString = this.account.getKey(JSONKEY_REGISTRATION_ID);
        Integer storedRegId = null;
        if (!regenerate && regIdString != null) {
            try {
                storedRegId = Integer.valueOf(regIdString);
            } catch (final NumberFormatException e) {
                Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Unparseable axolotl registration id; regenerating");
            }
        }
        int reg_id;
        if (storedRegId != null) {
            reg_id = storedRegId;
        } else {
            Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Could not retrieve axolotl registration id for account " + account.getJid());
            reg_id = generateRegistrationId();
            boolean success = this.account.setKey(JSONKEY_REGISTRATION_ID, Integer.toString(reg_id));
            if (success) {
                mXmppConnectionService.databaseBackend.updateAccount(account);
            } else {
                Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Failed to write new key to the database!");
            }
        }
        return reg_id;
    }

    private int loadCurrentPreKeyId() {
        String prekeyIdString = this.account.getKey(JSONKEY_CURRENT_PREKEY_ID);
        Integer storedPrekeyId = null;
        if (prekeyIdString != null) {
            try {
                storedPrekeyId = Integer.valueOf(prekeyIdString);
            } catch (final NumberFormatException e) {
                Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Unparseable current prekey id; starting from 0");
            }
        }
        int prekey_id;
        if (storedPrekeyId != null) {
            prekey_id = storedPrekeyId;
        } else {
            Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Could not retrieve current prekey id for account " + account.getJid());
            prekey_id = 0;
        }
        return prekey_id;
    }

    public void regenerate() {
        mXmppConnectionService.databaseBackend.wipeAxolotlDb(account);
        trustCache.evictAll();
        account.setKey(JSONKEY_CURRENT_PREKEY_ID, Integer.toString(0));
        identityKeyPair = loadIdentityKeyPair();
        localRegistrationId = loadRegistrationId(true);
        currentPreKeyId = 0;
        mXmppConnectionService.updateAccountUi();
    }

    /**
     * Get the local client's identity key pair.
     *
     * @return The local client's persistent identity key pair.
     */
    @Override
    public IdentityKeyPair getIdentityKeyPair() {
        if (identityKeyPair == null) {
            identityKeyPair = loadIdentityKeyPair();
        }
        return identityKeyPair;
    }

    /**
     * Return the local client's registration ID.
     * <p/>
     * Clients should maintain a registration ID, a random number
     * between 1 and 16380 that's generated once at install time.
     *
     * @return the local client's registration ID.
     */
    @Override
    public int getLocalRegistrationId() {
        return localRegistrationId;
    }

    /**
     * Save a remote client's identity key.
     *
     * <p>Stores the key as trusted (BTBV) or undecided (TOFU) depending on the
     * current policy and whether the contact already has verified keys.
     *
     *
     * <p>The BTBV "check + write" sequence is protected by a per-JID
     * {@link ReentrantLock} so that concurrent calls for the same address
     * cannot race between {@code hasVerifiedKeys} and {@code storeIdentityKey}.
     *
     * @param address     The address of the remote client.
     * @param identityKey The remote client's identity key.
     * @return {@link IdentityChange#REPLACED_EXISTING} if this address previously had a
     * different identity key on file, {@link IdentityChange#NEW_OR_UNCHANGED} otherwise.
     */
    @Override
    public IdentityChange saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
        final Set<IdentityKey> priorKeys =
                mXmppConnectionService.databaseBackend.loadIdentityKeys(account, address.getName());
        if (priorKeys.contains(identityKey)) {
            // Key is already known — nothing to write, no trust decision needed.
            return IdentityChange.NEW_OR_UNCHANGED;
        }
        final IdentityChange change =
                priorKeys.isEmpty() ? IdentityChange.NEW_OR_UNCHANGED : IdentityChange.REPLACED_EXISTING;

        final String fingerprint =
                CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize());

        // hold the per-JID lock for the entire check + write
        final ReentrantLock lock = trustLockFor(address.getName());
        lock.lock();
        try {
            // Re-check inside the lock: another thread may have stored the key
            // between our outer contains() check and here.
            if (mXmppConnectionService.databaseBackend
                    .loadIdentityKeys(account, address.getName()).contains(identityKey)) {
                return IdentityChange.NEW_OR_UNCHANGED;
            }

            FingerprintStatus status = getFingerprintStatus(fingerprint);
            if (status == null) {
                // hasVerifiedKeys is read while holding the lock, so a concurrent
                // storeIdentityKey(VERIFIED) on another thread cannot sneak in
                // between this check and the write below.
                if (mXmppConnectionService.blindTrustBeforeVerification()
                        && !account.getAxolotlService().hasVerifiedKeys(address.getName())) {
                    status = FingerprintStatus.createActiveTrusted();
                } else {
                    status = FingerprintStatus.createActiveUndecided();
                }
            } else {
                status = status.toActive();
            }

            mXmppConnectionService.databaseBackend.storeIdentityKey(
                    account, address.getName(), identityKey, status);
            trustCache.remove(fingerprint);
        } finally {
            lock.unlock();
        }
        // ────────────────────────────────────────────────────────────────────

        return change;
    }

    /**
     * Verify a remote client's identity key.
     *
     * <p>Implements Trust-On-First-Use (TOFU) with optional Blind Trust Before
     * Verification (BTBV), mirroring the policy applied in
     * {@link #saveIdentity(SignalProtocolAddress, IdentityKey)}.
     *
     * <p>Decision table:
     * <pre>
     *  Status in DB   | Result
     *  ───────────────┼────────────────────────────────────────────────────
     *  null (unknown) │ BTBV on  + no verified keys for address → true
     *                 │ BTBV off | verified key exists for address → false
     *  TRUSTED        │ true   (BTBV-accepted key)
     *  VERIFIED       │ true   (user manually verified)
     *  VERIFIED_X509  │ true   (X.509-verified)
     *  UNDECIDED      │ false  (seen but not yet accepted by user)
     *  UNTRUSTED      │ false  (user explicitly untrusted)
     *  COMPROMISED    │ false  (user marked key as compromised)
     * </pre>
     *
     *
     * <p>The BTBV path (status == null) is guarded by the same per-JID lock
     * used in {@link #saveIdentity}. This ensures that the
     * {@code hasVerifiedKeys} read and the implicit trust decision here are
     * always consistent with the state written by concurrent
     * {@code saveIdentity} calls for the same address.
     *
     * @param address     The remote device's protocol address (bare-JID + device-ID).
     * @param identityKey The identity key presented by the remote device.
     * @param direction   SENDING or RECEIVING — not used here, consistent with
     *                    how libsignal-protocol-java leaves the decision to the
     *                    application layer.
     * @return {@code true} if the session cipher should proceed;
     *         {@code false} to raise {@link org.signal.libsignal.protocol.UntrustedIdentityException}.
     */
    @Override
    public boolean isTrustedIdentity(SignalProtocolAddress address,
                                     IdentityKey identityKey,
                                     Direction direction) {
        final String fingerprint =
                CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize());
        final FingerprintStatus status = getFingerprintStatus(fingerprint);

        if (status == null) {
            // hold the per-JID lock so the BTBV read-decide
            // sequence is atomic with respect to concurrent saveIdentity calls
            // for the same JID.
            final ReentrantLock lock = trustLockFor(address.getName());
            lock.lock();
            try {
                // Re-read inside the lock — another thread may have stored the
                // key (and set its status) between the getFingerprintStatus()
                // call above and here.
                final FingerprintStatus freshStatus = getFingerprintStatus(fingerprint);
                if (freshStatus != null) {
                    return freshStatus.isTrusted();
                }

                final boolean btbv = mXmppConnectionService.blindTrustBeforeVerification();
                final boolean hasVerified =
                        account.getAxolotlService().hasVerifiedKeys(address.getName());

                if (btbv && !hasVerified) {
                    return true;
                }

                return false;
            } finally {
                lock.unlock();
            }
            // ────────────────────────────────────────────────────────────────
        }

        // Key is known: honour the stored decision.
        // FingerprintStatus.isTrusted() returns true for Trust.TRUSTED,
        // Trust.VERIFIED, and Trust.VERIFIED_X509 only.
        // Trust.COMPROMISED, UNTRUSTED, and UNDECIDED all return false.
        return status.isTrusted();
    }

    @Override
    public IdentityKey getIdentity(SignalProtocolAddress address) {
        Set<IdentityKey> identityKeys = mXmppConnectionService.databaseBackend.loadIdentityKeys(account, address.getName());
        if (identityKeys.isEmpty()) {
            return null;
        }
        return identityKeys.iterator().next();
    }

    public FingerprintStatus getFingerprintStatus(String fingerprint) {
        return (fingerprint == null) ? null : trustCache.get(fingerprint);
    }

    public void setFingerprintStatus(String fingerprint, FingerprintStatus status) {
        mXmppConnectionService.databaseBackend.setIdentityKeyTrust(account, fingerprint, status);
        trustCache.remove(fingerprint);
    }

    public void setFingerprintCertificate(String fingerprint, X509Certificate x509Certificate) {
        mXmppConnectionService.databaseBackend.setIdentityKeyCertificate(account, fingerprint, x509Certificate);
    }

    public X509Certificate getFingerprintCertificate(String fingerprint) {
        return mXmppConnectionService.databaseBackend.getIdentityKeyCertifcate(account, fingerprint);
    }

    public Set<IdentityKey> getContactKeysWithTrust(String bareJid, FingerprintStatus status) {
        return mXmppConnectionService.databaseBackend.loadIdentityKeys(account, bareJid, status);
    }

    public long getContactNumTrustedKeys(String bareJid) {
        return mXmppConnectionService.databaseBackend.numTrustedKeys(account, bareJid);
    }

    // --------------------------------------
    // SessionStore
    // --------------------------------------

    /**
     * Returns a copy of the {@link SessionRecord} corresponding to the recipientId + deviceId tuple,
     * or a new SessionRecord if one does not currently exist.
     * <p/>
     * It is important that implementations return a copy of the current durable information.  The
     * returned SessionRecord may be modified, but those changes should not have an effect on the
     * durable session state (what is returned by subsequent calls to this method) without the
     * store method being called here first.
     *
     * @param address The name and device ID of the remote client.
     * @return a copy of the SessionRecord corresponding to the recipientId + deviceId tuple, or
     * a new SessionRecord if one does not currently exist.
     */
    @Override
    public SessionRecord loadSession(SignalProtocolAddress address) {
        SessionRecord session = mXmppConnectionService.databaseBackend.loadSession(this.account, address);
        return (session != null) ? session : new SessionRecord();
    }

    /**
     * Returns the {@link SessionRecord}s for each of the given addresses.
     *
     * <p>Not exercised by this app's OMEMO usage (it only ever builds/uses sessions one
     * address at a time), but required by {@link SignalProtocolStore}.
     *
     * @param addresses The name and device ID of each remote client.
     * @return the SessionRecords corresponding to each address.
     * @throws NoSessionException if any address does not have an active session.
     */
    @Override
    public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) throws NoSessionException {
        final List<SessionRecord> records = new ArrayList<>(addresses.size());
        for (final SignalProtocolAddress address : addresses) {
            if (!containsSession(address)) {
                throw new NoSessionException("No session for " + address);
            }
            records.add(loadSession(address));
        }
        return records;
    }

    /**
     * Returns all known devices with active sessions for a recipient
     *
     * @param name the name of the client.
     * @return all known sub-devices with active sessions.
     */
    @Override
    public List<Integer> getSubDeviceSessions(String name) {
        return mXmppConnectionService.databaseBackend.getSubDeviceSessions(account,
                new SignalProtocolAddress(name, 0));
    }

    public List<String> getKnownAddresses() {
        return mXmppConnectionService.databaseBackend.getKnownSignalAddresses(account);
    }

    /**
     * Commit to storage the {@link SessionRecord} for a given recipientId + deviceId tuple.
     *
     * @param address the address of the remote client.
     * @param record  the current SessionRecord for the remote client.
     */
    @Override
    public void storeSession(SignalProtocolAddress address, SessionRecord record) {
        mXmppConnectionService.databaseBackend.storeSession(account, address, record);
    }

    /**
     * Determine whether there is a committed {@link SessionRecord} for a recipientId + deviceId tuple.
     *
     * @param address the address of the remote client.
     * @return true if a {@link SessionRecord} exists, false otherwise.
     */
    @Override
    public boolean containsSession(SignalProtocolAddress address) {
        return mXmppConnectionService.databaseBackend.containsSession(account, address);
    }

    /**
     * Remove a {@link SessionRecord} for a recipientId + deviceId tuple.
     *
     * @param address the address of the remote client.
     */
    @Override
    public void deleteSession(SignalProtocolAddress address) {
        mXmppConnectionService.databaseBackend.deleteSession(account, address);
    }

    /**
     * Remove the {@link SessionRecord}s corresponding to all devices of a recipientId.
     *
     * @param name the name of the remote client.
     */
    @Override
    public void deleteAllSessions(String name) {
        SignalProtocolAddress address = new SignalProtocolAddress(name, 0);
        mXmppConnectionService.databaseBackend.deleteAllSessions(account, address);
    }

    // --------------------------------------
    // PreKeyStore
    // --------------------------------------

    /**
     * Load a local PreKeyRecord.
     *
     * @param preKeyId the ID of the local PreKeyRecord.
     * @return the corresponding PreKeyRecord.
     * @throws InvalidKeyIdException when there is no corresponding PreKeyRecord.
     */
    @Override
    public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
        PreKeyRecord record = mXmppConnectionService.databaseBackend.loadPreKey(account, preKeyId);
        if (record == null) {
            throw new InvalidKeyIdException("No such PreKeyRecord: " + preKeyId);
        }
        return record;
    }

    /**
     * Store a local PreKeyRecord.
     *
     * @param preKeyId the ID of the PreKeyRecord to store.
     * @param record   the PreKeyRecord.
     */
    @Override
    public void storePreKey(int preKeyId, PreKeyRecord record) {
        mXmppConnectionService.databaseBackend.storePreKey(account, record);
        currentPreKeyId = preKeyId;
        boolean success = this.account.setKey(JSONKEY_CURRENT_PREKEY_ID, Integer.toString(preKeyId));
        if (success) {
            mXmppConnectionService.databaseBackend.updateAccount(account);
        } else {
            Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Failed to write new prekey id to the database!");
        }
    }

    /**
     * @param preKeyId A PreKeyRecord ID.
     * @return true if the store has a record for the preKeyId, otherwise false.
     */
    @Override
    public boolean containsPreKey(int preKeyId) {
        return mXmppConnectionService.databaseBackend.containsPreKey(account, preKeyId);
    }

    /**
     * Delete a PreKeyRecord from local storage.
     *
     * @param preKeyId The ID of the PreKeyRecord to remove.
     */
    @Override
    public void removePreKey(int preKeyId) {
        synchronized (preKeysMarkedForRemoval) {
            preKeysMarkedForRemoval.add(preKeyId);
        }
    }

    public boolean flushPreKeys() {
        int count = 0;
        synchronized (preKeysMarkedForRemoval) {
            for (Integer preKeyId : preKeysMarkedForRemoval) {
                count += mXmppConnectionService.databaseBackend.deletePreKey(account, preKeyId);
            }
            preKeysMarkedForRemoval.clear();
        }
        return count > 0;
    }

    // --------------------------------------
    // SignedPreKeyStore
    // --------------------------------------

    /**
     * Load a local SignedPreKeyRecord.
     *
     * @param signedPreKeyId the ID of the local SignedPreKeyRecord.
     * @return the corresponding SignedPreKeyRecord.
     * @throws InvalidKeyIdException when there is no corresponding SignedPreKeyRecord.
     */
    @Override
    public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
        SignedPreKeyRecord record = mXmppConnectionService.databaseBackend.loadSignedPreKey(account, signedPreKeyId);
        if (record == null) {
            throw new InvalidKeyIdException("No such SignedPreKeyRecord: " + signedPreKeyId);
        }
        return record;
    }

    /**
     * Load all local SignedPreKeyRecords.
     *
     * @return All stored SignedPreKeyRecords.
     */
    @Override
    public List<SignedPreKeyRecord> loadSignedPreKeys() {
        return mXmppConnectionService.databaseBackend.loadSignedPreKeys(account);
    }

    public int getSignedPreKeysCount() {
        return mXmppConnectionService.databaseBackend.getSignedPreKeysCount(account);
    }

    /**
     * Store a local SignedPreKeyRecord.
     *
     * @param signedPreKeyId the ID of the SignedPreKeyRecord to store.
     * @param record         the SignedPreKeyRecord.
     */
    @Override
    public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
        mXmppConnectionService.databaseBackend.storeSignedPreKey(account, record);
    }

    /**
     * @param signedPreKeyId A SignedPreKeyRecord ID.
     * @return true if the store has a record for the signedPreKeyId, otherwise false.
     */
    @Override
    public boolean containsSignedPreKey(int signedPreKeyId) {
        return mXmppConnectionService.databaseBackend.containsSignedPreKey(account, signedPreKeyId);
    }

    /**
     * Delete a SignedPreKeyRecord from local storage.
     *
     * @param signedPreKeyId The ID of the SignedPreKeyRecord to remove.
     */
    @Override
    public void removeSignedPreKey(int signedPreKeyId) {
        mXmppConnectionService.databaseBackend.deleteSignedPreKey(account, signedPreKeyId);
    }

    public void preVerifyFingerprint(Account account, String name, String fingerprint) {
        mXmppConnectionService.databaseBackend.storePreVerification(account, name, fingerprint, FingerprintStatus.createInactiveVerified());
    }

    // --------------------------------------
    // KyberPreKeyStore / SenderKeyStore
    //
    // OMEMO (XEP-0384) as implemented by this app is X3DH-only: bundles carry no Kyber
    // (PQXDH) pre-key, and the app never uses libsignal's group/sender-key messaging.
    // SignalProtocolStore requires these two interfaces regardless, so they're stubbed
    // out here; none of these methods are reachable from this app's actual message flow.
    // --------------------------------------

    @Override
    public KyberPreKeyRecord loadKyberPreKey(int kyberPreKeyId) throws InvalidKeyIdException {
        throw new InvalidKeyIdException("No such KyberPreKeyRecord: " + kyberPreKeyId);
    }

    @Override
    public List<KyberPreKeyRecord> loadKyberPreKeys() {
        return new ArrayList<>();
    }

    @Override
    public void storeKyberPreKey(int kyberPreKeyId, KyberPreKeyRecord record) {
        throw new UnsupportedOperationException("Kyber pre-keys are not used by this app's OMEMO implementation");
    }

    @Override
    public boolean containsKyberPreKey(int kyberPreKeyId) {
        return false;
    }

    @Override
    public void markKyberPreKeyUsed(int kyberPreKeyId) {
        // no-op: no Kyber pre-keys are ever stored, see class-level note above
    }

    @Override
    public void storeSenderKey(SignalProtocolAddress sender, UUID distributionId, SenderKeyRecord record) {
        throw new UnsupportedOperationException("Group/sender-key sessions are not used by this app's OMEMO implementation");
    }

    @Override
    public SenderKeyRecord loadSenderKey(SignalProtocolAddress sender, UUID distributionId) {
        return null;
    }
}

package com.glaciersecurity.glaciermessenger.entities;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.SystemClock;

import com.glaciersecurity.glaciermessenger.GlacierGuardian;
import com.glaciersecurity.glaciermessenger.utils.KeystoreCredentialStore;
import com.glaciersecurity.glaciermessenger.utils.Log;

import com.glaciersecurity.glaciermessenger.utils.Tools;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.crypto.PgpDecryptionService;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.AxolotlService;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.XmppAxolotlSession;
import com.glaciersecurity.glaciermessenger.crypto.sasl.ChannelBinding;
import com.glaciersecurity.glaciermessenger.crypto.sasl.ChannelBindingMechanism;
import com.glaciersecurity.glaciermessenger.crypto.sasl.HashedToken;
import com.glaciersecurity.glaciermessenger.crypto.sasl.HashedTokenSha256;
import com.glaciersecurity.glaciermessenger.crypto.sasl.HashedTokenSha512;
import com.glaciersecurity.glaciermessenger.crypto.sasl.SaslMechanism;
import com.glaciersecurity.glaciermessenger.services.AvatarService;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.utils.UIHelper;
import com.glaciersecurity.glaciermessenger.utils.XmppUri;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.XmppConnection;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.RtpCapability;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

public class Account extends AbstractEntity implements AvatarService.Avatarable {

    public static final String TABLENAME = "accounts";

    public static final String USERNAME = "username";
    public static final String SERVER = "server";
    public static final String PASSWORD = "password";
    public static final String OPTIONS = "options";
    public static final String ROSTERVERSION = "rosterversion";
    public static final String KEYS = "keys";
    public static final String AVATAR = "avatar";
    public static final String DISPLAY_NAME = "display_name";
    public static final String HOSTNAME = "hostname";
    public static final String TIMER = "timer";
    public static final String PORT = "port";
    public static final String STATUS = "status";
    public static final String STATUS_MESSAGE = "status_message";
    public static final String RESOURCE = "resource";
    public static final String PINNED_MECHANISM = "pinned_mechanism";
    public static final String PINNED_CHANNEL_BINDING = "pinned_channel_binding";
    public static final String FAST_MECHANISM = "fast_mechanism";
    public static final String FAST_TOKEN = "fast_token";
    public static final String ORGANIZATION = "organization";

    public static final int OPTION_DISABLED = 1;
    public static final int OPTION_REGISTER = 2;
    public static final int OPTION_MAGIC_CREATE = 4;
    public static final int OPTION_REQUIRES_ACCESS_MODE_CHANGE = 5;
    public static final int OPTION_LOGGED_IN_SUCCESSFULLY = 6;
    public static final int OPTION_HTTP_UPLOAD_AVAILABLE = 7;
    public static final int OPTION_UNVERIFIED = 8;
    public static final int OPTION_FIXED_USERNAME = 9;
    public static final int OPTION_QUICKSTART_AVAILABLE = 10;
    public static final int OPTION_SOFT_DISABLED = 11;

    private static final String KEY_PGP_SIGNATURE = "pgp_signature";
    private static final String KEY_PGP_ID = "pgp_id";
    private static final String KEY_PINNED_MECHANISM = "pinned_mechanism";
    public static final String KEY_PRE_AUTH_REGISTRATION_TOKEN = "pre_auth_registration";
    private static final String KEY_GLACIER_BIND = "glacier_bind";

    public static final String ADD_TO_CORE_VPN = "add_to_core_vpn";
    /**
     * Retained for the database only. Nothing reads this column: the console never sent
     * add_user_to_SMS, no code ever branched on it, and the Java field is gone. The column
     * and its migration stay because existing rows have it, dropping a SQLite column means
     * rebuilding the table, and removing it from onCreate alone would leave fresh and
     * upgraded databases with different shapes. SMS access is gated by IS_SMS_ENABLED.
     */
    public static final String ADD_USER_TO_SMS = "add_user_to_SMS";
    public static final String ADD_USER_TO_PURCHASE_NUMBERS = "add_user_to_purchase_numbers";
    public static final String CORE_REGION = "core_region";
    public static final String EXTENSION_VOICESERVER = "extension_voiceserver";
    public static final String FIRST_NAME = "first_name";
    public static final String IS_SMS_ENABLED = "isSMSEnabled";
    public static final String LAST_NAME = "last_name";

    protected final JSONObject keys;
    private final Roster roster = new Roster(this);
    private final Collection<Jid> blocklist = new CopyOnWriteArraySet<>();
    public final Set<Conversation> pendingConferenceJoins = new HashSet<>();
    public final Set<Conversation> pendingConferenceLeaves = new HashSet<>();
    public final Set<Conversation> inProgressConferenceJoins = new HashSet<>();
    public final Set<Conversation> inProgressConferencePings = new HashSet<>();
    protected Jid jid;
    /**
     * The account password. In-memory cache only: like {@link #token} and {@link #fastToken} it
     * lives in {@link KeystoreCredentialStore}, not in the {@code accounts} table.
     *
     * @see #persistSensitiveCredentials()
     */
    protected String password;
    /**
     * ejabberd JWT used for SASL PLAIN token auth, kept alongside {@link #password}.
     * Never persisted in SQLite — lives in {@link KeystoreCredentialStore}, keyed
     * per-account (distinct key namespace from the Cognito password store).
     */
    private String token;
    protected int options = 0;
    protected State status = State.OFFLINE;
    private State lastErrorStatus = State.OFFLINE;
    protected String resource;
    protected String avatar;
    protected String hostname = null;
    protected String organization;
    protected int port = 5222;
    protected int timer;
    protected boolean online = false;
    private String rosterVersion;
    private String displayName = null;
    private AxolotlService axolotlService = null;
    private PgpDecryptionService pgpDecryptionService = null;
    private XmppConnection xmppConnection = null;
    private long mEndGracePeriod = 0L;
    private final Map<Jid, Bookmark> bookmarks = new HashMap<>();
    private Presence.Status presenceStatus;
    private String presenceStatusMessage;
    private String pinnedMechanism;
    private String pinnedChannelBinding;
    private String fastMechanism;
    /**
     * FAST token for SASL HT-* quick resumption. In-memory cache only — see {@link #password}.
     */
    private String fastToken;
    /**
     * Set when this instance was built from a row that still carried a password or FAST token in
     * the {@code accounts} table columns. {@link #persistSensitiveCredentials()} moves those
     * values into {@link KeystoreCredentialStore} and {@link #getContentValues()} nulls the
     * columns, so the row is upgraded the first time it is written back.
     */
    private boolean legacyPlaintextCredentials = false;
    private Boolean add_to_core_vpn;
    private Boolean canPurchaseNumbers;
    private List<String> coreRegion;

    private String extensionVoiceserver;
    private String firstName;
    private Boolean isSMSEnabled;
    private String lastName;
    protected JSONArray smsProfileJson;
    private GlacierPhoneEntity glacierPhoneEntity;


    public Account(final Jid jid, final String password) {
        this(
                java.util.UUID.randomUUID().toString(),
                jid,
                password,
                0,
                null,
                "",
                null,
                null,
                null,
                5222,
                Message.TIMER_NONE,
                Presence.Status.ONLINE,
                null,
                null,
                null,
                null,
                null);
    }


    private Account(
            final String uuid,
            final Jid jid,
            final String password,
            final int options,
            final String rosterVersion,
            final String keys,
            final String avatar,
            String displayName,
            String hostname,
            int port,
            int timer,
            final Presence.Status status,
            String statusMessage,
            final String pinnedMechanism,
            final String pinnedChannelBinding,
            final String fastMechanism,
            final String fastToken) {
        this.uuid = uuid;
        this.jid = jid;
        this.password = password;
        this.options = options;
        this.rosterVersion = rosterVersion;
        JSONObject tmp;
        try {
            tmp = new JSONObject(keys);
        } catch (JSONException e) {
            tmp = new JSONObject();
        }
        this.keys = tmp;
        this.avatar = avatar;
        this.displayName = displayName;
        this.hostname = hostname;
        this.port = port;
        this.timer = timer;
        this.presenceStatus = status;
        this.presenceStatusMessage = statusMessage;
        this.pinnedMechanism = pinnedMechanism;
        this.pinnedChannelBinding = pinnedChannelBinding;
        this.fastMechanism = fastMechanism;
        this.fastToken = fastToken;
    }

    public Account(final Jid jid, final String password, final String organization) {
        this(
                java.util.UUID.randomUUID().toString(),
                jid,
                password,
                0,
                null,
                "",
                null,
                null,
                null,
                5222,
                Message.TIMER_NONE,
                Presence.Status.ONLINE,
                null,
                null,
                null,
                null,
                null,
                organization,
                false,
                false,
                null,
                null,
                false,
                null);
    }

    public Account(final Jid jid, final String password, final String organization, final GlacierUserEntity glacierUsers, final String coreRegion) {
        this(
                java.util.UUID.randomUUID().toString(),
                jid,
                password,
                0,
                null,
                "",
                null,
                null,
                null,
                5222,
                Message.TIMER_NONE,
                Presence.Status.ONLINE,
                null,
                null,
                null,
                null,
                null,
                organization,
                glacierUsers.getAddToCoreVpn(),
                glacierUsers.getAddUserToPurchaseNumbers(),
                coreRegion,
                glacierUsers.getFirstName(),
                glacierUsers.getIsSmsEnabled(),
                glacierUsers.getLastName());
    }


    private Account(
            final String uuid,
            final Jid jid,
            final String password,
            final int options,
            final String rosterVersion,
            final String keys,
            final String avatar,
            String displayName,
            String hostname,
            int port,
            int timer,
            final Presence.Status status,
            String statusMessage,
            final String pinnedMechanism,
            final String pinnedChannelBinding,
            final String fastMechanism,
            final String fastToken,
            final String organization,
            final Boolean add_to_core_vpn,
            final Boolean add_user_to_purchase_numbers,
            final String core_region,
            final String first_name,
            final Boolean isSMSEnabled,
            final String last_name) {
        this.uuid = uuid;
        this.jid = jid;
        this.password = password;
        this.options = options;
        this.rosterVersion = rosterVersion;
        JSONObject tmp;
        try {
            tmp = new JSONObject(keys);
        } catch (JSONException e) {
            tmp = new JSONObject();
        }
        this.keys = tmp;
        this.avatar = avatar;
        this.displayName = displayName;
        this.hostname = hostname;
        this.port = port;
        this.timer = timer;
        this.presenceStatus = status;
        this.presenceStatusMessage = statusMessage;
        this.pinnedMechanism = pinnedMechanism;
        this.pinnedChannelBinding = pinnedChannelBinding;
        this.fastMechanism = fastMechanism;
        this.fastToken = fastToken;
        this.organization = organization;
        this.add_to_core_vpn = add_to_core_vpn;
        this.canPurchaseNumbers = add_user_to_purchase_numbers;

        this.coreRegion = regionFromJson(core_region);
        this.firstName = first_name;
        this.isSMSEnabled = isSMSEnabled;
        this.lastName = last_name;
    }

    private List<String> regionFromJson(String coreRegion) {
        List<String> regions = new ArrayList<>();
        if (coreRegion == null || coreRegion.isEmpty()) {
            return regions;
        }
        try {
            JsonArray jsonArray = JsonParser.parseString(coreRegion).getAsJsonArray();
            if (jsonArray != null) {
                for (int i = 0; i < jsonArray.size(); i++) {
                    String r = jsonArray.get(i).getAsString();
                    regions.add(r.replace("\"", ""));
                }
            }
        } catch (Exception e) {
        }
        return Tools.removeDuplicates(regions);
    }

    public static Account fromCursor(final Cursor cursor) {
        final Jid jid;
        try {
            final String resource = cursor.getString(cursor.getColumnIndexOrThrow(RESOURCE));
            jid =
                    Jid.of(
                            cursor.getString(cursor.getColumnIndexOrThrow(USERNAME)),
                            cursor.getString(cursor.getColumnIndexOrThrow(SERVER)),
                            resource == null || resource.trim().isEmpty() ? null : resource);
        } catch (final IllegalArgumentException e) {
            throw new AssertionError(e);
        }
        final Account account = new Account(
                cursor.getString(cursor.getColumnIndexOrThrow(UUID)),
                jid,
                cursor.getString(cursor.getColumnIndexOrThrow(PASSWORD)),
                cursor.getInt(cursor.getColumnIndexOrThrow(OPTIONS)),
                cursor.getString(cursor.getColumnIndexOrThrow(ROSTERVERSION)),
                cursor.getString(cursor.getColumnIndexOrThrow(KEYS)),
                cursor.getString(cursor.getColumnIndexOrThrow(AVATAR)),
                cursor.getString(cursor.getColumnIndexOrThrow(DISPLAY_NAME)),
                cursor.getString(cursor.getColumnIndexOrThrow(HOSTNAME)),
                cursor.getInt(cursor.getColumnIndexOrThrow(PORT)),
                cursor.getInt(cursor.getColumnIndexOrThrow(TIMER)),
                Presence.Status.fromShowString(cursor.getString(cursor.getColumnIndexOrThrow(STATUS))),
                cursor.getString(cursor.getColumnIndexOrThrow(STATUS_MESSAGE)),
                cursor.getString(cursor.getColumnIndexOrThrow(PINNED_MECHANISM)),
                cursor.getString(cursor.getColumnIndexOrThrow(PINNED_CHANNEL_BINDING)),
                cursor.getString(cursor.getColumnIndexOrThrow(FAST_MECHANISM)),
                cursor.getString(cursor.getColumnIndexOrThrow(FAST_TOKEN)),
                cursor.getString(cursor.getColumnIndexOrThrow(ORGANIZATION)),
                (cursor.getInt(cursor.getColumnIndexOrThrow(ADD_TO_CORE_VPN)) == 1),
                (cursor.getInt(cursor.getColumnIndexOrThrow(ADD_USER_TO_PURCHASE_NUMBERS)) == 1),
                cursor.getString(cursor.getColumnIndexOrThrow(CORE_REGION)),
                cursor.getString(cursor.getColumnIndexOrThrow(FIRST_NAME)),
                (cursor.getInt(cursor.getColumnIndexOrThrow(IS_SMS_ENABLED)) == 1),
                cursor.getString(cursor.getColumnIndexOrThrow(LAST_NAME)));

        // A row may still carry the credentials in its own columns. The constructor keeps whatever
        // the columns held, so the values stay usable; flagging the account here is what gets the
        // row rewritten (Keystore copy stored, columns nulled).
        account.legacyPlaintextCredentials =
                !Strings.isNullOrEmpty(account.password) || !Strings.isNullOrEmpty(account.fastToken);
        return account;
    }

    public void setGlacierUser(GlacierUserEntity glacierUsers){
        Boolean addVPN = glacierUsers.getAddToCoreVpn();
        this.add_to_core_vpn = addVPN != null ? addVPN : false;

        Boolean addUserToPurchase = glacierUsers.getAddUserToPurchaseNumbers();
        this.canPurchaseNumbers = addUserToPurchase != null ? addUserToPurchase : false;

        List<String> regions = glacierUsers.getCoreRegion();
        this.coreRegion = regions != null ? regions : new ArrayList<>();

        String fName = glacierUsers.getFirstName();
        this.firstName = fName != null ? fName : "";

        Boolean smsEnabled = glacierUsers.getIsSmsEnabled();
        this.isSMSEnabled = smsEnabled != null ? smsEnabled : false;

        String lName = glacierUsers.getLastName();
        this.lastName = lName != null ? lName : "";
    }

    public boolean httpUploadAvailable(long size) {
        return xmppConnection != null && xmppConnection.getFeatures().httpUpload(size);
    }

    public boolean httpUploadAvailable() {
        return isOptionSet(OPTION_HTTP_UPLOAD_AVAILABLE) || httpUploadAvailable(0);
    }

    public String getDisplayName() {
        if (displayName == null || displayName.isEmpty()){
            return this.getJid().getLocal();
        }
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Contact getSelfContact() {
        return getRoster().getContact(jid);
    }

    public boolean hasPendingPgpIntent(Conversation conversation) {
        return pgpDecryptionService != null && pgpDecryptionService.hasPendingIntent(conversation);
    }

    public boolean isPgpDecryptionServiceConnected() {
        return pgpDecryptionService != null && pgpDecryptionService.isConnected();
    }

    public boolean setShowErrorNotification(boolean newValue) {
        boolean oldValue = showErrorNotification();
        setKey("show_error", Boolean.toString(newValue));
        return newValue != oldValue;
    }

    public boolean showErrorNotification() {
        String key = getKey("show_error");
        return key == null || Boolean.parseBoolean(key);
    }

    public boolean isEnabled() {
        return !isOptionSet(Account.OPTION_DISABLED);
    }

    public boolean isConnectionEnabled() {
        return !isOptionSet(Account.OPTION_DISABLED) && !isOptionSet(Account.OPTION_SOFT_DISABLED);
    }

    public boolean isOptionSet(final int option) {
        return ((options & (1 << option)) != 0);
    }

    public boolean setOption(final int option, final boolean value) {
        if (value && (option == OPTION_DISABLED || option == OPTION_SOFT_DISABLED)) {
            this.setStatus(State.OFFLINE);
        }
        final int before = this.options;
        if (value) {
            this.options |= 1 << option;
        } else {
            this.options &= ~(1 << option);
        }
        return before != this.options;
    }

    public String getUsername() {
        return jid.getEscapedLocal();
    }

    public boolean setJid(final Jid next) {
        final Jid previousFull = this.jid;
        final Jid prev = this.jid != null ? this.jid.asBareJid() : null;
        final boolean changed = prev == null || (next != null && !prev.equals(next.asBareJid()));
        if (changed) {
            final AxolotlService oldAxolotlService = this.axolotlService;
            if (oldAxolotlService != null) {
                oldAxolotlService.destroy();
                this.jid = next;
                this.axolotlService = oldAxolotlService.makeNew();
            }
        }
        this.jid = next;
        return next != null && !next.equals(previousFull);
    }

    public Jid getDomain() {
        return jid.getDomain();
    }

    public String getServer() {
        return jid.getDomain().toEscapedString();
    }

    /**
     * Returns the account password, loading it from {@link KeystoreCredentialStore} on first
     * access if it is not already cached in memory.
     */
    public String getPassword() {
        if (this.password == null) {
            this.password = loadCredential(passwordCredentialId());
        }
        return this.password;
    }

    public void setPassword(final String password) {
        this.password = password;
        if (Strings.isNullOrEmpty(password)) {
            removeCredential(passwordCredentialId());
        }
    }

    /**
     * Returns the stored ejabberd JWT, loading it from {@link KeystoreCredentialStore} on
     * first access if not already cached in memory.
     */
    public String getToken() {
        if (this.token == null) {
            this.token = loadToken();
        }
        return this.token;
    }

    public boolean hasToken() {
        return !Strings.isNullOrEmpty(getToken());
    }

    /**
     * Stores {@code token} (or clears it, if null/empty) in the Keystore-backed credential
     * store under a key distinct from the password store.
     */
    public void setToken(final String token) {
        this.token = token;
        final Context context = GlacierGuardian.getContext();
        if (context == null) {
            return;
        }
        if (Strings.isNullOrEmpty(token)) {
            KeystoreCredentialStore.remove(context, tokenCredentialId());
        } else {
            KeystoreCredentialStore.store(context, tokenCredentialId(), token);
        }
    }

    /**
     * Removes the stored token entirely. Called on logout / account removal.
     */
    public void clearToken() {
        this.token = null;
        final Context context = GlacierGuardian.getContext();
        if (context != null) {
            KeystoreCredentialStore.remove(context, tokenCredentialId());
        }
    }

    private String loadToken() {
        final Context context = GlacierGuardian.getContext();
        if (context == null) {
            return null;
        }
        return KeystoreCredentialStore.retrieve(context, tokenCredentialId());
    }

    private String tokenCredentialId() {
        return "xmpp_jwt_" + uuid;
    }

    // ── Keystore-backed credential storage ─────────────────────────────
    //
    // The account password and the XMPP FAST token live in KeystoreCredentialStore, sealed with
    // a per-credential non-exportable AES-256-GCM key, and their accounts-table columns are
    // written as NULL.

    private String passwordCredentialId() {
        return "xmpp_password_" + uuid;
    }

    private String fastTokenCredentialId() {
        return "xmpp_fast_" + uuid;
    }

    private String loadCredential(final String credentialId) {
        final Context context = GlacierGuardian.getContext();
        if (context == null) {
            return null;
        }
        return KeystoreCredentialStore.retrieve(context, credentialId);
    }

    private void removeCredential(final String credentialId) {
        final Context context = GlacierGuardian.getContext();
        if (context != null) {
            KeystoreCredentialStore.remove(context, credentialId);
        }
    }

    /**
     * Writes the in-memory password and FAST token into {@link KeystoreCredentialStore}. Called
     * from {@link #getContentValues()}, i.e. at exactly the points where the row is persisted,
     * so a caller that mutates the account and saves it cannot end up with the two stores
     * disagreeing.
     *
     * <p>Failure is deliberately not compensated for by writing the value into the database
     * instead: an unusable Keystore means the user re-authenticates, which is a better outcome
     * than putting the credential in the database.
     *
     * <p>Only writes; never deletes. A null field means "not loaded from the store yet", not
     * "cleared" — the account may well have been saved before anything called the getter, and
     * treating that as a deletion would silently log the user out. Clearing a credential goes
     * through {@link #setPassword(String)}, {@link #resetFastToken()} or
     * {@link #clearCredentials()}, all of which remove it from the store directly.
     */
    private void persistSensitiveCredentials() {
        final Context context = GlacierGuardian.getContext();
        if (context == null) {
            Log.e(Config.LOGTAG,
                    "Account: no application context; cannot persist account credentials");
            return;
        }
        persistCredential(context, passwordCredentialId(), this.password, "password");
        persistCredential(context, fastTokenCredentialId(), this.fastToken, "FAST token");
    }

    private static void persistCredential(
            final Context context,
            final String credentialId,
            final String value,
            final String description) {
        if (Strings.isNullOrEmpty(value)) {
            return;
        }
        if (!KeystoreCredentialStore.store(context, credentialId, value)) {
            Log.e(Config.LOGTAG, "Account: failed to store " + description
                    + " in the Keystore credential store; it will not be persisted");
        }
    }

    /**
     * @return true if this account was loaded from a row that still held a password or FAST token
     * in its columns, and should therefore be written back once to migrate it.
     */
    public boolean needsCredentialMigration() {
        return this.legacyPlaintextCredentials;
    }

    /**
     * Removes every credential this account keeps outside the database — password, FAST token and
     * ejabberd JWT. Called when the account row itself is deleted, so the Keystore keys and their
     * ciphertext do not outlive it.
     */
    public void clearCredentials() {
        this.password = null;
        this.fastToken = null;
        this.fastMechanism = null;
        removeCredential(passwordCredentialId());
        removeCredential(fastTokenCredentialId());
        clearToken();
    }

    public String getHostname() {
        return Strings.nullToEmpty(this.hostname);
    }

    public int getTimer() {
        return this.timer;
    }

    public void setTimer(int timer) {
        this.timer = timer;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public int getPort() {
        return this.port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public State getStatus() {
        if (isOptionSet(OPTION_DISABLED)) {
            return State.DISABLED;
        } else if (isOptionSet(OPTION_SOFT_DISABLED)) {
            return State.LOGGED_OUT;
        } else {
            return this.status;
        }
    }

    public GlacierPhoneEntity getGlacierPhoneEntity() {
        return glacierPhoneEntity;
    }

    public void setGlacierPhoneEntity(GlacierPhoneEntity glacierPhoneEntity) {
        this.glacierPhoneEntity = glacierPhoneEntity;
    }

    public boolean unauthorized() {
        return this.status == State.UNAUTHORIZED || this.lastErrorStatus == State.UNAUTHORIZED;
    }

    public State getLastErrorStatus() {
        return this.lastErrorStatus;
    }

    public void setStatus(final State status) {
        this.status = status;
        // Losing the network replaces a stale error, so an UNAUTHORIZED or DOWNGRADE_ATTACK from
        // an earlier attempt does not keep showing while the real reason is being offline.
        if (status.isError
                || status == State.ONLINE
                || status == State.NO_INTERNET
                || status == State.AIRPLANE_MODE) {
            this.lastErrorStatus = status;
        }
    }

    public void setPinnedMechanism(final SaslMechanism mechanism) {
        this.pinnedMechanism = mechanism.getMechanism();
        if (mechanism instanceof ChannelBindingMechanism) {
            this.pinnedChannelBinding =
                    ((ChannelBindingMechanism) mechanism).getChannelBinding().toString();
        } else {
            this.pinnedChannelBinding = null;
        }
    }

    public void setFastToken(final HashedToken.Mechanism mechanism, final String token) {
        this.fastMechanism = mechanism.name();
        this.fastToken = token;
    }

    public void resetFastToken() {
        this.fastMechanism = null;
        this.fastToken = null;
        // Also drop the Keystore copy, otherwise getFastToken() would lazily resurrect the token
        // this call is meant to invalidate.
        removeCredential(fastTokenCredentialId());
    }

    public void resetPinnedMechanism() {
        this.pinnedMechanism = null;
        this.pinnedChannelBinding = null;
        setKey(Account.KEY_PINNED_MECHANISM, String.valueOf(-1));
    }

    public int getPinnedMechanismPriority() {
        final int fallback = getKeyAsInt(KEY_PINNED_MECHANISM, -1);
        if (Strings.isNullOrEmpty(this.pinnedMechanism)) {
            return fallback;
        }
        final SaslMechanism saslMechanism = getPinnedMechanism();
        if (saslMechanism == null) {
            return fallback;
        } else {
            return saslMechanism.getPriority();
        }
    }

    private SaslMechanism getPinnedMechanism() {
        final String mechanism = Strings.nullToEmpty(this.pinnedMechanism);
        final ChannelBinding channelBinding = ChannelBinding.get(this.pinnedChannelBinding);
        return new SaslMechanism.Factory(this).of(mechanism, channelBinding);
    }

    public HashedToken getFastMechanism() {
        final HashedToken.Mechanism fastMechanism = HashedToken.Mechanism.ofOrNull(this.fastMechanism);
        final String token = getFastToken();
        if (fastMechanism == null || Strings.isNullOrEmpty(token)) {
            return null;
        }
        if (fastMechanism.hashFunction.equals("SHA-256")) {
            return new HashedTokenSha256(this, fastMechanism.channelBinding);
        } else if (fastMechanism.hashFunction.equals("SHA-512")) {
            return new HashedTokenSha512(this, fastMechanism.channelBinding);
        } else {
            return null;
        }
    }

    public SaslMechanism getQuickStartMechanism() {
        final HashedToken hashedTokenMechanism = getFastMechanism();
        if (hashedTokenMechanism != null) {
            return hashedTokenMechanism;
        }
        return getPinnedMechanism();
    }

    /**
     * Returns the FAST token, loading it from {@link KeystoreCredentialStore} on first access if
     * it is not already cached in memory.
     */
    public String getFastToken() {
        if (this.fastToken == null) {
            this.fastToken = loadCredential(fastTokenCredentialId());
        }
        return this.fastToken;
    }

    public State getTrueStatus() {
        return this.status;
    }

    public boolean errorStatus() {
        return getStatus().isError();
    }

    public boolean hasErrorStatus() {
        return getXmppConnection() != null
                && (getStatus().isError() || getStatus() == State.CONNECTING)
                && getXmppConnection().getAttempt() >= 3;
    }

    public Presence.Status getPresenceStatus() {
        return this.presenceStatus;
    }

    public void setPresenceStatus(Presence.Status status) {
        this.presenceStatus = status;
    }

    public String getPresenceStatusMessage() {
        return this.presenceStatusMessage;
    }

    public void setPresenceStatusMessage(String message) {
        this.presenceStatusMessage = message;
    }

    public String getResource() {
        return jid.getResource();
    }

    public void setResource(final String resource) {
        this.jid = this.jid.withResource(resource);
    }

    public Jid getJid() {
        return jid;
    }

    public JSONObject getKeys() {
        return keys;
    }

    public String getKey(final String name) {
        synchronized (this.keys) {
            return this.keys.optString(name, null);
        }
    }

    public JSONArray getSmsProfileJson() {
        return smsProfileJson;
    }

    public void setSmsProfileJson(JSONArray smsProfileJson) {
        this.smsProfileJson = smsProfileJson;
    }

    public int getKeyAsInt(final String name, int defaultValue) {
        String key = getKey(name);
        try {
            return key == null ? defaultValue : Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean setKey(final String keyName, final String keyValue) {
        synchronized (this.keys) {
            try {
                this.keys.put(keyName, keyValue);
                return true;
            } catch (final JSONException e) {
                return false;
            }
        }
    }

    public void setPrivateKeyAlias(final String alias) {
        setKey("private_key_alias", alias);
    }

    public String getPrivateKeyAlias() {
        return getKey("private_key_alias");
    }



    @Override
    public ContentValues getContentValues() {
        final ContentValues values = new ContentValues();
        // The password and FAST token go to the Keystore credential store, and their
        // columns are written as NULL. The columns are kept in the schema so that reads of older
        // rows (and older backups) can still find and migrate a value held in a column.
        persistSensitiveCredentials();
        this.legacyPlaintextCredentials = false;
        values.put(UUID, uuid);
        values.put(USERNAME, jid.getLocal());
        values.put(SERVER, jid.getDomain().toEscapedString());
        values.putNull(PASSWORD);
        values.put(OPTIONS, options);
        synchronized (this.keys) {
            values.put(KEYS, this.keys.toString());
        }
        values.put(ROSTERVERSION, rosterVersion);
        values.put(AVATAR, avatar);
        values.put(DISPLAY_NAME, displayName);
        values.put(HOSTNAME, hostname);
        values.put(PORT, port);
        values.put(TIMER, timer);
        values.put(STATUS, presenceStatus.toShowString());
        values.put(STATUS_MESSAGE, presenceStatusMessage);
        values.put(RESOURCE, jid.getResource());
        values.put(PINNED_MECHANISM, pinnedMechanism);
        values.put(PINNED_CHANNEL_BINDING, pinnedChannelBinding);
        values.put(FAST_MECHANISM, this.fastMechanism);
        values.putNull(FAST_TOKEN);
        values.put(ORGANIZATION, this.organization);

        values.put(ADD_TO_CORE_VPN, this.add_to_core_vpn);
        values.put(ADD_USER_TO_PURCHASE_NUMBERS, this.canPurchaseNumbers);
        values.put(CORE_REGION, this.coreRegion.toString());
        values.put(EXTENSION_VOICESERVER, this.extensionVoiceserver);
        values.put(FIRST_NAME, this.firstName);
        values.put(IS_SMS_ENABLED, this.isSMSEnabled);
        values.put(LAST_NAME, this.lastName);
        return values;
    }

    public AxolotlService getAxolotlService() {
        return axolotlService;
    }

    public void initAccountServices(final XmppConnectionService context) {
        this.axolotlService = new AxolotlService(this, context);
        this.pgpDecryptionService = new PgpDecryptionService(context);
        if (xmppConnection != null) {
            xmppConnection.addOnAdvancedStreamFeaturesAvailableListener(axolotlService);
        }
    }

    public PgpDecryptionService getPgpDecryptionService() {
        return this.pgpDecryptionService;
    }

    public XmppConnection getXmppConnection() {
        return this.xmppConnection;
    }

    public void setXmppConnection(final XmppConnection connection) {
        this.xmppConnection = connection;
    }

    public String getRosterVersion() {
        if (this.rosterVersion == null) {
            return "";
        } else {
            return this.rosterVersion;
        }
    }

    public void setRosterVersion(final String version) {
        this.rosterVersion = version;
    }

    public int countPresences() {
        return this.getSelfContact().getPresences().size();
    }

    public int activeDevicesWithRtpCapability() {
        int i = 0;
        for (Presence presence : getSelfContact().getPresences().getPresences()) {
            if (RtpCapability.check(presence) != RtpCapability.Capability.NONE) {
                i++;
            }
        }
        return i;
    }

    public String getPgpSignature() {
        return getKey(KEY_PGP_SIGNATURE);
    }

    public boolean setPgpSignature(String signature) {
        return setKey(KEY_PGP_SIGNATURE, signature);
    }

    public boolean unsetPgpSignature() {
        synchronized (this.keys) {
            return keys.remove(KEY_PGP_SIGNATURE) != null;
        }
    }

    public long getPgpId() {
        synchronized (this.keys) {
            if (keys.has(KEY_PGP_ID)) {
                try {
                    return keys.getLong(KEY_PGP_ID);
                } catch (JSONException e) {
                    return 0;
                }
            } else {
                return 0;
            }
        }
    }

    public boolean setPgpSignId(long pgpID) {
        synchronized (this.keys) {
            try {
                if (pgpID == 0) {
                    keys.remove(KEY_PGP_ID);
                } else {
                    keys.put(KEY_PGP_ID, pgpID);
                }
            } catch (JSONException e) {
                return false;
            }
            return true;
        }
    }

    public Roster getRoster() {
        return this.roster;
    }

    public Collection<Bookmark> getBookmarks() {
        synchronized (this.bookmarks) {
            return ImmutableList.copyOf(this.bookmarks.values());
        }
    }

    public void setBookmarks(final Map<Jid, Bookmark> bookmarks) {
        synchronized (this.bookmarks) {
            this.bookmarks.clear();
            this.bookmarks.putAll(bookmarks);
        }
    }

    public void putBookmark(final Bookmark bookmark) {
        synchronized (this.bookmarks) {
            this.bookmarks.put(bookmark.getJid(), bookmark);
        }
    }

    public void removeBookmark(Bookmark bookmark) {
        synchronized (this.bookmarks) {
            this.bookmarks.remove(bookmark.getJid());
        }
    }

    public void removeBookmark(Jid jid) {
        synchronized (this.bookmarks) {
            this.bookmarks.remove(jid);
        }
    }

    public Set<Jid> getBookmarkedJids() {
        synchronized (this.bookmarks) {
            return new HashSet<>(this.bookmarks.keySet());
        }
    }

    public Bookmark getBookmark(final Jid jid) {
        synchronized (this.bookmarks) {
            return this.bookmarks.get(jid.asBareJid());
        }
    }

    public boolean setAvatar(final String filename) {
        if (this.avatar != null && this.avatar.equals(filename)) {
            return false;
        } else {
            this.avatar = filename;
            return true;
        }
    }

    public String getAvatar() {
        return this.avatar;
    }

    public void activateGracePeriod(final long duration) {
        if (duration > 0) {
            this.mEndGracePeriod = SystemClock.elapsedRealtime() + duration;
        }
    }

    public void deactivateGracePeriod() {
        this.mEndGracePeriod = 0L;
    }

    public boolean inGracePeriod() {
        return SystemClock.elapsedRealtime() < this.mEndGracePeriod;
    }

    public String getShareableUri() {
        List<XmppUri.Fingerprint> fingerprints = this.getFingerprints();
        String uri = "xmpp:" + this.getJid().asBareJid().toEscapedString();
        if (fingerprints.size() > 0) {
            return XmppUri.getFingerprintUri(uri, fingerprints, ';');
        } else {
            return uri;
        }
    }

    public String getShareableLink() {
        List<XmppUri.Fingerprint> fingerprints = this.getFingerprints();
        String uri =
                "https://" + XmppUri.INVITE_DOMAIN + "/i/"
                        + XmppUri.lameUrlEncode(this.getJid().asBareJid().toEscapedString());
        if (fingerprints.size() > 0) {
            return XmppUri.getFingerprintUri(uri, fingerprints, '&');
        } else {
            return uri;
        }
    }

    private List<XmppUri.Fingerprint> getFingerprints() {
        ArrayList<XmppUri.Fingerprint> fingerprints = new ArrayList<>();
        if (axolotlService == null) {
            return fingerprints;
        }
        fingerprints.add(
                new XmppUri.Fingerprint(
                        XmppUri.FingerprintType.OMEMO,
                        axolotlService.getOwnFingerprint().substring(2),
                        axolotlService.getOwnDeviceId()));
        for (XmppAxolotlSession session : axolotlService.findOwnSessions()) {
            if (session.getTrust().isVerified() && session.getTrust().isActive()) {
                fingerprints.add(
                        new XmppUri.Fingerprint(
                                XmppUri.FingerprintType.OMEMO,
                                session.getFingerprint().substring(2).replaceAll("\\s", ""),
                                session.getRemoteAddress().getDeviceId()));
            }
        }
        return fingerprints;
    }

    public boolean isBlocked(final ListItem contact) {
        final Jid jid = contact.getJid();
        return jid != null
                && (blocklist.contains(jid.asBareJid()) || blocklist.contains(jid.getDomain()));
    }

    public boolean isBlocked(final Jid jid) {
        return jid != null && blocklist.contains(jid.asBareJid());
    }

    // Read-only: the blocklist is mutated only through the methods below, so callers cannot
    // change it behind the account's back.
    public Collection<Jid> getBlocklist() {
        return Collections.unmodifiableCollection(this.blocklist);
    }

    public void addToBlocklist(final Jid jid) {
        this.blocklist.add(jid);
    }

    public void addToBlocklist(final Collection<Jid> jids) {
        this.blocklist.addAll(jids);
    }

    public void removeFromBlocklist(final Jid jid) {
        this.blocklist.remove(jid);
    }

    public void removeFromBlocklist(final Collection<Jid> jids) {
        this.blocklist.removeAll(jids);
    }

    public void clearBlocklist() {
        this.blocklist.clear();
    }

    public boolean isOnlineAndConnected() {
        return this.getStatus() == State.ONLINE && this.getXmppConnection() != null;
    }

    @Override
    public int getAvatarBackgroundColor() {
        return UIHelper.getColorForName(jid.asBareJid().toString());
    }

    @Override
    public String getAvatarName() {
        throw new IllegalStateException("This method should not be called");
    }

    // Kept in the keys column so it survives a restart: a SASL 2 login has to pick the bind
    // namespace before the server's post-login features arrive. Unknown means Glacier bind.
    public boolean getGlacierBindAvailable() {
        final String value = getKey(KEY_GLACIER_BIND);
        return value == null || Boolean.parseBoolean(value);
    }

    /** Returns true if the value changed and the account needs saving. */
    public boolean setGlacierBindAvailable(final boolean available) {
        if (getGlacierBindAvailable() == available) {
            return false;
        }
        return setKey(KEY_GLACIER_BIND, String.valueOf(available));
    }

    public String getOrganization() {
        return organization;
    }

    public void setOrganization(String organization){
        this.organization = organization;
    }

    public boolean isAdd_to_core_vpn() {
        return add_to_core_vpn;
    }

    public void setAdd_to_core_vpn(boolean add_to_core_vpn) {
        this.add_to_core_vpn = add_to_core_vpn;
    }

    public Boolean getCanPurchaseNumbers() {
        return canPurchaseNumbers;
    }

    public void setCanPurchaseNumbers(Boolean canPurchaseNumbers) {
        this.canPurchaseNumbers = canPurchaseNumbers;
    }

    public List<String> getCoreRegion() {
        return coreRegion;
    }



    public void setCoreRegion(List<String> coreRegion) {
        this.coreRegion = coreRegion;
    }

    public String getExtensionVoiceserver() {
        return extensionVoiceserver;
    }

    public void setExtensionVoiceserver(String extensionVoiceserver) {
        this.extensionVoiceserver = extensionVoiceserver;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public Boolean getSMSEnabled() {
        return isSMSEnabled;
    }

    public void setSMSEnabled(Boolean SMSEnabled) {
        isSMSEnabled = SMSEnabled;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }



    public enum State {
        DISABLED(false, false),
        LOGGED_OUT(false,false),
        OFFLINE(false),
        CONNECTING(false),
        ONLINE(false),
        NO_INTERNET(false),
        AIRPLANE_MODE(false),
        UNAUTHORIZED,
        TEMPORARY_AUTH_FAILURE,
        SERVER_NOT_FOUND,
        REGISTRATION_SUCCESSFUL(false),
        REGISTRATION_FAILED(true, false),
        REGISTRATION_WEB(true, false),
        REGISTRATION_CONFLICT(true, false),
        REGISTRATION_NOT_SUPPORTED(true, false),
        REGISTRATION_PLEASE_WAIT(true, false),
        REGISTRATION_INVALID_TOKEN(true, false),
        REGISTRATION_PASSWORD_TOO_WEAK(true, false),
        TLS_ERROR,
        TLS_ERROR_DOMAIN,
        INCOMPATIBLE_SERVER,
        INCOMPATIBLE_CLIENT,
        DOWNGRADE_ATTACK,
        SESSION_FAILURE,
        BIND_FAILURE,
        HOST_UNKNOWN,
        STREAM_ERROR,
        SEE_OTHER_HOST,
        STREAM_OPENING_ERROR,
        POLICY_VIOLATION,
        PAYMENT_REQUIRED,
        MISSING_INTERNET_PERMISSION(false);

        private final boolean isError;
        private final boolean attemptReconnect;

        State(final boolean isError) {
            this(isError, true);
        }

        State(final boolean isError, final boolean reconnect) {
            this.isError = isError;
            this.attemptReconnect = reconnect;
        }

        State() {
            this(true, true);
        }

        public boolean isError() {
            return this.isError;
        }

        public boolean isAttemptReconnect() {
            return this.attemptReconnect;
        }

        public int getReadableId() {
            switch (this) {
                case DISABLED:
                    return R.string.account_status_disabled;
                case LOGGED_OUT:
                    return R.string.account_state_logged_out;
                case ONLINE:
                    return R.string.account_status_online;
                case CONNECTING:
                    return R.string.account_status_connecting;
                case OFFLINE:
                    return R.string.account_status_offline;
                case UNAUTHORIZED:
                    return R.string.account_status_unauthorized;
                case SERVER_NOT_FOUND:
                    return R.string.account_status_not_found;
                case NO_INTERNET:
                    return R.string.account_status_no_internet;
                case AIRPLANE_MODE:
                    return R.string.account_status_airplane_mode;
                case REGISTRATION_FAILED:
                    return R.string.account_status_regis_fail;
                case REGISTRATION_WEB:
                    return R.string.account_status_regis_web;
                case REGISTRATION_CONFLICT:
                    return R.string.account_status_regis_conflict;
                case REGISTRATION_SUCCESSFUL:
                    return R.string.account_status_regis_success;
                case REGISTRATION_NOT_SUPPORTED:
                    return R.string.account_status_regis_not_sup;
                case REGISTRATION_INVALID_TOKEN:
                    return R.string.account_status_regis_invalid_token;
                case TLS_ERROR:
                    return R.string.account_status_tls_error;
                case TLS_ERROR_DOMAIN:
                    return R.string.account_status_tls_error_domain;
                case INCOMPATIBLE_SERVER:
                    return R.string.account_status_incompatible_server;
                case INCOMPATIBLE_CLIENT:
                    return R.string.account_status_incompatible_client;
                case BIND_FAILURE:
                    return R.string.account_status_bind_failure;
                case SESSION_FAILURE:
                    return R.string.session_failure;
                case DOWNGRADE_ATTACK:
                    return R.string.sasl_downgrade;
                case HOST_UNKNOWN:
                    return R.string.account_status_host_unknown;
                case POLICY_VIOLATION:
                    return R.string.account_status_policy_violation;
                case REGISTRATION_PLEASE_WAIT:
                    return R.string.registration_please_wait;
                case REGISTRATION_PASSWORD_TOO_WEAK:
                    return R.string.registration_password_too_weak;
                case STREAM_ERROR:
                    return R.string.account_status_stream_error;
                case STREAM_OPENING_ERROR:
                    return R.string.account_status_stream_opening_error;
                case PAYMENT_REQUIRED:
                    return R.string.payment_required;
                case SEE_OTHER_HOST:
                    return R.string.reconnect_on_other_host;
                case MISSING_INTERNET_PERMISSION:
                    return R.string.missing_internet_permission;
                case TEMPORARY_AUTH_FAILURE:
                    return R.string.account_status_temporary_auth_failure;
                default:
                    return R.string.account_status_unknown;
            }
        }
    }
}

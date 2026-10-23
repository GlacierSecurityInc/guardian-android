package com.glaciersecurity.glaciermessenger.http.fake;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The state a real Glacier tenant would hold, kept in memory for {@link FakeGlacierBackend}.
 *
 * <p>This is what makes the fake worth having over a folder of canned files. Purchasing a number
 * really does add it to the selected list, releasing one really does remove it, and renaming one
 * really does stick — so the phone module can be exercised end to end without Twilio ever
 * hearing about it. Nothing is persisted: the state is rebuilt from {@link #seed()} whenever the
 * process starts, which is also what makes it safe under the instrumented tier, where every test
 * method gets a fresh process.
 *
 * <p>Every value here comes from a range reserved for documentation — {@code 202-555-01xx} for
 * phone numbers (the fiction block), {@code example.com} for hosts, {@code acme} for the org.
 * See the "No real data in tests" section of {@code src/test/README.md}. Nothing in this file may
 * ever be replaced with a value from a real tenant, however convenient that would be for
 * reproducing a bug: this class compiles into debug APKs that get shared around.
 */
public final class FakeTenant {

    /** One phone number the tenant owns, or could buy. */
    public static final class Number {
        public final String sid;
        public final String e164;
        public String nickname;

        Number(final String sid, final String e164, final String nickname) {
            this.sid = sid;
            this.e164 = e164;
            this.nickname = nickname;
        }
    }

    /** One voicemail recording. */
    public static final class Voicemail {
        public final String recordingSid;
        public final String callSid;
        public final String from;
        public final String to;
        public final String time;
        public final String duration;
        public String status;

        Voicemail(final String recordingSid, final String callSid, final String from,
                  final String to, final String time, final String duration, final String status) {
            this.recordingSid = recordingSid;
            this.callSid = callSid;
            this.from = from;
            this.to = to;
            this.time = time;
            this.duration = duration;
            this.status = status;
        }
    }

    /** One call-history row. */
    public static final class Call {
        public final String sid;
        public final String from;
        public final String to;
        public final String dateCreated;
        public final String status;
        public final String direction;
        public final String duration;

        Call(final String sid, final String from, final String to, final String dateCreated,
             final String status, final String direction, final String duration) {
            this.sid = sid;
            this.from = from;
            this.to = to;
            this.dateCreated = dateCreated;
            this.status = status;
            this.direction = direction;
            this.duration = duration;
        }
    }

    public static final String ORGANIZATION = "acme";
    public static final String USERNAME = "testuser";
    public static final String MESSENGER_ID = "testuser@example.com";

    // A JWT whose payload decodes to {"sub":"guardian"} and whose signature is the literal word
    // "signature". Constructed, never copied from a real token -- the same fixture
    // TwilioAccessTokenParserTest uses, so the two stay recognisably fake together.
    public static final String FAKE_JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJndWFyZGlhbiJ9.c2lnbmF0dXJl";

    private static final AtomicInteger SID_COUNTER = new AtomicInteger(0);

    private static final Object LOCK = new Object();

    private static List<Number> owned = new ArrayList<>();
    private static List<Number> purchasable = new ArrayList<>();
    private static Map<String, List<Voicemail>> voicemails = new LinkedHashMap<>();
    private static Map<String, List<Call>> calls = new LinkedHashMap<>();

    private static boolean smsEnabled = true;
    private static boolean purchaseNumbersAllowed = true;

    static {
        seed();
    }

    private FakeTenant() {
        throw new IllegalStateException("Do not instantiate");
    }

    /** Rebuilds the starting state. Called once at class-load time, and by tests between cases. */
    public static void seed() {
        synchronized (LOCK) {
            SID_COUNTER.set(0);
            owned = new ArrayList<>();
            purchasable = new ArrayList<>();
            voicemails = new LinkedHashMap<>();
            calls = new LinkedHashMap<>();
            smsEnabled = true;
            purchaseNumbersAllowed = true;

            final Number first = new Number(nextSid("PN"), "+12025550142", "Work");
            final Number second = new Number(nextSid("PN"), "+12025550143", "");
            owned.add(first);
            owned.add(second);

            // Enough to page, and deliberately not contiguous with the owned pair so a test can
            // tell "the list came back" from "the list came back unchanged".
            for (int i = 150; i < 162; i++) {
                purchasable.add(new Number(nextSid("PN"), "+1202555" + String.format(Locale.ROOT, "%04d", i), ""));
            }

            voicemails.put(first.e164, new ArrayList<>(List.of(
                    new Voicemail(nextSid("RE"), nextSid("CA"), "+12025550188", first.e164,
                            "2026-09-14T09:12:00Z", "17", "new"),
                    new Voicemail(nextSid("RE"), nextSid("CA"), "+12025550190", first.e164,
                            "2026-09-13T16:40:00Z", "42", "read"))));
            voicemails.put(second.e164, new ArrayList<>());

            calls.put(first.e164, new ArrayList<>(List.of(
                    new Call(nextSid("CA"), "+12025550188", first.e164,
                            "2026-09-14T09:11:40Z", "completed", "inbound", "31"),
                    new Call(nextSid("CA"), first.e164, "+12025550191",
                            "2026-09-14T08:02:10Z", "completed", "outbound-dial", "126"),
                    // A missed inbound call: HistoryItem.isMissedInbound keys the unread badge
                    // off exactly this pairing, so leaving it out would make the badge untestable.
                    new Call(nextSid("CA"), "+12025550193", first.e164,
                            "2026-09-13T21:55:02Z", "no-answer", "inbound", "0"))));
            calls.put(second.e164, new ArrayList<>());
        }
    }

    private static String nextSid(final String prefix) {
        return prefix + String.format(Locale.ROOT, "%032d", SID_COUNTER.incrementAndGet());
    }

    public static List<Number> owned() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(owned));
        }
    }

    public static List<Number> purchasable() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(purchasable));
        }
    }

    /**
     * Moves a number out of the purchasable pool and into the owned list.
     *
     * @return the newly owned number, or null when it was not on offer — which is how the real
     *         console answers a number somebody else bought between listing and purchasing.
     */
    public static Number purchase(final String e164) {
        synchronized (LOCK) {
            final String wanted = normalize(e164);
            for (int i = 0; i < purchasable.size(); i++) {
                if (normalize(purchasable.get(i).e164).equals(wanted)) {
                    final Number bought = purchasable.remove(i);
                    owned.add(bought);
                    voicemails.put(bought.e164, new ArrayList<>());
                    calls.put(bought.e164, new ArrayList<>());
                    return bought;
                }
            }
            return null;
        }
    }

    /** @return true when the number was owned and has been given up. */
    public static boolean release(final String e164) {
        synchronized (LOCK) {
            final String wanted = normalize(e164);
            for (int i = 0; i < owned.size(); i++) {
                if (normalize(owned.get(i).e164).equals(wanted)) {
                    final Number released = owned.remove(i);
                    released.nickname = "";
                    purchasable.add(released);
                    voicemails.remove(released.e164);
                    calls.remove(released.e164);
                    return true;
                }
            }
            return false;
        }
    }

    /** @return true when the number was owned and now carries the new nickname. */
    public static boolean rename(final String e164, final String nickname) {
        synchronized (LOCK) {
            final String wanted = normalize(e164);
            for (final Number number : owned) {
                if (normalize(number.e164).equals(wanted)) {
                    number.nickname = nickname == null ? "" : nickname;
                    return true;
                }
            }
            return false;
        }
    }

    public static List<Voicemail> voicemailsFor(final String e164) {
        synchronized (LOCK) {
            final List<Voicemail> found = voicemails.get(keyFor(e164));
            return found == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(found));
        }
    }

    public static List<Call> callsFor(final String e164) {
        synchronized (LOCK) {
            final List<Call> found = calls.get(keyFor(e164));
            return found == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(found));
        }
    }

    /** Marks one voicemail read or unread. @return true when it was found. */
    public static boolean setVoicemailStatus(final String recordingSid, final String status) {
        synchronized (LOCK) {
            for (final List<Voicemail> list : voicemails.values()) {
                for (final Voicemail voicemail : list) {
                    if (voicemail.recordingSid.equals(recordingSid)) {
                        voicemail.status = status;
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * Applies one status to every voicemail on a number. @return how many changed.
     *
     * <p>Takes the status rather than assuming "read": Delete All sends {@code deleted}
     * (VoicemailRecord.VM_Status.DELETED.label) through the same endpoint.
     */
    public static int setAllVoicemailStatuses(final String e164, final String status) {
        synchronized (LOCK) {
            final List<Voicemail> list = voicemails.get(keyFor(e164));
            if (list == null || status == null) {
                return 0;
            }
            int changed = 0;
            for (final Voicemail voicemail : list) {
                if (!status.equals(voicemail.status)) {
                    voicemail.status = status;
                    changed++;
                }
            }
            return changed;
        }
    }

    /** Drops one call-history row. @return true when it was found. */
    public static boolean deleteCall(final String sid) {
        synchronized (LOCK) {
            for (final List<Call> list : calls.values()) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).sid.equals(sid)) {
                        list.remove(i);
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** Drops every call-history row on one number. @return how many went. */
    public static int deleteAllCalls(final String e164) {
        synchronized (LOCK) {
            final List<Call> list = calls.get(keyFor(e164));
            if (list == null) {
                return 0;
            }
            final int size = list.size();
            list.clear();
            return size;
        }
    }

    public static boolean isSmsEnabled() {
        return smsEnabled;
    }

    public static void setSmsEnabled(final boolean enabled) {
        smsEnabled = enabled;
    }

    public static boolean isPurchaseNumbersAllowed() {
        return purchaseNumbersAllowed;
    }

    public static void setPurchaseNumbersAllowed(final boolean allowed) {
        purchaseNumbersAllowed = allowed;
    }

    /**
     * The app sends numbers in several spellings -- E.164 off the wire, the reformatted display
     * form out of a view, and occasionally without the country code. Comparing on digits alone
     * means a fake never rejects a request for a reason the real backend would not.
     */
    static String normalize(final String number) {
        if (number == null) {
            return "";
        }
        final StringBuilder digits = new StringBuilder();
        for (int i = 0; i < number.length(); i++) {
            final char c = number.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        // Treat a 10-digit NANP number and its +1 form as the same number.
        if (digits.length() == 10) {
            return "1" + digits;
        }
        return digits.toString();
    }

    private static String keyFor(final String e164) {
        final String wanted = normalize(e164);
        for (final String key : voicemails.keySet()) {
            if (normalize(key).equals(wanted)) {
                return key;
            }
        }
        for (final String key : calls.keySet()) {
            if (normalize(key).equals(wanted)) {
                return key;
            }
        }
        return e164 == null ? "" : e164;
    }
}

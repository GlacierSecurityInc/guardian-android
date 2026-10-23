package com.glaciersecurity.glaciermessenger.http.fake;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * The denylist that lets a debug build be pointed at a real tenant.
 *
 * <p>Exercised through {@link DestructiveWriteGuard#blockReason} rather than the interceptor,
 * because the {@code BuildConfig} gate is false in every unit-test build — a guard tested only
 * through {@code interceptorOrNull} would not be tested at all.
 */
public class DestructiveWriteGuardTest {

    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");

    @Test
    public void buyingAndReleasingNumbersIsRefused() {
        // The two that cost money. Releasing is the worse one: Twilio does not guarantee the
        // same number can be reclaimed, so it is irreversible in practice.
        assertThat(blockReason("/api/mobile/purchaseTwilioNumber", "purchaseNum=%2B12025550150"))
                .isNotNull();
        assertThat(blockReason("/api/mobile/releaseTwilioNumber", "releaseNumber=%2B12025550142"))
                .isNotNull();
    }

    @Test
    public void deletingHistoryIsRefused() {
        assertThat(blockReason("/api/mobile/deleteCallLog", "callSid=CA1")).isNotNull();
        assertThat(blockReason("/api/mobile/remove-call-history", "number=%2B12025550142"))
                .isNotNull();
        assertThat(blockReason("/api/mobile/clearEjabberdChatHistory", "")).isNotNull();
    }

    @Test
    public void deletingVoicemailIsRefusedButMarkingItReadIsNot() {
        // Same endpoint, opposite verdicts -- the guard has to read the body, not just the path.
        assertThat(blockReason("/api/mobile/updateVoicemailStatus",
                "recordingSid=RE1&status=deleted")).isNotNull();
        assertThat(blockReason("/api/mobile/updateAllVoicemailStatus",
                "number=%2B12025550142&status=deleted")).isNotNull();

        assertThat(blockReason("/api/mobile/updateVoicemailStatus",
                "recordingSid=RE1&status=read")).isNull();
        assertThat(blockReason("/api/mobile/updateAllVoicemailStatus",
                "number=%2B12025550142&status=read")).isNull();
    }

    @Test
    public void aDestructiveStatusIsRecognisedWhateverItsCase() {
        assertThat(blockReason("/api/mobile/updateVoicemailStatus",
                "recordingSid=RE1&status=DELETED")).isNotNull();
        assertThat(blockReason("/api/mobile/updateVoicemailStatus",
                "recordingSid=RE1&status=%20Deleted%20")).isNotNull();
    }

    @Test
    public void everythingElseIsLetThrough() {
        // A guard that blocked too much would be switched off, which is worse than a narrow one
        // that stays on. Renaming and reading are free and undoable from the UI.
        assertThat(blockReason("/api/mobile/nickname",
                "twilioNumber=%2B12025550142&nickname=Front+Desk")).isNull();
        assertThat(blockReason("/api/mobile/getUserSelectedTwilioNumbers", "organization=acme"))
                .isNull();
        assertThat(blockReason("/api/mobile/call-history", "number=%2B12025550142")).isNull();
        assertThat(blockReason("/api/mobile/voicemail_list", "number=%2B12025550142")).isNull();
    }

    @Test
    public void nonGlacierTrafficIsNotTheGuardsBusiness() {
        // An XMPP HTTP-upload slot, an avatar, a media download. Blocking on a path that merely
        // looks similar would break unrelated traffic.
        assertThat(blockReason("/upload/abcdef/purchaseTwilioNumber", "")).isNull();
    }

    private static String blockReason(final String path, final String form) {
        return DestructiveWriteGuard.blockReason(new Request.Builder()
                .url("https://api.example.com" + path)
                .post(RequestBody.create(form, FORM))
                .build());
    }
}

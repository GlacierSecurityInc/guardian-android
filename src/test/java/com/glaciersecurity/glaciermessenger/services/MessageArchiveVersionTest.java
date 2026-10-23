package com.glaciersecurity.glaciermessenger.services;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.services.MessageArchiveService.Version;
import com.glaciersecurity.glaciermessenger.xml.Element;

import org.junit.Test;

import java.lang.reflect.Method;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import im.conversations.android.xmpp.model.stanza.Message;

/**
 * {@link Version} decides which MAM namespace the client speaks to a server, and then which
 * incoming stanzas count as archived messages. Both halves fail silently when wrong: negotiate the
 * wrong namespace and catch-up returns nothing, so the chat just looks empty; miss a result
 * element and the message is dropped on the floor with no error anywhere.
 *
 * <p>The selection itself is driven by enum declaration order -- {@code get} walks {@code values()}
 * backwards and takes the first namespace the server advertises -- so these tests pin the order as
 * well as the namespaces. Reordering the constants for tidiness would quietly downgrade every
 * connection.
 *
 * <p>Plain JUnit: Version touches only String, List and Element.
 */
public class MessageArchiveVersionTest {

    @Test
    public void theNamespacesAreTheOnesTheXepsDefine() {
        assertThat(Version.MAM_0.namespace).isEqualTo("urn:xmpp:mam:0");
        assertThat(Version.MAM_1.namespace).isEqualTo("urn:xmpp:mam:1");
        assertThat(Version.MAM_2.namespace).isEqualTo("urn:xmpp:mam:2");
    }

    /**
     * Only mam:0 is legacy. The flag changes how queries are formed, so a version marked legacy by
     * mistake talks mam:0 syntax to a mam:2 server.
     */
    @Test
    public void onlyMamZeroIsLegacy() {
        assertThat(Version.MAM_0.legacy).isTrue();
        assertThat(Version.MAM_1.legacy).isFalse();
        assertThat(Version.MAM_2.legacy).isFalse();
    }

    /**
     * Declaration order is load-bearing: {@code get(List)} iterates values() from the end, so the
     * newest version has to be declared last for it to be preferred. The selection tests below go
     * through {@code get} itself; this one says why the order it walks matters.
     */
    @Test
    public void newestVersionIsDeclaredLast() {
        assertThat(Arrays.asList(Version.values()))
                .containsExactly(Version.MAM_0, Version.MAM_1, Version.MAM_2)
                .inOrder();
    }

    // ---- which version gets negotiated ------------------------------------------------------

    /**
     * {@code Version.get(List)} is private, and its two public callers both need a live
     * {@code XmppConnection} to reach it. Reflection is the only way to drive the selection itself
     * without one, and driving it is the point: the enum-order test above is a statement about the
     * data, not about the code that reads it, and every mutation of {@code get} leaves it green.
     *
     * <p>A rename makes {@code getDeclaredMethod} throw, so this fails loudly rather than quietly
     * testing nothing.
     */
    private static Version select(final String... advertisedFeatures) {
        try {
            final Method get = Version.class.getDeclaredMethod("get", List.class);
            get.setAccessible(true);
            return (Version) get.invoke(null, Arrays.asList(advertisedFeatures));
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("Version.get(List) is no longer reachable", e);
        }
    }

    @Test
    public void theNewestAdvertisedVersionIsSelected() {
        assertThat(select("urn:xmpp:mam:0", "urn:xmpp:mam:1", "urn:xmpp:mam:2"))
                .isEqualTo(Version.MAM_2);
        assertThat(select("urn:xmpp:mam:0", "urn:xmpp:mam:1")).isEqualTo(Version.MAM_1);
        assertThat(select("urn:xmpp:mam:0")).isEqualTo(Version.MAM_0);
    }

    /** A server lists its features in whatever order it likes; ours is the one that decides. */
    @Test
    public void theOrderTheServerAdvertisesThemInDoesNotMatter() {
        assertThat(select("urn:xmpp:mam:2", "urn:xmpp:mam:0")).isEqualTo(Version.MAM_2);
        assertThat(select("urn:xmpp:mam:0", "urn:xmpp:mam:2")).isEqualTo(Version.MAM_2);
    }

    @Test
    public void aServerAdvertisingOnlyTheNewestGetsIt() {
        assertThat(select("urn:xmpp:mam:2")).isEqualTo(Version.MAM_2);
        assertThat(select("urn:xmpp:mam:1")).isEqualTo(Version.MAM_1);
    }

    @Test
    public void unrelatedFeaturesAreIgnored() {
        assertThat(select("urn:xmpp:carbons:2", "urn:xmpp:mam:2", "urn:xmpp:sm:3"))
                .isEqualTo(Version.MAM_2);
    }

    /**
     * With nothing advertised the answer is MAM_0 rather than null, so the caller always has a
     * namespace to talk. It is the legacy one, which is the safe direction: a server that does
     * support MAM but did not say so still gets a query it can parse.
     */
    @Test
    public void aServerAdvertisingNoMamFallsBackToTheLegacyVersion() {
        assertThat(select()).isEqualTo(Version.MAM_0);
        assertThat(select("urn:xmpp:carbons:2", "urn:xmpp:mam")).isEqualTo(Version.MAM_0);
    }

    @Test
    public void hasIsTrueForAnyKnownNamespace() {
        assertThat(Version.has(Collections.singletonList("urn:xmpp:mam:2"))).isTrue();
        assertThat(Version.has(Collections.singletonList("urn:xmpp:mam:1"))).isTrue();
        assertThat(Version.has(Collections.singletonList("urn:xmpp:mam:0"))).isTrue();
    }

    @Test
    public void hasIsFalseWhenTheServerAdvertisesNoMam() {
        final List<String> features =
                Arrays.asList("urn:xmpp:carbons:2", "urn:xmpp:sm:3", "urn:xmpp:mam");
        assertThat(Version.has(features)).isFalse();
    }

    @Test
    public void hasIsFalseForAnEmptyFeatureList() {
        assertThat(Version.has(Collections.emptyList())).isFalse();
    }

    @Test
    public void findResultLocatesAResultInAnySupportedNamespace() {
        for (final Version version : Version.values()) {
            final Message packet = new Message();
            packet.addChild("result", version.namespace);
            final Element result = Version.findResult(packet);
            assertThat(result).isNotNull();
            assertThat(result.getAttribute("xmlns")).isEqualTo(version.namespace);
        }
    }

    @Test
    public void findResultIgnoresAResultInAnUnrelatedNamespace() {
        final Message packet = new Message();
        packet.addChild("result", "urn:xmpp:mam");
        assertThat(Version.findResult(packet)).isNull();
    }

    @Test
    public void findResultIgnoresAnotherElementInTheMamNamespace() {
        final Message packet = new Message();
        packet.addChild("fin", Version.MAM_2.namespace);
        assertThat(Version.findResult(packet)).isNull();
    }

    @Test
    public void findResultReturnsNullForAPlainMessage() {
        assertThat(Version.findResult(new Message())).isNull();
    }
}

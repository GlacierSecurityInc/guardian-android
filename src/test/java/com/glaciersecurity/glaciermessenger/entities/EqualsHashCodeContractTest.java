package com.glaciersecurity.glaciermessenger.entities;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.ui.adapter.ExpandableListItem;
import com.glaciersecurity.glaciermessenger.xmpp.pep.Avatar;

import org.junit.Test;

/**
 * The four classes that gained a {@code hashCode()} to match an existing {@code equals()}.
 *
 * <p>The rule these answer is only satisfied when the two agree, so asserting that {@code equals()}
 * returns true is not enough on its own — a test that stopped there would pass just as happily
 * against the inherited identity {@code hashCode}, which is exactly the state being fixed. Each
 * case therefore checks both halves, plus the null field the implementations have to survive.
 */
public class EqualsHashCodeContractTest {

    private static GlacierOrgEntity org(final String organization) {
        return new GlacierOrgEntity(
                Boolean.FALSE, "dns-id", false, organization,
                false, false, false, "60", false, false);
    }

    private static Avatar avatar(final String sha1sum) {
        final Avatar avatar = new Avatar();
        avatar.sha1sum = sha1sum;
        return avatar;
    }

    @Test
    public void glacierOrgEntityAgreesOnEqualOrganizations() {
        assertThat(org("acme")).isEqualTo(org("acme"));
        assertThat(org("acme").hashCode()).isEqualTo(org("acme").hashCode());
    }

    @Test
    public void glacierOrgEntityDistinguishesOrganizations() {
        assertThat(org("acme")).isNotEqualTo(org("other"));
    }

    @Test
    public void glacierOrgEntityToleratesANullOrganization() {
        assertThat(org(null)).isEqualTo(org(null));
        assertThat(org(null).hashCode()).isEqualTo(org(null).hashCode());
    }

    @Test
    public void latestVersionEntityAgreesOnEqualKeys() {
        final LatestVersionEntity a = new LatestVersionEntity("a", "b", "c", "d", "e", "key");
        final LatestVersionEntity b = new LatestVersionEntity("z", "y", "x", "w", "v", "key");
        // Only the key participates in equals, so these differ in every other field and are
        // still equal — which is precisely why hashCode must not fold the others in.
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    public void latestVersionEntityDistinguishesKeys() {
        final LatestVersionEntity a = new LatestVersionEntity("a", "b", "c", "d", "e", "one");
        final LatestVersionEntity b = new LatestVersionEntity("a", "b", "c", "d", "e", "two");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    public void expandableListItemAgreesOnEqualNames() {
        final ExpandableListItem a = new ExpandableListItem(1, "name", "description");
        final ExpandableListItem b = new ExpandableListItem(2, "name", "other description");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    public void expandableListItemDistinguishesNames() {
        final ExpandableListItem a = new ExpandableListItem(1, "one", "description");
        final ExpandableListItem b = new ExpandableListItem(1, "two", "description");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    public void avatarAgreesOnEqualFilenames() {
        assertThat(avatar("abc")).isEqualTo(avatar("abc"));
        assertThat(avatar("abc").hashCode()).isEqualTo(avatar("abc").hashCode());
    }

    @Test
    public void avatarDistinguishesFilenames() {
        assertThat(avatar("abc")).isNotEqualTo(avatar("def"));
    }

    @Test
    public void avatarWithAnUnsetFilenameDoesNotThrow() {
        // The old equals() dereferenced the other side's filename unconditionally, so this pair
        // threw NullPointerException instead of answering.
        assertThat(avatar(null)).isEqualTo(avatar(null));
        assertThat(avatar(null).hashCode()).isEqualTo(0);
        assertThat(avatar(null)).isNotEqualTo(avatar("abc"));
        assertThat(avatar("abc")).isNotEqualTo(avatar(null));
    }

    @Test
    public void avatarIsNotEqualToOtherTypes() {
        assertThat(avatar("abc")).isNotEqualTo("abc");
        assertThat(avatar("abc")).isNotEqualTo(null);
    }
}

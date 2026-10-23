package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Message search builds an SQLite FTS MATCH expression out of whatever the user typed. A stray
 * quote or a bare asterisk is a syntax error in FTS, so the cleaning here is what stops search
 * from failing on ordinary input.
 */
public class FtsUtilsTest {

    @Test
    public void splitsOnWhitespace() {
        assertThat(FtsUtils.parse("hello world")).containsExactly("hello", "world").inOrder();
    }

    @Test
    public void collapsesRepeatedWhitespace() {
        assertThat(FtsUtils.parse("  hello   world  "))
                .containsExactly("hello", "world")
                .inOrder();
    }

    @Test
    public void quotesAreNotPassedThroughToFts() {
        assertThat(FtsUtils.parse("\"hello world\""))
                .containsExactly("hello", "world")
                .inOrder();
    }

    @Test
    public void leadingAndTrailingAsterisksAreStripped() {
        assertThat(FtsUtils.parse("*hello*")).containsExactly("hello");
    }

    @Test
    public void anAsteriskInsideATermIsKept() {
        assertThat(FtsUtils.parse("he*lo")).containsExactly("he*lo");
    }

    @Test
    public void aTermOfNothingButAsterisksIsDropped() {
        assertThat(FtsUtils.parse("***")).isEmpty();
    }

    @Test
    public void everyTermBecomesAPrefixMatch() {
        assertThat(FtsUtils.toMatchString(Arrays.asList("hello", "world")))
                .isEqualTo("hello* world*");
    }

    @Test
    public void booleanKeywordsAreUpperCasedForFts() {
        assertThat(FtsUtils.toMatchString(Arrays.asList("hello", "or", "world")))
                .isEqualTo("hello* OR world*");
        assertThat(FtsUtils.toMatchString(Collections.singletonList("and"))).isEqualTo("AND");
    }

    @Test
    public void aTermThatAlreadyHasAWildcardIsNotGivenAnother() {
        assertThat(FtsUtils.toMatchString(Collections.singletonList("he*lo"))).isEqualTo("he*lo");
    }

    @Test
    public void negationIsPreservedWithoutAPrefixWildcard() {
        assertThat(FtsUtils.toMatchString(Collections.singletonList("-spam"))).isEqualTo("-spam");
    }

    @Test
    public void emptyInputProducesAnEmptyMatchExpression() {
        assertThat(FtsUtils.toMatchString(Collections.emptyList())).isEmpty();
    }

    @Test
    public void whatTheUserTypedCanBeRebuiltForTheSearchField() {
        final List<String> terms = FtsUtils.parse("hello world");
        assertThat(FtsUtils.toUserEnteredString(terms)).isEqualTo("hello world");
    }

    @Test
    public void aBooleanKeywordSurvivesParsingAsTheUserTypedIt() {
        assertThat(FtsUtils.parse("a or b")).containsExactly("a", "or", "b").inOrder();
        assertThat(FtsUtils.isKeyword("and")).isTrue();
        assertThat(FtsUtils.isKeyword("OR")).isTrue();
        assertThat(FtsUtils.isKeyword("hello")).isFalse();
    }

    @Test
    public void anEmptyQueryParsesToNoTerms() {
        assertThat(FtsUtils.parse("")).isEmpty();
        assertThat(FtsUtils.toUserEnteredString(Collections.emptyList())).isEmpty();
    }

    /**
     * A trailing wildcard is stripped on the way in and put back by toMatchString, so "foo*" and
     * "foo" search the same thing. An interior one has no such equivalent and is passed through.
     */
    @Test
    public void parsingAndMatchBuildingRoundTripAWildcard() {
        assertThat(FtsUtils.toMatchString(FtsUtils.parse("foo*"))).isEqualTo("foo*");
        assertThat(FtsUtils.toMatchString(FtsUtils.parse("fo*o"))).isEqualTo("fo*o");
    }
}

package com.glaciersecurity.glaciermessenger.testutil;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.glaciersecurity.glaciermessenger.xml.Element;

import org.junit.Test;

/**
 * A test for a test helper, which is only worth writing because this one carries a guard. The
 * hand-written version of {@code withAttributes} was written three times in this suite and had the
 * same off-by-one each time; now that there is one copy, the guard is worth proving rather than
 * assuming.
 */
public class ElementsTest {

    @Test
    public void pairsAreSetAsAttributes() {
        final Element element =
                Elements.withAttributes(new Element("candidate"), "ip", "10.0.0.1", "port", "5000");

        assertThat(element.getAttribute("ip")).isEqualTo("10.0.0.1");
        assertThat(element.getAttribute("port")).isEqualTo("5000");
    }

    @Test
    public void noPairsLeavesTheElementAlone() {
        assertThat(Elements.withAttributes(new Element("candidate")).getAttribute("ip")).isNull();
    }

    @Test
    public void theElementItselfIsReturnedSoCallsCanBeNested() {
        final Element element = new Element("candidate");

        assertThat(Elements.withAttributes(element, "ip", "10.0.0.1")).isSameInstanceAs(element);
    }

    @Test
    public void anOddNumberOfValuesIsRefused() {
        // The whole reason this is shared: the loop reads pairs[i + 1], so an odd count walks off
        // the end of the array. Said plainly instead.
        final IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Elements.withAttributes(new Element("candidate"), "ip"));

        assertThat(thrown).hasMessageThat().contains("alternating name/value pairs");
    }

    @Test
    public void aLaterPairOverwritesAnEarlierOneOfTheSameName() {
        final Element element =
                Elements.withAttributes(new Element("candidate"), "ip", "10.0.0.1", "ip", "10.0.0.2");

        assertThat(element.getAttribute("ip")).isEqualTo("10.0.0.2");
    }
}

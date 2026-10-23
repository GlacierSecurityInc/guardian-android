package com.glaciersecurity.glaciermessenger.testutil;

import com.glaciersecurity.glaciermessenger.xml.Element;

/**
 * Building an {@link Element} from alternating name/value pairs, which the stanza tests do
 * constantly: a stanza fixture is mostly attributes, and writing them as pairs keeps a test
 * readable where a chain of {@code setAttribute} calls does not.
 *
 * <p>This exists as one helper rather than one per test class because the obvious hand-written
 * version has a sharp edge -- the loop reads {@code pairs[i + 1]}, so an odd argument count walks
 * off the end of the array. That was written three separate times in this suite and flagged three
 * separate times in review before being pulled out here. The guard belongs in one place.
 */
public final class Elements {

    private Elements() {}

    /**
     * Sets alternating name/value pairs on {@code element} and returns it.
     *
     * @throws IllegalArgumentException if an odd number of values is given, which is always a
     *     typo in the caller rather than something to interpret.
     */
    public static <T extends Element> T withAttributes(
            final T element, final String... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "expected alternating name/value pairs, got "
                            + nameValuePairs.length
                            + " values");
        }
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            element.setAttribute(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return element;
    }
}

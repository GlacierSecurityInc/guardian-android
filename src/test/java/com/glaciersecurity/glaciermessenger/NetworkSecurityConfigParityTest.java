package com.glaciersecurity.glaciermessenger;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The debug source set shadows the platform network security config so instrumented
 * fault-injection tests can reach a host-side MockWebServer. Shadowing means the pin-sets are
 * duplicated, and a duplicated pin-set is a pin rotation waiting to be applied to only one
 * file — which would leave debug builds trusting a CA production no longer accepts, or worse,
 * hide a pinning failure until release.
 *
 * <p>These tests read both files off disk and hold them in step.
 */
public class NetworkSecurityConfigParityTest {

    private static final File MAIN = new File("src/main/res/xml/network_security_configuration.xml");
    private static final File DEBUG = new File("src/debug/res/xml/network_security_configuration.xml");

    /** The extra hosts the debug config is allowed to add on top of the main one. */
    private static final Set<String> DEBUG_ONLY_DOMAINS =
            new LinkedHashSet<>(List.of("10.0.2.2", "127.0.0.1", "localhost"));

    @Test
    public void bothConfigsArePresent() {
        assertThat(MAIN.isFile()).isTrue();
        assertThat(DEBUG.isFile()).isTrue();
    }

    @Test
    public void everyPinnedHostInTheMainConfigIsPinnedIdenticallyInDebug() {
        final Map<String, Set<String>> main = pinsByDomain(MAIN);
        final Map<String, Set<String>> debug = pinsByDomain(DEBUG);

        assertThat(main).isNotEmpty();
        for (final Map.Entry<String, Set<String>> entry : main.entrySet()) {
            assertThat(debug).containsKey(entry.getKey());
            assertThat(debug.get(entry.getKey())).containsExactlyElementsIn(entry.getValue());
        }
    }

    @Test
    public void debugAddsNoPinnedHostOfItsOwn() {
        assertThat(pinsByDomain(DEBUG).keySet())
                .containsExactlyElementsIn(pinsByDomain(MAIN).keySet());
    }

    @Test
    public void debugOnlyLoosensTheHostsItIsMeantTo() {
        final Set<String> extra = new LinkedHashSet<>(allDomains(DEBUG));
        extra.removeAll(allDomains(MAIN));

        assertThat(extra).containsExactlyElementsIn(DEBUG_ONLY_DOMAINS);
    }

    @Test
    public void theExtraDebugHostsAreLoopbackOnlyAndUnpinned() {
        // Permitting cleartext to a pinned production host would be a very quiet disaster.
        for (final Element config : elements(parse(DEBUG), "domain-config")) {
            if (!"true".equals(config.getAttribute("cleartextTrafficPermitted"))) {
                continue;
            }
            assertThat(DEBUG_ONLY_DOMAINS).containsAtLeastElementsIn(domainsOf(config));
            assertThat(elements(config, "pin-set")).isEmpty();
        }
    }

    @Test
    public void theMainConfigStaysStrict() {
        final Document main = parse(MAIN);

        // A debug-overrides block here, or a cleartext host, would mean the debug file had been
        // copied over the release one.
        assertThat(elements(main, "debug-overrides")).isEmpty();
        for (final Element config : elements(main, "domain-config")) {
            assertThat(config.getAttribute("cleartextTrafficPermitted")).isNotEqualTo("true");
        }
        assertThat(main.getDocumentElement().getElementsByTagName("base-config").getLength())
                .isEqualTo(1);
    }

    @Test
    public void debugTrustsUserInstalledCertificatesOnlyBehindDebugOverrides() {
        final Document debug = parse(DEBUG);
        final List<Element> overrides = elements(debug, "debug-overrides");
        assertThat(overrides).hasSize(1);

        final Set<String> anchors = new LinkedHashSet<>();
        for (final Element certificates : elements(overrides.get(0), "certificates")) {
            anchors.add(certificates.getAttribute("src"));
        }
        assertThat(anchors).containsExactly("system", "user");

        // Outside debug-overrides nothing may trust user certificates, or a debug build would
        // accept an interception proxy against production too.
        for (final Element certificates : elements(debug, "certificates")) {
            if (isInsideDebugOverrides(certificates)) {
                continue;
            }
            assertThat(certificates.getAttribute("src")).isEqualTo("system");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Map<String, Set<String>> pinsByDomain(final File file) {
        final Map<String, Set<String>> byDomain = new LinkedHashMap<>();
        for (final Element config : elements(parse(file), "domain-config")) {
            final List<Element> pinSets = elements(config, "pin-set");
            if (pinSets.isEmpty()) {
                continue;
            }
            final Set<String> pins = new LinkedHashSet<>();
            for (final Element pinSet : pinSets) {
                for (final Element pin : elements(pinSet, "pin")) {
                    pins.add(pin.getAttribute("digest") + "/" + pin.getTextContent().trim());
                }
            }
            for (final String domain : domainsOf(config)) {
                byDomain.put(domain, pins);
            }
        }
        return byDomain;
    }

    private static Set<String> allDomains(final File file) {
        final Set<String> domains = new LinkedHashSet<>();
        for (final Element config : elements(parse(file), "domain-config")) {
            domains.addAll(domainsOf(config));
        }
        return domains;
    }

    private static Set<String> domainsOf(final Element domainConfig) {
        final Set<String> domains = new LinkedHashSet<>();
        for (final Element domain : elements(domainConfig, "domain")) {
            domains.add(domain.getTextContent().trim());
        }
        return domains;
    }

    private static boolean isInsideDebugOverrides(final Node node) {
        for (Node parent = node.getParentNode(); parent != null; parent = parent.getParentNode()) {
            if ("debug-overrides".equals(parent.getNodeName())) {
                return true;
            }
        }
        return false;
    }

    private static List<Element> elements(final Document document, final String tag) {
        return elements(document.getDocumentElement(), tag);
    }

    private static List<Element> elements(final Element root, final String tag) {
        final List<Element> found = new ArrayList<>();
        final NodeList nodes = root.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            found.add((Element) nodes.item(i));
        }
        return found;
    }

    private static Document parse(final File file) {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(file);
        } catch (final Exception e) {
            throw new AssertionError("could not parse " + file, e);
        }
    }
}

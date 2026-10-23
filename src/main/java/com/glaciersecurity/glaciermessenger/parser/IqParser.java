package com.glaciersecurity.glaciermessenger.parser;

import android.text.TextUtils;
import com.glaciersecurity.glaciermessenger.utils.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.CharMatcher;
import com.google.common.io.BaseEncoding;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.state.PreKeyBundle;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.AxolotlService;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Room;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;
import com.glaciersecurity.glaciermessenger.xmpp.InvalidJid;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.OnUpdateBlocklist;
import com.glaciersecurity.glaciermessenger.xmpp.forms.Data;
import im.conversations.android.xmpp.model.stanza.Iq;

public class IqParser extends AbstractParser implements Consumer<Iq> {

    public IqParser(final XmppConnectionService service, final Account account) {
        super(service, account);
    }

    public static List<Jid> items(final Iq packet) {
        ArrayList<Jid> items = new ArrayList<>();
        final Element query = packet.findChild("query", Namespace.DISCO_ITEMS);
        if (query == null) {
            return items;
        }
        for (Element child : query.getChildren()) {
            if ("item".equals(child.getName())) {
                Jid jid = child.getAttributeAsJid("jid");
                if (jid != null) {
                    items.add(jid);
                }
            }
        }
        return items;
    }

    public static Room parseRoom(Iq packet) {
        final Element query = packet.findChild("query", Namespace.DISCO_INFO);
        if (query == null) {
            return null;
        }
        final Element x = query.findChild("x");
        if (x == null) {
            return null;
        }
        final Element identity = query.findChild("identity");
        Data data = Data.parse(x);
        String address = packet.getFrom().toString();
        String name = identity == null ? null : identity.getAttribute("name");
        String roomName = data.getValue("muc#roomconfig_roomname");
        String description = data.getValue("muc#roominfo_description");
        String language = data.getValue("muc#roominfo_lang");
        String occupants = data.getValue("muc#roominfo_occupants");
        int nusers;
        try {
            nusers = occupants == null ? 0 : Integer.parseInt(occupants);
        } catch (NumberFormatException e) {
            nusers = 0;
        }

        boolean membersOnly = false;
        for (final Element feature : query.getChildren()) {
            if ("feature".equals(feature.getName()) &&
                    "muc_membersonly".equals(feature.getAttribute("var"))) {
                membersOnly = true;
                break;
            }
        }

        return new Room(
                address,
                TextUtils.isEmpty(roomName) ? name : roomName,
                description,
                language,
                nusers,
                membersOnly
        );
    }

    private void rosterItems(final Account account, final Element query) {
        final String version = query.getAttribute("ver");
        if (version != null) {
            account.getRoster().setVersion(version);
        }
        for (final Element item : query.getChildren()) {
            if (item.getName().equals("item")) {
                final Jid jid = InvalidJid.getNullForInvalid(item.getAttributeAsJid("jid"));
                if (jid == null) {
                    continue;
                }
                final String name = item.getAttribute("name");
                final String subscription = item.getAttribute("subscription");
                final Contact contact = account.getRoster().getContact(jid);
                boolean bothPre = contact.getOption(Contact.Options.TO) && contact.getOption(Contact.Options.FROM);
                if (!contact.getOption(Contact.Options.DIRTY_PUSH)) {
                    contact.setServerName(name);
                    contact.parseGroupsFromElement(item);
                }
                if ("remove".equals(subscription)) {
                    contact.resetOption(Contact.Options.IN_ROSTER);
                    contact.resetOption(Contact.Options.DIRTY_DELETE);
                    contact.resetOption(Contact.Options.PREEMPTIVE_GRANT);
                } else {
                    contact.setOption(Contact.Options.IN_ROSTER);
                    contact.resetOption(Contact.Options.DIRTY_PUSH);
                    contact.parseSubscriptionFromElement(item);
                }
                boolean both = contact.getOption(Contact.Options.TO) && contact.getOption(Contact.Options.FROM);
                if ((both != bothPre) && both) {
                    AxolotlService axolotlService = account.getAxolotlService();
                    if (axolotlService != null) {
                        axolotlService.clearErrorsInFetchStatusMap(contact.getJid());
                    }
                }
                mXmppConnectionService.getAvatarService().clear(contact);
            }
        }
        mXmppConnectionService.updateConversationUi();
        mXmppConnectionService.updateRosterUi();
        mXmppConnectionService.getShortcutService().refresh();
        mXmppConnectionService.syncRoster(account);
    }

    public static String avatarData(final Iq packet) {
        final Element pubsub = packet.findChild("pubsub", Namespace.PUBSUB);
        if (pubsub == null) {
            return null;
        }
        final Element items = pubsub.findChild("items");
        if (items == null) {
            return null;
        }
        return AbstractParser.avatarDataFromItems(items);
    }

    public static Element getItem(final Iq packet) {
        final Element pubsub = packet.findChild("pubsub", Namespace.PUBSUB);
        if (pubsub == null) {
            return null;
        }
        final Element items = pubsub.findChild("items");
        if (items == null) {
            return null;
        }
        return items.findChild("item");
    }

    @NonNull
    public static Set<Integer> deviceIds(final Element item) {
        return deviceIdsWithLabels(item).keySet();
    }

    @NonNull
    public static Map<Integer, String> deviceIdsWithLabels(final Element item) {
        Map<Integer, String> deviceMap = new HashMap<>();
        if (item != null) {
            final Element list = item.findChild("list");
            if (list != null) {
                for (Element device : list.getChildren()) {
                    if (!device.getName().equals("device")) {
                        continue;
                    }
                    try {
                        Integer id = Integer.valueOf(device.getAttribute("id"));
                        String label = device.getAttribute("label");
                        deviceMap.put(id, label);
                    } catch (NumberFormatException e) {
                        Log.e(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Encountered invalid <device> node in PEP (" + e.getMessage() + "):" + device.toString() + ", skipping...");
                    }
                }
            }
        }
        return deviceMap;
    }

    private static Integer signedPreKeyId(final Element bundle) {
        final Element signedPreKeyPublic = bundle.findChild("signedPreKeyPublic");
        if (signedPreKeyPublic == null) {
            return null;
        }
        try {
            return Integer.valueOf(signedPreKeyPublic.getAttribute("signedPreKeyId"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ECPublicKey signedPreKeyPublic(final Element bundle) {
        ECPublicKey publicKey = null;
        final String signedPreKeyPublic = bundle.findChildContent("signedPreKeyPublic");
        if (signedPreKeyPublic == null) {
            return null;
        }
        try {
            publicKey = Curve.decodePoint(base64decode(signedPreKeyPublic), 0);
        } catch (final IllegalArgumentException | InvalidKeyException e) {
            Log.e(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Invalid signedPreKeyPublic in PEP: " + e.getMessage());
        }
        return publicKey;
    }

    private static byte[] signedPreKeySignature(final Element bundle) {
        final String signedPreKeySignature = bundle.findChildContent("signedPreKeySignature");
        if (signedPreKeySignature == null) {
            return null;
        }
        try {
            return base64decode(signedPreKeySignature);
        } catch (final IllegalArgumentException e) {
            Log.e(Config.LOGTAG, AxolotlService.LOGPREFIX + " : Invalid base64 in signedPreKeySignature");
            return null;
        }
    }

    private static IdentityKey identityKey(final Element bundle) {
        final String identityKey = bundle.findChildContent("identityKey");
        if (identityKey == null) {
            return null;
        }
        try {
            return new IdentityKey(base64decode(identityKey), 0);
        } catch (final IllegalArgumentException | InvalidKeyException e) {
            Log.e(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Invalid identityKey in PEP: " + e.getMessage());
            return null;
        }
    }

    public static Map<Integer, ECPublicKey> preKeyPublics(final Iq packet) {
        Map<Integer, ECPublicKey> preKeyRecords = new HashMap<>();
        Element item = getItem(packet);
        if (item == null) {
            return null;
        }
        final Element bundleElement = item.findChild("bundle");
        if (bundleElement == null) {
            return null;
        }
        final Element prekeysElement = bundleElement.findChild("prekeys");
        if (prekeysElement == null) {
            return null;
        }
        for (Element preKeyPublicElement : prekeysElement.getChildren()) {
            if (!preKeyPublicElement.getName().equals("preKeyPublic")) {
                continue;
            }
            final String preKey = preKeyPublicElement.getContent();
            if (preKey == null) {
                continue;
            }
            Integer preKeyId = null;
            try {
                preKeyId = Integer.valueOf(preKeyPublicElement.getAttribute("preKeyId"));
                final ECPublicKey preKeyPublic = Curve.decodePoint(base64decode(preKey), 0);
                preKeyRecords.put(preKeyId, preKeyPublic);
            } catch (NumberFormatException e) {
                Log.e(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "could not parse preKeyId from preKey " + preKeyPublicElement.toString());
            } catch (Throwable e) {
                Log.e(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Invalid preKeyPublic (ID=" + preKeyId + ") in PEP: " + e.getMessage() + ", skipping...");
            }
        }
        return preKeyRecords;
    }

    private static byte[] base64decode(String input) {
        return BaseEncoding.base64().decode(CharMatcher.whitespace().removeFrom(input));
    }

    public static Pair<X509Certificate[], byte[]> verification(final Iq packet) {
        Element item = getItem(packet);
        Element verification = item != null ? item.findChild("verification", AxolotlService.PEP_PREFIX) : null;
        Element chain = verification != null ? verification.findChild("chain") : null;
        String signature = verification != null ? verification.findChildContent("signature") : null;
        if (chain != null && signature != null) {
            List<Element> certElements = chain.getChildren();
            X509Certificate[] certificates = new X509Certificate[certElements.size()];
            try {
                CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                int i = 0;
                for (final Element certElement : certElements) {
                    final String cert = certElement.getContent();
                    if (cert == null) {
                        continue;
                    }
                    certificates[i] = (X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(BaseEncoding.base64().decode(cert)));
                    ++i;
                }
                return new Pair<>(certificates, BaseEncoding.base64().decode(signature));
            } catch (CertificateException e) {
                return null;
            }
        } else {
            return null;
        }
    }

    @Nullable
    public static String bundleDeviceLabel(final Iq packet) {
        final Element bundleItem = getItem(packet);
        if (bundleItem == null) return null;
        final Element bundleElement = bundleItem.findChild("bundle");
        if (bundleElement == null) return null;
        return bundleElement.getAttribute("label");
    }

    public static PreKeyBundle bundle(final Iq bundle) {
        final Element bundleItem = getItem(bundle);
        if (bundleItem == null) {
            return null;
        }
        final Element bundleElement = bundleItem.findChild("bundle");
        if (bundleElement == null) {
            return null;
        }
        final ECPublicKey signedPreKeyPublic = signedPreKeyPublic(bundleElement);
        final Integer signedPreKeyId = signedPreKeyId(bundleElement);
        final byte[] signedPreKeySignature = signedPreKeySignature(bundleElement);
        final IdentityKey identityKey = identityKey(bundleElement);
        if (signedPreKeyId == null
                || signedPreKeyPublic == null
                || identityKey == null
                || signedPreKeySignature == null
                || signedPreKeySignature.length == 0) {
            return null;
        }
        return new PreKeyBundle(0, 0, PreKeyBundle.NULL_PRE_KEY_ID, null,
                signedPreKeyId, signedPreKeyPublic, signedPreKeySignature, identityKey);
    }

    @Override
    public void accept(final Iq packet) {
        final boolean isGet = packet.getType() == Iq.Type.GET;
        if (packet.getType() == Iq.Type.ERROR || packet.getType() == Iq.Type.TIMEOUT) {
            return;
        }
        if (packet.hasChild("query", Namespace.ROSTER) && packet.fromServer(account)) {
            final Element query = packet.findChild("query");
            if (packet.getType() == Iq.Type.RESULT && !query.getChildren().isEmpty()) {
                account.getRoster().markAllAsNotInRoster();
            }
            this.rosterItems(account, query);
            if (packet.getType() == Iq.Type.SET) {
                // Acknowledge roster push per RFC 6121 §2.1.6
                mXmppConnectionService.sendIqPacket(account, packet.generateResponse(Iq.Type.RESULT), null);
                // SRG changes may also update MUC memberships — re-fetch member lists
                mXmppConnectionService.refreshPrivateMucMemberLists(account);
            }
        } else if ((packet.hasChild("block", Namespace.BLOCKING) || packet.hasChild("blocklist", Namespace.BLOCKING)) &&
                packet.fromServer(account)) {
            // Block list or block push.
            final Element blocklist = packet.findChild("blocklist", Namespace.BLOCKING);
            final Element block = packet.findChild("block", Namespace.BLOCKING);
            final Collection<Element> items = blocklist != null ? blocklist.getChildren() :
                    (block != null ? block.getChildren() : null);
            // If this is a response to a blocklist query, clear the block list and replace with the new one.
            // Otherwise, just update the existing blocklist.
            if (packet.getType() == Iq.Type.RESULT) {
                account.clearBlocklist();
                account.getXmppConnection().getFeatures().setBlockListRequested(true);
            }
            if (items != null) {
                final Collection<Jid> jids = new ArrayList<>(items.size());
                // Create a collection of Jids from the packet
                for (final Element item : items) {
                    if (item.getName().equals("item")) {
                        final Jid jid = InvalidJid.getNullForInvalid(item.getAttributeAsJid("jid"));
                        if (jid != null) {
                            jids.add(jid);
                        }
                    }
                }
                account.addToBlocklist(jids);
                if (packet.getType() == Iq.Type.SET) {
                    boolean removed = false;
                    for (Jid jid : jids) {
                        removed |= mXmppConnectionService.removeBlockedConversations(account, jid);
                    }
                    if (removed) {
                        mXmppConnectionService.updateConversationUi();
                    }
                }
            }
            // Update the UI
            mXmppConnectionService.updateBlocklistUi();
            if (packet.getType() == Iq.Type.SET) {
                final Iq response = packet.generateResponse(Iq.Type.RESULT);
                mXmppConnectionService.sendIqPacket(account, response, null);
            }
        } else if (packet.hasChild("unblock", Namespace.BLOCKING) &&
                packet.fromServer(account) && packet.getType() == Iq.Type.SET) {
            final Collection<Element> items = packet.findChild("unblock", Namespace.BLOCKING).getChildren();
            if (items.isEmpty()) {
                // No children to unblock == unblock all
                account.clearBlocklist();
            } else {
                final Collection<Jid> jids = new ArrayList<>(items.size());
                for (final Element item : items) {
                    if (item.getName().equals("item")) {
                        final Jid jid = InvalidJid.getNullForInvalid(item.getAttributeAsJid("jid"));
                        if (jid != null) {
                            jids.add(jid);
                        }
                    }
                }
                account.removeFromBlocklist(jids);
            }
            mXmppConnectionService.updateBlocklistUi();
            final Iq response = packet.generateResponse(Iq.Type.RESULT);
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("open", "http://jabber.org/protocol/ibb")
                || packet.hasChild("data", "http://jabber.org/protocol/ibb")
                || packet.hasChild("close", "http://jabber.org/protocol/ibb")) {
            mXmppConnectionService.getJingleConnectionManager()
                    .deliverIbbPacket(account, packet);
        } else if (packet.hasChild("query", "http://jabber.org/protocol/disco#info")) {
            final Iq response = mXmppConnectionService.getIqGenerator().discoResponse(account, packet);
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("query", "jabber:iq:version") && isGet) {
            final Iq response = mXmppConnectionService.getIqGenerator().versionResponse(packet);
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("ping", "urn:xmpp:ping") && isGet) {
            final Iq response = packet.generateResponse(Iq.Type.RESULT);
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("time", "urn:xmpp:time") && isGet) {
            final Iq response = mXmppConnectionService.getIqGenerator().entityTimeResponse(packet);
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("push", Namespace.UNIFIED_PUSH) && packet.getType() == Iq.Type.SET) {
            final Jid transport = packet.getFrom();
            final Element push = packet.findChild("push", Namespace.UNIFIED_PUSH);
            final boolean success =
                    push != null
                            && mXmppConnectionService.processUnifiedPushMessage(
                                    account, transport, push);
            final Iq response;
            if (success) {
                response = packet.generateResponse(Iq.Type.RESULT);
            } else {
                response = packet.generateResponse(Iq.Type.ERROR);
                final Element error = response.addChild("error");
                error.setAttribute("type", "cancel");
                error.setAttribute("code", "404");
                error.addChild("item-not-found", "urn:ietf:params:xml:ns:xmpp-stanzas");
            }
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else {
            if (packet.getType() == Iq.Type.GET || packet.getType() == Iq.Type.SET) {
                final Iq response = packet.generateResponse(Iq.Type.ERROR);
                final Element error = response.addChild("error");
                error.setAttribute("type", "cancel");
                error.addChild("feature-not-implemented", "urn:ietf:params:xml:ns:xmpp-stanzas");
                account.getXmppConnection().sendIqPacket(response, null);
            }
        }
    }

}

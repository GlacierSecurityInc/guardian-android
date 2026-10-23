package com.glaciersecurity.glaciermessenger.parser;

import com.glaciersecurity.glaciermessenger.utils.Log;
import android.text.TextUtils;
import android.util.Pair;

import com.google.common.collect.ImmutableSet;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.AxolotlService;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.BrokenSessionException;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.NotEncryptedForThisDeviceException;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.OutdatedSenderException;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.XmppAxolotlMessage;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Bookmark;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Conversational;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.entities.MucOptions;
import com.glaciersecurity.glaciermessenger.entities.Reaction;
import com.glaciersecurity.glaciermessenger.entities.ReadByMarker;
import com.glaciersecurity.glaciermessenger.entities.ReceiptRequest;
import com.glaciersecurity.glaciermessenger.entities.RtpSessionStatus;
import com.glaciersecurity.glaciermessenger.http.HttpConnectionManager;
import com.glaciersecurity.glaciermessenger.services.MessageArchiveService;
import com.glaciersecurity.glaciermessenger.services.QuickConversationsService;import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.utils.CryptoHelper;
import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.LocalizedContent;
import com.glaciersecurity.glaciermessenger.xml.Namespace;
import com.glaciersecurity.glaciermessenger.xmpp.InvalidJid;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.chatstate.ChatState;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleConnectionManager;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleRtpConnection;
import com.glaciersecurity.glaciermessenger.xmpp.pep.Avatar;
import im.conversations.android.xmpp.model.Extension;
import im.conversations.android.xmpp.model.carbons.Received;
import im.conversations.android.xmpp.model.carbons.Sent;
import im.conversations.android.xmpp.model.forward.Forwarded;
import im.conversations.android.xmpp.model.occupant.OccupantId;
import im.conversations.android.xmpp.model.reactions.Reactions;

public class MessageParser extends AbstractParser implements Consumer<im.conversations.android.xmpp.model.stanza.Message> {

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss", Locale.ENGLISH);

    private static final List<String> JINGLE_MESSAGE_ELEMENT_NAMES =
            Arrays.asList("accept", "propose", "proceed", "reject", "retract", "ringing", "finish");

    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s");

    public MessageParser(final XmppConnectionService service, final Account account) {
        super(service, account);
    }

    // True when every line of a message body is, on its own, a plausible http(s)
    // upload url -- the shape some G2G senders use to bundle several photos into
    // one message body instead of (or in addition to) multiple <x oob> elements.
    private static boolean isMultiUrlList(final String body) {
        final String[] lines = body.split("\n");
        if (lines.length < 2) {
            return false;
        }
        for (final String line : lines) {
            if (line.isEmpty() || WHITESPACE.matcher(line).find()) {
                return false;
            }
            final java.net.URI uri;
            try {
                uri = new java.net.URI(line);
            } catch (final java.net.URISyntaxException e) {
                return false;
            }
            final String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
        }
        return true;
    }

    private static String extractStanzaId(Element packet, boolean isTypeGroupChat, Conversation conversation) {
        final Jid by;
        final boolean safeToExtract;
        if (isTypeGroupChat) {
            by = conversation.getJid().asBareJid();
            safeToExtract = conversation.getMucOptions().hasFeature(Namespace.STANZA_IDS);
        } else {
            Account account = conversation.getAccount();
            by = account.getJid().asBareJid();
            safeToExtract = account.getXmppConnection().getFeatures().stanzaIds();
        }
        return safeToExtract ? extractStanzaId(packet, by) : null;
    }

    private static String extractStanzaId(Account account, Element packet) {
        final boolean safeToExtract = account.getXmppConnection().getFeatures().stanzaIds();
        return safeToExtract ? extractStanzaId(packet, account.getJid().asBareJid()) : null;
    }

    private static String extractStanzaId(Element packet, Jid by) {
        for (Element child : packet.getChildren()) {
            if (child.getName().equals("stanza-id")
                    && Namespace.STANZA_IDS.equals(child.getNamespace())
                    && by.equals(InvalidJid.getNullForInvalid(child.getAttributeAsJid("by")))) {
                return child.getAttribute("id");
            }
        }
        return null;
    }

    private static Jid getTrueCounterpart(Element mucUserElement, Jid fallback) {
        final Element item = mucUserElement == null ? null : mucUserElement.findChild("item");
        Jid result = item == null ? null : InvalidJid.getNullForInvalid(item.getAttributeAsJid("jid"));
        return result != null ? result : fallback;
    }

    private boolean extractChatState(Conversation c, final boolean isTypeGroupChat, final im.conversations.android.xmpp.model.stanza.Message packet) {
        ChatState state = ChatState.parse(packet);
        if (state != null && c != null) {
            final Account account = c.getAccount();
            final Jid from = packet.getFrom();
            if (from.asBareJid().equals(account.getJid().asBareJid())) {
                c.setOutgoingChatState(state);
                if (state == ChatState.ACTIVE || state == ChatState.COMPOSING) {
                    if (c.getContact().isSelf()) {
                        return false;
                    }
                    mXmppConnectionService.markRead(c);
                    activateGracePeriod(account);
                }
                return false;
            } else {
                if (isTypeGroupChat) {
                    MucOptions.User user = c.getMucOptions().findUserByFullJid(from);
                    if (user != null) {
                        return user.setChatState(state);
                    } else {
                        return false;
                    }
                } else {
                    return c.setIncomingChatState(state);
                }
            }
        }
        return false;
    }

    private Message parseAxolotlChat(Element axolotlMessage, Jid from, Conversation conversation, int status, final boolean checkedForDuplicates, boolean postpone) {
        final AxolotlService service = conversation.getAccount().getAxolotlService();
        final XmppAxolotlMessage xmppAxolotlMessage;
        try {
            xmppAxolotlMessage = XmppAxolotlMessage.fromElement(axolotlMessage, from.asBareJid());
        } catch (Exception e) {
            return null;
        }
        if (xmppAxolotlMessage.hasPayload()) {
            final XmppAxolotlMessage.XmppAxolotlPlaintextMessage plaintextMessage;
            try {
                plaintextMessage = service.processReceivingPayloadMessage(xmppAxolotlMessage, postpone);
            } catch (BrokenSessionException e) {
                if (checkedForDuplicates) {
                    if (service.trustedOrPreviouslyResponded(from.asBareJid())) {
                        service.reportBrokenSessionException(e, postpone);
                        return new Message(conversation, "", Message.ENCRYPTION_AXOLOTL_FAILED, status);
                    } else {
                        return new Message(conversation, "", Message.ENCRYPTION_AXOLOTL_FAILED, status);
                    }
                } else {
                    return null;
                }
            } catch (NotEncryptedForThisDeviceException e) {
                return new Message(conversation, "", Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE, status);
            } catch (OutdatedSenderException e) {
                return new Message(conversation, "", Message.ENCRYPTION_AXOLOTL_FAILED, status);
            }
            if (plaintextMessage != null) {
                Message finishedMessage = new Message(conversation, plaintextMessage.getPlaintext(), Message.ENCRYPTION_AXOLOTL, status);
                finishedMessage.setFingerprint(plaintextMessage.getFingerprint());
                return finishedMessage;
            }
        } else {
            service.processReceivingKeyTransportMessage(xmppAxolotlMessage, postpone);
        }
        return null;
    }

    private Invite extractInvite(final Element message) {
        final Element originId = message.findChild("origin-id", Namespace.STANZA_IDS);
        final String inviteRemoteId =
                originId != null && originId.getAttribute("id") != null
                        ? originId.getAttribute("id")
                        : extractStanzaId(message);
        final Element mucUser = message.findChild("x", Namespace.MUC_USER);
        if (mucUser != null) {
            final Element invite = mucUser.findChild("invite");
            if (invite != null) {
                final String password = mucUser.findChildContent("password");
                final Jid from = InvalidJid.getNullForInvalid(invite.getAttributeAsJid("from"));
                final Jid to = InvalidJid.getNullForInvalid(invite.getAttributeAsJid("to"));
                if (to != null && from == null) {
                    return null;
                }
                final Jid room = InvalidJid.getNullForInvalid(message.getAttributeAsJid("from"));
                if (room == null) {
                    return null;
                }
                return new Invite(room, password, false, from, inviteRemoteId);
            }
        }
        final Element conference = message.findChild("x", "jabber:x:conference");
        if (conference != null) {
            Jid from = InvalidJid.getNullForInvalid(message.getAttributeAsJid("from"));
            Jid room = InvalidJid.getNullForInvalid(conference.getAttributeAsJid("jid"));
            if (room == null) {
                return null;
            }
            return new Invite(room, conference.getAttribute("password"), true, from, inviteRemoteId);
        }
        return null;
    }

    private String extractStanzaId(final Element message) {
        final Element stanzaId = message.findChild("stanza-id", Namespace.STANZA_IDS);
        if (stanzaId != null && stanzaId.getAttribute("id") != null) {
            return stanzaId.getAttribute("id");
        }
        return message.getAttribute("id");
    }

    private void parseEvent(final Element event, final Jid from, final Account account) {
        final Element items = event.findChild("items");
        final String node = items == null ? null : items.getAttribute("node");
        if ("urn:xmpp:avatar:metadata".equals(node)) {
            Avatar avatar = Avatar.parseMetadata(items);
            if (avatar != null) {
                avatar.owner = from.asBareJid();
                if (mXmppConnectionService.getFileBackend().isAvatarCached(avatar)) {
                    if (account.getJid().asBareJid().equals(from)) {
                        if (account.setAvatar(avatar.getFilename())) {
                            mXmppConnectionService.databaseBackend.updateAccount(account);
                            mXmppConnectionService.notifyAccountAvatarHasChanged(account);
                        }
                        mXmppConnectionService.getAvatarService().clear(account);
                        mXmppConnectionService.updateConversationUi();
                        mXmppConnectionService.updateAccountUi();
                    } else {
                        final Contact contact = account.getRoster().getContact(from);
                        contact.setAvatar(avatar);
                        mXmppConnectionService.syncRoster(account);
                        mXmppConnectionService.getAvatarService().clear(contact);
                        mXmppConnectionService.updateConversationUi();
                        mXmppConnectionService.updateRosterUi();
                    }
                } else if (mXmppConnectionService.isDataSaverDisabled()) {
                    mXmppConnectionService.fetchAvatar(account, avatar);
                }
            }
        } else if (Namespace.NICK.equals(node)) {
            final Element i = items.findChild("item");
            final String nick = i == null ? null : i.findChildContent("nick", Namespace.NICK);
            if (nick != null) {
                setNick(account, from, nick);
            }
        } else if (AxolotlService.PEP_DEVICE_LIST.equals(node)) {
            Element item = items.findChild("item");
            final Map<Integer, String> deviceIdsWithLabels = IqParser.deviceIdsWithLabels(item);
            final AxolotlService axolotlService = account.getAxolotlService();
            axolotlService.registerDevices(from, deviceIdsWithLabels);
        } else if (Namespace.BOOKMARKS.equals(node) && account.getJid().asBareJid().equals(from)) {
            final var connection = account.getXmppConnection();
            if (connection.getFeatures().bookmarks2()) {
                // Only a change signal: Bookmarks 2 stays the source of truth, and re-reading it
                // runs the deleted-bookmark diff that archives a group left elsewhere.
                mXmppConnectionService.fetchBookmarks2(account);
            } else if (connection.getFeatures().bookmarksConversion()) {
                final Element i = items.findChild("item");
                final Element storage =
                        i == null ? null : i.findChild("storage", Namespace.BOOKMARKS);
                final Map<Jid, Bookmark> bookmarks = Bookmark.parseFromStorage(storage, account);
                mXmppConnectionService.processBookmarksInitial(account, bookmarks, true);
            }
        } else if (Namespace.BOOKMARKS2.equals(node) && account.getJid().asBareJid().equals(from)) {
            final Element item = items.findChild("item");
            final Element retract = items.findChild("retract");
            if (item != null) {
                final Bookmark bookmark = Bookmark.parseFromItem(item, account);
                if (bookmark != null) {
                    account.putBookmark(bookmark);
                    mXmppConnectionService.processModifiedBookmark(bookmark);
                    mXmppConnectionService.updateConversationUi();
                }
            }
            if (retract != null) {
                final Jid id = InvalidJid.getNullForInvalid(retract.getAttributeAsJid("id"));
                if (id != null) {
                    account.removeBookmark(id);
                    mXmppConnectionService.processDeletedBookmark(account, id);
                    mXmppConnectionService.updateConversationUi();
                }
            }
        } else if (Config.MESSAGE_DISPLAYED_SYNCHRONIZATION
                && Namespace.MDS_DISPLAYED.equals(node)
                && account.getJid().asBareJid().equals(from)) {
            final Element item = items.findChild("item");
            mXmppConnectionService.processMdsItem(account, item);
        }
    }

    private void parseDeleteEvent(final Element event, final Jid from, final Account account) {
        final Element delete = event.findChild("delete");
        final String node = delete == null ? null : delete.getAttribute("node");
        if (Namespace.NICK.equals(node)) {
            setNick(account, from, null);
        } else if (Namespace.BOOKMARKS2.equals(node) && account.getJid().asBareJid().equals(from)) {
            deleteAllBookmarks(account);
        } else if (Namespace.AVATAR_METADATA.equals(node) && account.getJid().asBareJid().equals(from)) {
        }
    }

    private void parsePurgeEvent(final Element event, final Jid from, final Account account) {
        final Element purge = event.findChild("purge");
        final String node = purge == null ? null : purge.getAttribute("node");
        if (Namespace.BOOKMARKS2.equals(node) && account.getJid().asBareJid().equals(from)) {
            deleteAllBookmarks(account);
        }
    }

    private void deleteAllBookmarks(final Account account) {
        final var previous = account.getBookmarkedJids();
        account.setBookmarks(Collections.emptyMap());
        mXmppConnectionService.processDeletedBookmarks(account, previous);
    }

    private void setNick(Account account, Jid user, String nick) {
        if (user.asBareJid().equals(account.getJid().asBareJid())) {
            account.setDisplayName(nick);
            if (QuickConversationsService.isQuicksy()) {
                mXmppConnectionService.getAvatarService().clear(account);
            }
        } else {
            Contact contact = account.getRoster().getContact(user);
            if (contact.setPresenceName(nick)) {
                mXmppConnectionService.syncRoster(account);
                mXmppConnectionService.getAvatarService().clear(contact);
            }
        }
        mXmppConnectionService.updateConversationUi();
        mXmppConnectionService.updateAccountUi();
    }

    private boolean handleErrorMessage(final Account account, final im.conversations.android.xmpp.model.stanza.Message packet) {
        if (packet.getType() == im.conversations.android.xmpp.model.stanza.Message.Type.ERROR) {
            if (packet.fromServer(account)) {
                final var forwarded = getForwardedMessagePacket(packet,"received", Namespace.CARBONS);
                if (forwarded != null) {
                    return handleErrorMessage(account, forwarded.first);
                }
            }
            final Jid from = packet.getFrom();
            final String id = packet.getId();
            if (from != null && id != null) {
                if (id.startsWith(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX)) {
                    final String sessionId = id.substring(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX.length());
                    mXmppConnectionService.getJingleConnectionManager()
                            .updateProposedSessionDiscovered(account, from, sessionId, JingleConnectionManager.DeviceDiscoveryState.FAILED);
                    return true;
                }
                if (id.startsWith(JingleRtpConnection.JINGLE_MESSAGE_PROCEED_ID_PREFIX)) {
                    final String sessionId = id.substring(JingleRtpConnection.JINGLE_MESSAGE_PROCEED_ID_PREFIX.length());
                    final String message = extractErrorMessage(packet);
                    mXmppConnectionService.getJingleConnectionManager().failProceed(account, from, sessionId, message);
                    return true;
                }
                mXmppConnectionService.markMessage(account,
                        from.asBareJid(),
                        id,
                        Message.STATUS_SEND_FAILED,
                        extractErrorMessage(packet));
                final Element error = packet.findChild("error");
                final boolean pingWorthyError = error != null && (error.hasChild("not-acceptable") || error.hasChild("remote-server-timeout") || error.hasChild("remote-server-not-found"));
                if (pingWorthyError) {
                    Conversation conversation = mXmppConnectionService.find(account, from);
                    if (conversation != null && conversation.getMode() == Conversational.MODE_MULTI) {
                        if (conversation.getMucOptions().online()) {
                            mXmppConnectionService.mucSelfPingAndRejoin(conversation);
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void accept(final im.conversations.android.xmpp.model.stanza.Message original) {
        if (handleErrorMessage(account, original)) {
            return;
        }
        final im.conversations.android.xmpp.model.stanza.Message packet;
        Long timestamp = null;
        boolean isCarbon = false;
        String serverMsgId = null;
        final Element fin = original.findChild("fin", MessageArchiveService.Version.MAM_0.namespace);
        if (fin != null) {
            mXmppConnectionService.getMessageArchiveService().processFinLegacy(fin, original.getFrom());
            return;
        }
        final Element result = MessageArchiveService.Version.findResult(original);
        final String queryId = result == null ? null : result.getAttribute("queryid");
        final MessageArchiveService.Query query = queryId == null ? null : mXmppConnectionService.getMessageArchiveService().findQuery(queryId);
        final boolean offlineMessagesRetrieved = account.getXmppConnection().isOfflineMessagesRetrieved();
        if (query != null && query.validFrom(original.getFrom())) {
            final var f = getForwardedMessagePacket(original,"result", query.version.namespace);
            if (f == null) {
                return;
            }
            timestamp = f.second;
            packet = f.first;
            serverMsgId = result.getAttribute("id");
            query.incrementMessageCount();
            if (handleErrorMessage(account, packet)) {
                return;
            }
        } else if (query != null) {
            return;
        } else if (original.fromServer(account)
                && original.getType() != im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT) {
            Pair<im.conversations.android.xmpp.model.stanza.Message, Long> f;
            f = getForwardedMessagePacket(original, Received.class);
            f = f == null ? getForwardedMessagePacket(original, Sent.class) : f;
            packet = f != null ? f.first : original;
            if (handleErrorMessage(account, packet)) {
                return;
            }
            timestamp = f != null ? f.second : null;
            isCarbon = f != null;
        } else {
            packet = original;
        }

        if (timestamp == null) {
            timestamp = AbstractParser.parseTimestamp(original, AbstractParser.parseTimestamp(packet));
        }
        final Reactions reactions = packet.getExtension(Reactions.class);
        final LocalizedContent body = packet.getBody();
        final Element mucUserElement = packet.findChild("x", Namespace.MUC_USER);
        final boolean isTypeGroupChat = packet.getType() == im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT;
        final String pgpEncrypted = packet.findChildContent("x", "jabber:x:encrypted");
        final Element replaceElement = packet.findChild("replace", "urn:xmpp:message-correct:0");
        final Element timerElement = packet.findChild("x", "jabber:x:msgexpire");
        // iPhone/other G2G clients bundling a multi-photo send have been observed
        // using either several sibling <x xmlns='jabber:x:oob'> elements, or a
        // single <x> wrapping several <url> children -- findChild/findChildContent
        // only ever returned the first url found, silently dropping the rest.
        // Enumerate every <url> under every matching <x> so nothing is missed
        // regardless of which shape the sender used.
        final List<Element> oobElements = packet.findChildren("x", Namespace.OOB);
        final List<String> oobUrls = new ArrayList<>();
        for (final Element oobElement : oobElements) {
            for (final Element child : oobElement.getChildren()) {
                if ("url".equals(child.getName())) {
                    final String url = child.getContent();
                    if (url != null && !oobUrls.contains(url)) {
                        oobUrls.add(url);
                    }
                }
            }
        }
        // A third shape has also been observed from iOS: rather than (or in addition
        // to) multiple <x oob> elements, every uploaded photo's url is put on its
        // own line of the plain <body>, with at most one of them mirrored into a
        // single <x oob><url>. Because the body then has more than one line, the
        // existing single-url-body handling below never recognized it as an image
        // at all -- it would have rendered as a multi-line text message instead of
        // silently keeping just one photo, but either way every image past the
        // first was lost. Detect that shape and fold its urls in too.
        // Require at least one real <x oob> element to already be present so an
        // ordinary text message that happens to be a few pasted links (no oob
        // signal at all) isn't misread as a batch of image attachments.
        final boolean bodyIsMultiUrlList = !oobUrls.isEmpty() && body != null && body.content != null && isMultiUrlList(body.content);
        if (bodyIsMultiUrlList) {
            final String[] bodyUrls = body.content.split("\n");
            // Body order reflects the sender's actual attachment order; put those
            // first and only append any oob-only extra as a leftover.
            final List<String> merged = new ArrayList<>();
            for (final String url : bodyUrls) {
                if (!merged.contains(url)) {
                    merged.add(url);
                }
            }
            for (final String url : oobUrls) {
                if (!merged.contains(url)) {
                    merged.add(url);
                }
            }
            oobUrls.clear();
            oobUrls.addAll(merged);
        }
        if (oobUrls.size() > 1) {
        }
        final String oobUrl = oobUrls.isEmpty() ? null : oobUrls.get(0);
        final List<String> additionalOobUrls = oobUrls.size() > 1 ? oobUrls.subList(1, oobUrls.size()) : Collections.emptyList();
        final String replacementId = replaceElement == null ? null : replaceElement.getAttribute("id");
        final Element axolotlEncrypted = packet.findChildEnsureSingle(XmppAxolotlMessage.CONTAINERTAG, AxolotlService.PEP_PREFIX);
        int status;
        final Jid counterpart;
        final Jid to = packet.getTo();
        final Jid from = packet.getFrom();
        final Element originId = packet.findChild("origin-id", Namespace.STANZA_IDS);
        final String remoteMsgId;
        if (originId != null && originId.getAttribute("id") != null) {
            remoteMsgId = originId.getAttribute("id");
        } else {
            remoteMsgId = packet.getId();
        }
        boolean notify = false;

        if (from == null || !InvalidJid.isValid(from) || !InvalidJid.isValid(to)) {
            Log.e(Config.LOGTAG, "encountered invalid message from='" + Jid.obfuscateForLog(from) + "' to='" + Jid.obfuscateForLog(to) + "'");
            return;
        }
        if (query != null && !query.muc() && isTypeGroupChat) {
            Log.e(Config.LOGTAG, Jid.obfuscateForLog(account.getJid().asBareJid()) + ": received groupchat (" + Jid.obfuscateForLog(from) + ") message on regular MAM request. skipping");
            return;
        }
        final Jid mucTrueCounterPart;
        final OccupantId occupant;
        if (isTypeGroupChat) {
            final Conversation conversation =
                    mXmppConnectionService.find(account, from.asBareJid());
            final Jid mucTrueCounterPartByPresence;
            if (conversation != null) {
                final var mucOptions = conversation.getMucOptions();
                occupant = mucOptions.occupantId() ? packet.getExtension(OccupantId.class) : null;
                final var user =
                        occupant == null ? null : mucOptions.findUserByOccupantId(occupant.getId());
                mucTrueCounterPartByPresence = user == null ? null : user.getRealJid();
            } else {
                occupant = null;
                mucTrueCounterPartByPresence = null;
            }
            mucTrueCounterPart =
                    getTrueCounterpart(
                            (query != null && query.safeToExtractTrueCounterpart())
                                    ? mucUserElement
                                    : null,
                            mucTrueCounterPartByPresence);
        } else {
            mucTrueCounterPart = null;
            occupant = null;
        }
        boolean isMucStatusMessage = InvalidJid.hasValidFrom(packet) && from.isBareJid() && mucUserElement != null && mucUserElement.hasChild("status");
        boolean selfAddressed;
        if (packet.fromAccount(account)) {
            status = Message.STATUS_SEND;
            selfAddressed = to == null || account.getJid().asBareJid().equals(to.asBareJid());
            if (selfAddressed) {
                counterpart = from;
            } else {
                counterpart = to;
            }
        } else {
             status = Message.STATUS_RECEIVED;
            counterpart = from;
            selfAddressed = false;
        }

        final Invite invite = extractInvite(packet);
        if (invite != null) {
            if (invite.jid.asBareJid().equals(account.getJid().asBareJid())) {
            } else if (isTypeGroupChat) {
            } else if (invite.direct && (mucUserElement != null || invite.inviter == null || mXmppConnectionService.isMuc(account, invite.inviter))) {
            } else {
                invite.execute(account);
                return;
            }
        }

        if (reactions == null && (body != null || pgpEncrypted != null || (axolotlEncrypted != null && axolotlEncrypted.hasChild("payload")) || oobUrl != null) && !isMucStatusMessage) {
            final boolean conversationIsProbablyMuc = isTypeGroupChat || mucUserElement != null || account.getXmppConnection().getMucServersWithholdAccount().contains(counterpart.getDomain().toEscapedString());
            final Conversation conversation = mXmppConnectionService.findOrCreateConversation(account, counterpart.asBareJid(), conversationIsProbablyMuc, false, query, false);
            final boolean conversationMultiMode = conversation.getMode() == Conversation.MODE_MULTI;

            if (serverMsgId == null) {
                serverMsgId = extractStanzaId(packet, isTypeGroupChat, conversation);
            }


            if (selfAddressed) {
                if (mXmppConnectionService.markMessage(conversation, remoteMsgId, Message.STATUS_SEND_RECEIVED, serverMsgId)) {
                    return;
                }
                status = Message.STATUS_RECEIVED;
                if (remoteMsgId != null && conversation.findMessageWithRemoteId(remoteMsgId, counterpart) != null) {
                    return;
                }
            }

            if (isTypeGroupChat) {
                if (conversation.getMucOptions().isSelf(counterpart)) {
                    status = Message.STATUS_SEND_RECEIVED;
                    isCarbon = true; //not really carbon but received from another resource
                    if (mXmppConnectionService.markMessage(conversation, remoteMsgId, status, serverMsgId, body)) {
                        return;
                    } else if (remoteMsgId == null || Config.IGNORE_ID_REWRITE_IN_MUC) {
                        if (body != null) {
                            Message message = conversation.findSentMessageWithBody(body.content);
                            if (message != null) {
                                mXmppConnectionService.markMessage(message, status);
                                return;
                            }
                        }
                    }
                } else {
                    status = Message.STATUS_RECEIVED;
                }
            }
            final Message message;
            if (pgpEncrypted != null && Config.supportOpenPgp()) {
                message = new Message(conversation, pgpEncrypted, Message.ENCRYPTION_PGP, status);
            } else if (axolotlEncrypted != null && Config.supportOmemo()) {
                Jid origin;
                Set<Jid> fallbacksBySourceId = Collections.emptySet();
                if (conversationMultiMode) {
                    final Jid fallback = conversation.getMucOptions().getTrueCounterpart(counterpart);
                    origin = getTrueCounterpart(query != null ? mucUserElement : null, fallback);
                    if (origin == null) {
                        try {
                            fallbacksBySourceId = account.getAxolotlService().findCounterpartsBySourceId(XmppAxolotlMessage.parseSourceId(axolotlEncrypted));
                        } catch (IllegalArgumentException e) {
                            //ignoring
                        }
                    }
                    if (origin == null && fallbacksBySourceId.size() == 0) {
                        return;
                    }
                } else {
                    fallbacksBySourceId = Collections.emptySet();
                    origin = from;
                }

                final boolean liveMessage = query == null && !isTypeGroupChat && mucUserElement == null;
                final boolean checkedForDuplicates = liveMessage || (serverMsgId != null && remoteMsgId != null && !conversation.possibleDuplicate(serverMsgId, remoteMsgId));

                if (origin != null) {
                    message = parseAxolotlChat(axolotlEncrypted, origin, conversation, status, checkedForDuplicates, query != null);
                } else {
                    Message trial = null;
                    for (Jid fallback : fallbacksBySourceId) {
                        trial = parseAxolotlChat(axolotlEncrypted, fallback, conversation, status, checkedForDuplicates && fallbacksBySourceId.size() == 1, query != null);
                        if (trial != null) {
                            origin = fallback;
                            break;
                        }
                    }
                    message = trial;
                }
                if (message == null) {
                    if (query == null && extractChatState(mXmppConnectionService.find(account, counterpart.asBareJid()), isTypeGroupChat, packet)) {
                        mXmppConnectionService.updateConversationUi();
                    }
                    if (query != null && status == Message.STATUS_SEND && remoteMsgId != null) {
                        Message previouslySent = conversation.findSentMessageWithUuid(remoteMsgId);
                        if (previouslySent != null && previouslySent.getServerMsgId() == null && serverMsgId != null) {
                            previouslySent.setServerMsgId(serverMsgId);
                            mXmppConnectionService.databaseBackend.updateMessage(previouslySent, false);
                        }
                    }
                    return;
                }
                if (conversationMultiMode) {
                    message.setTrueCounterpart(origin);
                }
            } else if ((body == null || bodyIsMultiUrlList) && oobUrl != null) {
                message = new Message(conversation, oobUrl, Message.ENCRYPTION_NONE, status);
                message.setOob(true);
                if (CryptoHelper.isPgpEncryptedUrl(oobUrl)) {
                    message.setEncryption(Message.ENCRYPTION_DECRYPTED);
                }
            } else if (body == null) {
                // Reachable, and neither a crash nor a blank message is the right answer.
                // Config.supportOpenPgp() returns a hardcoded false in this build, so a stanza
                // carrying only legacy OpenPGP ciphertext passes the outer gate on
                // pgpEncrypted, skips the branch that would have handled it, and arrives here
                // with nothing to show. Substituting an empty body would persist a blank
                // message, mark it unread and notify on it as though it were plaintext the
                // peer had actually sent.
                return;
            } else {
                message = new Message(conversation, body.content, Message.ENCRYPTION_NONE, status);
                if (body.count > 1) {
                    message.setBodyLanguage(body.language);
                }
            }

            message.setCounterpart(counterpart);
            message.setRemoteMsgId(remoteMsgId);

            if (timerElement != null && timerElement.getAttribute("seconds") != null) {
                String timerStr = timerElement.getAttribute("seconds");
                try {
                    int timer = Integer.parseInt(timerStr);
                    message.setTimer(timer);
                    if (conversation.getTimer() != timer) {
                        conversation.setTimer(timer);
                        setTimerStatus(conversation, timerStr);
                    }
                } catch(NumberFormatException nfe) {
                    message.setTimer(Message.TIMER_NONE);
                }
            } else {
                if (conversation.getTimer() != Message.TIMER_NONE) { //AM#9
                    message.setTimer(Message.TIMER_NONE);
                    conversation.setTimer(Message.TIMER_NONE);
                    setTimerStatus(conversation, null);
                }
            }

            message.setServerMsgId(serverMsgId);
            message.setCarbon(isCarbon);
            message.setTime(timestamp);
            if (body != null && body.content != null && body.content.equals(oobUrl)) {
                message.setOob(true);
                if (CryptoHelper.isPgpEncryptedUrl(oobUrl)) {
                    message.setEncryption(Message.ENCRYPTION_DECRYPTED);
                }
            }
            message.markable = packet.hasChild("markable", "urn:xmpp:chat-markers:0");
            if (conversationMultiMode) {
                final var mucOptions = conversation.getMucOptions();
                if (occupant != null) {
                    message.setOccupantId(occupant.getId());
                }
                message.setMucUser(mucOptions.findUserByFullJid(counterpart));
                final Jid fallback = mucOptions.getTrueCounterpart(counterpart);
                Jid trueCounterpart;
                if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL) {
                    trueCounterpart = message.getTrueCounterpart();
                } else if (query != null && query.safeToExtractTrueCounterpart()) {
                    trueCounterpart = getTrueCounterpart(mucUserElement, fallback);
                } else {
                    trueCounterpart = fallback;
                }
                if (trueCounterpart != null && isTypeGroupChat) {
                    if (trueCounterpart.asBareJid().equals(account.getJid().asBareJid())) {
                        status = isTypeGroupChat ? Message.STATUS_SEND_RECEIVED : Message.STATUS_SEND;
                    } else {
                        status = Message.STATUS_RECEIVED;
                        message.setCarbon(false);
                    }
                }
                message.setStatus(status);
                message.setTrueCounterpart(trueCounterpart);
                if (!isTypeGroupChat) {
                    message.setType(Message.TYPE_PRIVATE);
                } else if (isLeaveGroupNotification(message.getBody())) {
                    // Render "<name> left the group" notices as a centered system
                    // message instead of a normal chat bubble.
                    message.setType(Message.TYPE_STATUS);
                }
            } else {
                updateLastseen(account, from);
            }

            if (replacementId != null && mXmppConnectionService.allowMessageCorrection()) {
                final Message replacedMessage = conversation.findMessageWithRemoteIdAndCounterpart(replacementId,
                        counterpart,
                        message.getStatus() == Message.STATUS_RECEIVED,
                        message.isCarbon());
                if (replacedMessage != null) {
                    final boolean fingerprintsMatch = replacedMessage.getFingerprint() == null
                            || replacedMessage.getFingerprint().equals(message.getFingerprint());
                    final boolean trueCountersMatch = replacedMessage.getTrueCounterpart() != null
                            && message.getTrueCounterpart() != null
                            && replacedMessage.getTrueCounterpart().asBareJid().equals(message.getTrueCounterpart().asBareJid());
                    final boolean occupantIdMatch =
                            replacedMessage.getOccupantId() != null
                                    && replacedMessage
                                            .getOccupantId()
                                            .equals(message.getOccupantId());
                    final boolean mucUserMatches = query == null && replacedMessage.sameMucUser(message); //can not be checked when using mam
                    final boolean duplicate = conversation.hasDuplicateMessage(message);
                    if (fingerprintsMatch && (trueCountersMatch || occupantIdMatch || !conversationMultiMode || mucUserMatches) && !duplicate) {
                        synchronized (replacedMessage) {
                            final String uuid = replacedMessage.getUuid();
                            replacedMessage.setUuid(UUID.randomUUID().toString());
                            replacedMessage.setBody(message.getBody());
                            replacedMessage.putEdited(replacedMessage.getRemoteMsgId(), replacedMessage.getServerMsgId());
                            replacedMessage.setRemoteMsgId(remoteMsgId);
                            if (replacedMessage.getServerMsgId() == null || message.getServerMsgId() != null) {
                                replacedMessage.setServerMsgId(message.getServerMsgId());
                            }
                            replacedMessage.setEncryption(message.getEncryption());
                            replacedMessage.setTimer(message.getTimer());
                            replacedMessage.setEndTime(message.getEndTime());
                            if (replacedMessage.getStatus() == Message.STATUS_RECEIVED) {
                                replacedMessage.markUnread();
                            }
                            extractChatState(mXmppConnectionService.find(account, counterpart.asBareJid()), isTypeGroupChat, packet);
                            mXmppConnectionService.updateMessage(replacedMessage, uuid);
                            if (mXmppConnectionService.confirmMessages()
                                    && replacedMessage.getStatus() == Message.STATUS_RECEIVED
                                    && (replacedMessage.trusted() || replacedMessage.isPrivateMessage())
                                    && remoteMsgId != null
                                    && !selfAddressed
                                    && !isTypeGroupChat) {
                                processMessageReceipts(account, packet, remoteMsgId, query);
                            }
                            if (replacedMessage.getEncryption() == Message.ENCRYPTION_PGP) {
                                conversation.getAccount().getPgpDecryptionService().discard(replacedMessage);
                                conversation.getAccount().getPgpDecryptionService().decrypt(replacedMessage, false);
                            }
                        }
                        mXmppConnectionService.getNotificationService().updateNotification();
                        return;
                    }
                }
            }

            long deletionDate = mXmppConnectionService.getAutomaticMessageDeletionDate();
            if (deletionDate != 0 && message.getTimeSent() < deletionDate) {
                return;
            }

            boolean checkForDuplicates = (isTypeGroupChat && packet.hasChild("delay", "urn:xmpp:delay"))
                    || message.isPrivateMessage()
                    || message.getServerMsgId() != null
                    || (query == null && mXmppConnectionService.getMessageArchiveService().isCatchupInProgress(conversation));
            if (checkForDuplicates) {
                final Message duplicate = conversation.findDuplicateMessage(message);
                if (duplicate != null) {
                    final boolean serverMsgIdUpdated;
                    if (duplicate.getStatus() != Message.STATUS_RECEIVED
                            && duplicate.getUuid().equals(message.getRemoteMsgId())
                            && duplicate.getServerMsgId() == null
                            && message.getServerMsgId() != null) {
                        duplicate.setServerMsgId(message.getServerMsgId());
                        if (mXmppConnectionService.databaseBackend.updateMessage(duplicate, false)) {
                            serverMsgIdUpdated = true;
                        } else {
                            serverMsgIdUpdated = false;
                            Log.e(Config.LOGTAG, "failed to update message");
                        }
                    } else {
                        serverMsgIdUpdated = false;
                    }
                    return;
                }
            }

            if (query != null && query.getPagingOrder() == MessageArchiveService.PagingOrder.REVERSE) {
                conversation.prepend(query.getActualInThisQuery(), message);
            } else {
                conversation.add(message);
            }
            if (query != null) {
                query.incrementActualMessageCount();
            }

            if (query == null || query.isCatchup()) { //either no mam or catchup
                if (status == Message.STATUS_SEND || status == Message.STATUS_SEND_RECEIVED) {
                    mXmppConnectionService.markRead(conversation);
                    if (query == null) {
                        activateGracePeriod(account);
                    }
                } else if (message.getType() == Message.TYPE_STATUS) {
                    // System notices such as "<name> left the group" should appear
                    // inline without raising a notification or unread badge.
                } else {
                    message.markUnread();
                    notify = true;
                }
            }

            if (message.getEncryption() == Message.ENCRYPTION_PGP) {
                notify = conversation.getAccount().getPgpDecryptionService().decrypt(message, notify);
            } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE || message.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED) {
                notify = false;
            }

            if (query == null) {
                extractChatState(mXmppConnectionService.find(account, counterpart.asBareJid()), isTypeGroupChat, packet);
                mXmppConnectionService.updateConversationUi();
            }

            if (mXmppConnectionService.confirmMessages()
                    && message.getStatus() == Message.STATUS_RECEIVED
                    && (message.trusted() || message.isPrivateMessage())
                    && remoteMsgId != null
                    && !selfAddressed
                    && !isTypeGroupChat) {
                processMessageReceipts(account, packet, remoteMsgId, query);
            }

            mXmppConnectionService.databaseBackend.createMessage(message);
            final HttpConnectionManager manager = this.mXmppConnectionService.getHttpConnectionManager();
            if (message.trusted() && message.treatAsDownloadable() && manager.getAutoAcceptFileSize() > 0) {
                manager.createNewDownloadConnection(message);
            } else if (notify) {
                if (query != null && query.isCatchup()) {
                    mXmppConnectionService.getNotificationService().pushFromBacklog(message);
                } else {
                    mXmppConnectionService.getNotificationService().push(message);
                }
            }

            if (oobUrl != null && (body == null || body.content == null || body.content.equals(oobUrl) || bodyIsMultiUrlList) && !additionalOobUrls.isEmpty()) {
                // The sender bundled several photos into this one stanza; give
                // each one its own Message row (mirroring how a single-image
                // stanza is handled above) instead of dropping all but the first.
                long extraTimestamp = timestamp;
                int extraIndex = 1;
                for (final String extraUrl : additionalOobUrls) {
                    extraTimestamp += 1;
                    // A problem with one image (bad url, db hiccup, etc.) must not
                    // abort the loop and silently drop every image after it.
                    try {
                        final Message extraMessage = new Message(conversation, extraUrl, Message.ENCRYPTION_NONE, message.getStatus());
                        extraMessage.setOob(true);
                        if (CryptoHelper.isPgpEncryptedUrl(extraUrl)) {
                            extraMessage.setEncryption(Message.ENCRYPTION_DECRYPTED);
                        }
                        extraMessage.setCounterpart(counterpart);
                        extraMessage.setRemoteMsgId(remoteMsgId == null ? null : remoteMsgId + "#img" + extraIndex);
                        extraMessage.setServerMsgId(serverMsgId == null ? null : serverMsgId + "#img" + extraIndex);
                        extraMessage.setCarbon(message.isCarbon());
                        extraMessage.setTime(extraTimestamp);
                        extraMessage.markable = message.markable;
                        extraMessage.setType(message.getType());
                        if (conversationMultiMode) {
                            extraMessage.setOccupantId(message.getOccupantId());
                            extraMessage.setMucUser(conversation.getMucOptions().findUserByFullJid(counterpart));
                            extraMessage.setTrueCounterpart(message.getTrueCounterpart());
                        }

                        if (conversation.hasDuplicateMessage(extraMessage)) {
                            extraIndex++;
                            continue;
                        }

                        if (query != null && query.getPagingOrder() == MessageArchiveService.PagingOrder.REVERSE) {
                            conversation.prepend(query.getActualInThisQuery(), extraMessage);
                        } else {
                            conversation.add(extraMessage);
                        }
                        if (query != null) {
                            query.incrementActualMessageCount();
                        }

                        boolean extraNotify = false;
                        if ((query == null || query.isCatchup())
                                && extraMessage.getStatus() != Message.STATUS_SEND
                                && extraMessage.getStatus() != Message.STATUS_SEND_RECEIVED) {
                            extraMessage.markUnread();
                            extraNotify = true;
                        }

                        mXmppConnectionService.databaseBackend.createMessage(extraMessage);
                        if (extraMessage.trusted() && extraMessage.treatAsDownloadable() && manager.getAutoAcceptFileSize() > 0) {
                            manager.createNewDownloadConnection(extraMessage);
                        } else if (extraNotify) {
                            if (query != null && query.isCatchup()) {
                                mXmppConnectionService.getNotificationService().pushFromBacklog(extraMessage);
                            } else {
                                mXmppConnectionService.getNotificationService().push(extraMessage);
                            }
                        }
                    } catch (final Exception e) {
                        Log.e(Config.LOGTAG, "failed to insert image " + (extraIndex + 1) + " of multi-image stanza", e);
                    }
                    extraIndex++;
                }
            }
        } else if (!packet.hasChild("body")) { //no body

            final Conversation conversation = mXmppConnectionService.find(account, from.asBareJid());
            if (axolotlEncrypted != null) {
                Jid origin;
                if (conversation != null && conversation.getMode() == Conversation.MODE_MULTI) {
                    final Jid fallback = conversation.getMucOptions().getTrueCounterpart(counterpart);
                    origin = getTrueCounterpart(query != null ? mucUserElement : null, fallback);
                    if (origin == null) {
                        return;
                    }
                } else if (isTypeGroupChat) {
                    return;
                } else {
                    origin = from;
                }
                try {
                    final XmppAxolotlMessage xmppAxolotlMessage = XmppAxolotlMessage.fromElement(axolotlEncrypted, origin.asBareJid());
                    account.getAxolotlService().processReceivingKeyTransportMessage(xmppAxolotlMessage, query != null);
                } catch (Exception e) {
                    return;
                }
            }

            if (query == null && extractChatState(mXmppConnectionService.find(account, counterpart.asBareJid()), isTypeGroupChat, packet)) {
                mXmppConnectionService.updateConversationUi();
            }

            if (isTypeGroupChat) {
                if (packet.hasChild("subject") && !packet.hasChild("thread")) { // We already know it has no body per above
                    if (conversation != null && conversation.getMode() == Conversation.MODE_MULTI) {
                        conversation.setHasMessagesLeftOnServer(conversation.countMessages() > 0);
                        final LocalizedContent subject = packet.findInternationalizedChildContentInDefaultNamespace("subject");
                        if (subject != null && conversation.getMucOptions().setSubject(subject.content)) {
                            mXmppConnectionService.updateConversation(conversation);
                        }
                        mXmppConnectionService.updateConversationUi();
                        return;
                    }
                }
            }
            if (conversation != null && mucUserElement != null && InvalidJid.hasValidFrom(packet) && from.isBareJid()) {
                for (Element child : mucUserElement.getChildren()) {
                    if ("status".equals(child.getName())) {
                        try {
                            int code = Integer.parseInt(child.getAttribute("code"));
                            if ((code >= 170 && code <= 174) || (code >= 102 && code <= 104)) {
                                mXmppConnectionService.fetchConferenceConfiguration(conversation);
                                break;
                            }
                        } catch (Exception e) {
                            //ignored
                        }
                    } else if ("item".equals(child.getName())) {
                        MucOptions.User user = AbstractParser.parseItem(conversation, child);
                        if (!user.realJidMatchesAccount()) {
                            boolean isNew = conversation.getMucOptions().updateUser(user);
                            mXmppConnectionService.getAvatarService().clear(conversation);
                            mXmppConnectionService.updateMucRosterUi();
                            mXmppConnectionService.updateConversationUi();
                            Contact contact = user.getContact();
                            if (!user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER)) {
                                Jid jid = user.getRealJid();
                                List<Jid> cryptoTargets = conversation.getAcceptedCryptoTargets();
                                if (cryptoTargets.remove(user.getRealJid())) {
                                    conversation.setAcceptedCryptoTargets(cryptoTargets);
                                    mXmppConnectionService.updateConversation(conversation);
                                }
                            } else if (isNew
                                    && user.getRealJid() != null
                                    && conversation.getMucOptions().isPrivateAndNonAnonymous()
                                    && (contact == null || !contact.mutualPresenceSubscription())
                                    && account.getAxolotlService().hasEmptyDeviceList(user.getRealJid())) {
                                account.getAxolotlService().fetchDeviceIds(user.getRealJid());
                            }
                        }
                    }
                }
            }
            if (!isTypeGroupChat) {
                for (Element child : packet.getChildren()) {
                    if (Namespace.JINGLE_MESSAGE.equals(child.getNamespace()) && JINGLE_MESSAGE_ELEMENT_NAMES.contains(child.getName())) {
                        final String action = child.getName();
                        final String sessionId = child.getAttribute("id");
                        if (sessionId == null) {
                            break;
                        }
                        if (query == null && offlineMessagesRetrieved) {
                            if (serverMsgId == null) {
                                serverMsgId = extractStanzaId(account, packet);
                            }
                            mXmppConnectionService
                                    .getJingleConnectionManager()
                                    .deliverMessage(
                                            account,
                                            packet.getTo(),
                                            packet.getFrom(),
                                            child,
                                            remoteMsgId,
                                            serverMsgId,
                                            timestamp);
                            final Contact contact = account.getRoster().getContact(from);
                            // this is the same condition that is found in JingleRtpConnection for
                            // the 'ringing' response. Responding with delivery receipts predates
                            // the 'ringing' spec'd
                            final boolean sendReceipts =
                                    (mXmppConnectionService.confirmMessages()
                                                    && contact.showInContactList())
                                            || Config.JINGLE_MESSAGE_INIT_STRICT_OFFLINE_CHECK;
                            if (remoteMsgId != null && !contact.isSelf() && sendReceipts) {
                                processMessageReceipts(account, packet, remoteMsgId, null);
                            }
                        } else if ((query != null && query.isCatchup()) || !offlineMessagesRetrieved) {
                            if ("propose".equals(action)) {
                                final Element description = child.findChild("description");
                                final String namespace =
                                        description == null ? null : description.getNamespace();
                                if (Namespace.JINGLE_APPS_RTP.equals(namespace)) {
                                    final Conversation c =
                                            mXmppConnectionService.findOrCreateConversation(
                                                    account, counterpart.asBareJid(), false, false);
                                    final Message preExistingMessage =
                                            c.findRtpSession(sessionId, status);
                                    if (preExistingMessage != null) {
                                        preExistingMessage.setServerMsgId(serverMsgId);
                                        mXmppConnectionService.updateMessage(preExistingMessage);
                                        break;
                                    }
                                    final Message message =
                                            new Message(
                                                    c, status, Message.TYPE_RTP_SESSION, sessionId);
                                    message.setServerMsgId(serverMsgId);
                                    message.setTime(timestamp);
                                    message.setBody(new RtpSessionStatus(false, 0).toString());
                                    c.add(message);
                                    mXmppConnectionService.databaseBackend.createMessage(message);
                                }
                            } else if ("proceed".equals(action)) {
                                // status needs to be flipped to find the original propose
                                final Conversation c =
                                        mXmppConnectionService.findOrCreateConversation(
                                                account, counterpart.asBareJid(), false, false);
                                final int s =
                                        packet.fromAccount(account)
                                                ? Message.STATUS_RECEIVED
                                                : Message.STATUS_SEND;
                                final Message message = c.findRtpSession(sessionId, s);
                                if (message != null) {
                                    message.setBody(new RtpSessionStatus(true, 0).toString());
                                    if (serverMsgId != null) {
                                        message.setServerMsgId(serverMsgId);
                                    }
                                    message.setTime(timestamp);
                                    mXmppConnectionService.updateMessage(message, true);
                                }

                            } else if ("finish".equals(action)) {
                            }
                        } else {
                            //MAM reloads (non catchups
                            if ("propose".equals(action)) {
                                final Element description = child.findChild("description");
                                final String namespace = description == null ? null : description.getNamespace();
                                if (Namespace.JINGLE_APPS_RTP.equals(namespace)) {
                                    final Conversation c = mXmppConnectionService.findOrCreateConversation(account, counterpart.asBareJid(), false, false);
                                    final Message preExistingMessage = c.findRtpSession(sessionId, status);
                                    if (preExistingMessage != null) {
                                        preExistingMessage.setServerMsgId(serverMsgId);
                                        mXmppConnectionService.updateMessage(preExistingMessage);
                                        break;
                                    }
                                    final Message message = new Message(
                                            c,
                                            status,
                                            Message.TYPE_RTP_SESSION,
                                            sessionId
                                    );
                                    message.setServerMsgId(serverMsgId);
                                    message.setTime(timestamp);
                                    message.setBody(new RtpSessionStatus(true, 0).toString());
                                    if (query.getPagingOrder() == MessageArchiveService.PagingOrder.REVERSE) {
                                        c.prepend(query.getActualInThisQuery(), message);
                                    } else {
                                        c.add(message);
                                    }
                                    query.incrementActualMessageCount();
                                    mXmppConnectionService.databaseBackend.createMessage(message);
                                }
                            }
                        }
                        break;
                    }
                }
            }
        }

        Element received = packet.findChild("received", "urn:xmpp:chat-markers:0");
        if (received == null) {
            received = packet.findChild("received", "urn:xmpp:receipts");
        }
        if (received != null) {
            String id = received.getAttribute("id");
            if (packet.fromAccount(account)) {
                if (query != null && id != null && packet.getTo() != null) {
                    query.removePendingReceiptRequest(new ReceiptRequest(packet.getTo(), id));
                }
            } else if (id != null) {
                if (id.startsWith(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX)) {
                    final String sessionId = id.substring(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX.length());
                    mXmppConnectionService.getJingleConnectionManager()
                            .updateProposedSessionDiscovered(account, from, sessionId, JingleConnectionManager.DeviceDiscoveryState.DISCOVERED);
                } else {
                    mXmppConnectionService.markMessage(account, from.asBareJid(), id, Message.STATUS_SEND_RECEIVED);
                }
            }
        }
        final Element displayed = packet.findChild("displayed", "urn:xmpp:chat-markers:0");
        if (displayed != null) {
            final String id = displayed.getAttribute("id");
            final Jid sender = InvalidJid.getNullForInvalid(displayed.getAttributeAsJid("sender"));
            if (packet.fromAccount(account) && !selfAddressed) {
                final Conversation c =
                        mXmppConnectionService.find(account, counterpart.asBareJid());
                final Message message =
                        (c == null || id == null) ? null : c.findReceivedWithRemoteId(id);
                if (message != null && (query == null || query.isCatchup())) {
                    mXmppConnectionService.markReadUpTo(c, message);
                }
                if (query == null) {
                    activateGracePeriod(account);
                }
            } else if (isTypeGroupChat) {
                final Conversation conversation = mXmppConnectionService.find(account, counterpart.asBareJid());
                final Message message;
                if (conversation != null && id != null) {
                    if (sender != null) {
                        message = conversation.findMessageWithRemoteId(id, sender);
                    } else {
                        message = conversation.findMessageWithServerMsgId(id);
                    }
                } else {
                    message = null;
                }
                if (message != null) {
                    final Jid fallback = conversation.getMucOptions().getTrueCounterpart(counterpart);
                    final Jid trueJid = getTrueCounterpart((query != null && query.safeToExtractTrueCounterpart()) ? mucUserElement : null, fallback);
                    final boolean trueJidMatchesAccount = account.getJid().asBareJid().equals(trueJid == null ? null : trueJid.asBareJid());
                    if (trueJidMatchesAccount || conversation.getMucOptions().isSelf(counterpart)) {
                        if (!message.isRead() && (query == null || query.isCatchup())) { //checking if message is unread fixes race conditions with reflections
                            mXmppConnectionService.markReadUpTo(conversation, message);
                        }
                    } else if (!counterpart.isBareJid() && trueJid != null) {
                        final ReadByMarker readByMarker = ReadByMarker.from(counterpart, trueJid);
                        if (message.addReadByMarker(readByMarker)) {
                            final var mucOptions = conversation.getMucOptions();
                            final var everyone = ImmutableSet.copyOf(mucOptions.getMembers(false));
                            final var readyBy = message.getReadyByTrue();
                            final var mStatus = message.getStatus();
                            if (mucOptions.isPrivateAndNonAnonymous()
                                    && (mStatus == Message.STATUS_SEND_RECEIVED
                                            || mStatus == Message.STATUS_SEND)
                                    && readyBy.containsAll(everyone)) {
                                message.setStatus(Message.STATUS_SEND_DISPLAYED);
                            }
                            mXmppConnectionService.updateMessage(message, false);
                        }
                    }
                }
            } else {
                final Message displayedMessage = mXmppConnectionService.markMessage(account, from.asBareJid(), id, Message.STATUS_SEND_DISPLAYED);
                if (displayedMessage != null) {
                    Message message = displayedMessage.prev();
                    while (message != null
                            && message.getStatus() == Message.STATUS_SEND_RECEIVED
                            && message.getTimeSent() < displayedMessage.getTimeSent()) {
                        mXmppConnectionService.markMessage(message, Message.STATUS_SEND_DISPLAYED);
                        message = message.prev();
                    }
                    if (selfAddressed) {
                        dismissNotification(account, counterpart, query, id);
                    }
                }
            }
        }

        if (reactions != null) {
            final Collection<String> reactionValues = reactions.getReactions();
            final String reactingTo = reactions.getId();
            final Conversation conversation =
                    mXmppConnectionService.find(account, counterpart.asBareJid());

            if (conversation != null && reactingTo != null) {
                if (isTypeGroupChat && conversation.getMode() == Conversational.MODE_MULTI) {
                    final var mucOptions = conversation.getMucOptions();
                    final var occupantId = occupant == null ? null : occupant.getId();
                    final boolean hasOccupantId = !TextUtils.isEmpty(occupantId);
                    final boolean isReceived =
                            hasOccupantId
                                    ? !mucOptions.isSelf(occupantId)
                                    : (counterpart == null || !mucOptions.isSelf(counterpart));
                    final LinkedHashSet<String> newRemoteReactions = new LinkedHashSet<>();
                    final Message message = findMessageForReaction(conversation, reactingTo);
                    if (message != null) {
                        if (isReceived) {
                            final Set<String> previousReactionsFromSender = new HashSet<>();
                            for (final Reaction existingReaction : message.getReactions()) {
                                if (!existingReaction.received) {
                                    continue;
                                }
                                if (hasOccupantId) {
                                    if (occupantId != null
                                            && occupantId.equals(existingReaction.occupantId)) {
                                        previousReactionsFromSender.add(existingReaction.reaction);
                                    }
                                } else if (existingReaction.from != null
                                        && counterpart != null
                                        && counterpart.equals(existingReaction.from)) {
                                    previousReactionsFromSender.add(existingReaction.reaction);
                                }
                            }
                            for (final String reactionValue : reactionValues) {
                                if (!previousReactionsFromSender.contains(reactionValue)) {
                                    newRemoteReactions.add(reactionValue);
                                }
                            }
                        }
                        final Collection<Reaction> combinedReactions;
                        if (hasOccupantId) {
                            combinedReactions =
                                    Reaction.withOccupantId(
                                            message.getReactions(),
                                            reactionValues,
                                            isReceived,
                                            counterpart,
                                            mucTrueCounterPart,
                                            occupantId);
                        } else {
                            combinedReactions =
                                    Reaction.withFrom(
                                            message.getReactions(),
                                            reactionValues,
                                            isReceived,
                                            counterpart,
                                            false);
                        }
                        message.setReactions(combinedReactions);
                        mXmppConnectionService.updateMessage(message, false);
                        if (isReceived
                                && !newRemoteReactions.isEmpty()
                                && (message.getStatus() != Message.STATUS_RECEIVED
                                        || message.isCarbon())) {
                            mXmppConnectionService
                                    .getNotificationService()
                                    .pushReactionNotification(
                                            message,
                                            newRemoteReactions,
                                            counterpart,
                                            mucTrueCounterPart,
                                            hasOccupantId ? occupantId : null);
                        }
                    }
                } else if (conversation.getMode() == Conversational.MODE_SINGLE) {
                    final Message message = findMessageForReaction(conversation, reactingTo);
                    final boolean isReceived;
                    final Jid reactionFrom;
                    if (packet.fromAccount(account)) {
                        isReceived = false;
                        reactionFrom = account.getJid().asBareJid();
                    } else {
                        isReceived = true;
                        reactionFrom = counterpart;
                    }
                    packet.fromAccount(account);
                    if (message != null) {
                        final Set<String> previousReactionsFromCounterpart = new HashSet<>();
                        final LinkedHashSet<String> newRemoteReactions = new LinkedHashSet<>();
                        if (isReceived) {
                            for (final Reaction existingReaction : message.getReactions()) {
                                if (existingReaction.received
                                        && existingReaction.from != null
                                        && reactionFrom != null
                                        && reactionFrom
                                                .asBareJid()
                                                .equals(existingReaction.from.asBareJid())) {
                                    previousReactionsFromCounterpart.add(existingReaction.reaction);
                                }
                            }
                            for (final String reactionValue : reactionValues) {
                                if (!previousReactionsFromCounterpart.contains(reactionValue)) {
                                    newRemoteReactions.add(reactionValue);
                                }
                            }
                        }
                        final var combinedReactions =
                                Reaction.withFrom(
                                        message.getReactions(),
                                        reactionValues,
                                        isReceived,
                                        reactionFrom);
                        message.setReactions(combinedReactions);
                        mXmppConnectionService.updateMessage(message, false);
                        if (isReceived
                                && !newRemoteReactions.isEmpty()
                                && (message.getStatus() != Message.STATUS_RECEIVED
                                        || message.isCarbon())) {
                            mXmppConnectionService
                                    .getNotificationService()
                                    .pushReactionNotification(
                                            message,
                                            newRemoteReactions,
                                            reactionFrom,
                                            reactionFrom,
                                            null);
                        }
                    }
                }
            }
        }

        final Element event = original.findChild("event", "http://jabber.org/protocol/pubsub#event");
        if (event != null && InvalidJid.hasValidFrom(original) && original.getFrom().isBareJid()) {
            if (event.hasChild("items")) {
                parseEvent(event, original.getFrom(), account);
            } else if (event.hasChild("delete")) {
                parseDeleteEvent(event, original.getFrom(), account);
            } else if (event.hasChild("purge")) {
                parsePurgeEvent(event, original.getFrom(), account);
            }
        }

        final String nick = packet.findChildContent("nick", Namespace.NICK);
        if (nick != null && InvalidJid.hasValidFrom(original)) {
            if (mXmppConnectionService.isMuc(account, from)) {
                return;
            }
            final Contact contact = account.getRoster().getContact(from);
            if (contact.setPresenceName(nick)) {
                mXmppConnectionService.syncRoster(account);
                mXmppConnectionService.getAvatarService().clear(contact);
            }
        }
    }

    private static Pair<im.conversations.android.xmpp.model.stanza.Message,Long> getForwardedMessagePacket(final im.conversations.android.xmpp.model.stanza.Message original, Class<? extends Extension> clazz) {
        final var extension = original.getExtension(clazz);
        final var forwarded = extension == null ? null : extension.getExtension(Forwarded.class);
        if (forwarded == null) {
            return null;
        }
        final Long timestamp = AbstractParser.parseTimestamp(forwarded, null);
        final var forwardedMessage = forwarded.getMessage();
        if (forwardedMessage == null) {
            return null;
        }
        return new Pair<>(forwardedMessage,timestamp);
    }

    private static Pair<im.conversations.android.xmpp.model.stanza.Message,Long> getForwardedMessagePacket(final im.conversations.android.xmpp.model.stanza.Message original, final String name, final String namespace) {
        final Element wrapper = original.findChild(name, namespace);
        final var forwardedElement = wrapper == null ? null : wrapper.findChild("forwarded",Namespace.FORWARD);
        if (forwardedElement instanceof Forwarded forwarded) {
            final Long timestamp = AbstractParser.parseTimestamp(forwarded, null);
            final var forwardedMessage = forwarded.getMessage();
            if (forwardedMessage == null) {
                return null;
            }
            return new Pair<>(forwardedMessage,timestamp);
        }
        return null;
    }

    private void dismissNotification(Account account, Jid counterpart, MessageArchiveService.Query query, final String id) {
        final Conversation conversation = mXmppConnectionService.find(account, counterpart.asBareJid());
        if (conversation != null && (query == null || query.isCatchup())) {
            final String displayableId = conversation.findMostRecentRemoteDisplayableId();
            if (displayableId != null && displayableId.equals(id)) {
                mXmppConnectionService.markRead(conversation);
            } else {
                Log.w(Config.LOGTAG, Jid.obfuscateForLog(account.getJid().asBareJid()) + ": received dismissing display marker that did not match our last id in that conversation");
            }
        }
    }

    protected void setTimerStatus(Conversation conversation, String timerstring) {
        final String[] ctimers = mXmppConnectionService.getResources().getStringArray(R.array.timer_options_durations);
        final String[] ctimersStrs = mXmppConnectionService.getResources().getStringArray(R.array.timer_options_descriptions);
        int idx = -1;
        if (timerstring == null) {
            idx = 0;
        } else {
            for (int i = 0; i < ctimers.length; i++) {
                if (ctimers[i].equals(timerstring)) {
                    idx = i;
                }
            }
        }

        String tstatus = "";
        if (idx >= 0) {
            tstatus = ctimersStrs[idx];
        } else { return;}

        String timerStatus = "Disappearing message time set to " + tstatus;
        Message disMessageStatus = Message.createStatusMessage(conversation, timerStatus);
        disMessageStatus.setTime(System.currentTimeMillis());
        disMessageStatus.setEndTime(Long.MAX_VALUE);
        disMessageStatus.setTimer(Message.TIMER_NONE);
        mXmppConnectionService.databaseBackend.createMessage(disMessageStatus);
        conversation.add(disMessageStatus);
    }

    private void processMessageReceipts(final Account account, final im.conversations.android.xmpp.model.stanza.Message packet, final String remoteMsgId, MessageArchiveService.Query query) {
        final boolean markable = packet.hasChild("markable", "urn:xmpp:chat-markers:0");
        final boolean request = packet.hasChild("request", "urn:xmpp:receipts");
        if (query == null) {
            final ArrayList<String> receiptsNamespaces = new ArrayList<>();
            if (markable) {
                receiptsNamespaces.add("urn:xmpp:chat-markers:0");
            }
            if (request) {
                receiptsNamespaces.add("urn:xmpp:receipts");
            }
            if (receiptsNamespaces.size() > 0) {
                final var receipt = mXmppConnectionService.getMessageGenerator().received(account,
                        packet.getFrom(),
                        remoteMsgId,
                        receiptsNamespaces,
                        packet.getType());
                mXmppConnectionService.sendMessagePacket(account, receipt);
            }
        } else if (query.isCatchup()) {
            if (request) {
                query.addPendingReceiptRequest(new ReceiptRequest(packet.getFrom(), remoteMsgId));
            }
        }
    }

    /**
     * Detects messages produced when a participant leaves a group (see
     * {@code SettingsActivity#sendLeavingGroupMessage}, which sends
     * "&lt;display name&gt; {@code left_group}"). Such bodies are shown as inline system
     * messages rather than ordinary chat bubbles. Matching is suffix-based and tolerant of
     * locale and minor phrasing differences ("left the group", "left the group chat").
     */
    private boolean isLeaveGroupNotification(final String body) {
        if (body == null) {
            return false;
        }
        final String trimmed = body.trim().toLowerCase(Locale.US);
        if (trimmed.isEmpty()) {
            return false;
        }
        final String leftGroup =
                mXmppConnectionService.getString(R.string.left_group).toLowerCase(Locale.US);
        return trimmed.endsWith(" " + leftGroup)
                || trimmed.endsWith(" left the group")
                || trimmed.endsWith(" left the group chat");
    }

    private void activateGracePeriod(Account account) {
        long duration = mXmppConnectionService.getLongPreference("grace_period_length", R.integer.grace_period) * 1000;
        account.activateGracePeriod(duration);
    }

    private class Invite {
        final Jid jid;
        final String password;
        final boolean direct;
        final Jid inviter;
        final String remoteId;

        Invite(Jid jid, String password, boolean direct, Jid inviter, String remoteId) {
            this.jid = jid;
            this.password = password;
            this.direct = direct;
            this.inviter = inviter;
            this.remoteId = remoteId;
        }

        public boolean execute(final Account account) {
            if (this.jid == null) {
                return false;
            }
            final Contact contact =
                    this.inviter != null ? account.getRoster().getContact(this.inviter) : null;
            if (contact != null && contact.isBlocked()) {
                return false;
            }
            final AppSettings appSettings = new AppSettings(mXmppConnectionService);
            if ((contact != null && contact.showInContactList())
                    || appSettings.isAcceptInvitesFromStrangers()) {
                final Conversation conversation =
                        mXmppConnectionService.findOrCreateConversation(account, jid, true, false);
                if (!TextUtils.isEmpty(remoteId)) {
                    final Message duplicate = conversation.findMessageWithServerMsgId(remoteId);
                    if (duplicate != null) {
                        return true;
                    }
                }
                final boolean conversationOnline = conversation.getMucOptions().online();
                if (conversationOnline) {
                    mXmppConnectionService.mucSelfPingAndRejoin(conversation);
                } else {
                    conversation.getMucOptions().setPassword(password);
                    mXmppConnectionService.databaseBackend.updateConversation(conversation);
                    mXmppConnectionService.joinMuc(
                            conversation, contact != null && contact.showInContactList());

                    final boolean inviteOnly = conversation.getMucOptions().membersOnly();
                    final String conversationName = resolveGroupInviteDisplayName(conversation);

                    String inviterName = null;
                    if (contact != null) {
                        inviterName = contact.getDisplayName();
                    } else if (this.inviter != null) {
                        inviterName = this.inviter.asBareJid().toEscapedString();
                    }
                    final boolean hasInviterName = inviterName != null && !inviterName.isEmpty();
                    final String statusBody;
                    if (inviteOnly) {
                        statusBody =
                                hasInviterName
                                        ? mXmppConnectionService.getString(
                                        R.string.group_invite_notification_with_inviter,
                                        conversationName,
                                        inviterName)
                                        : mXmppConnectionService.getString(
                                        R.string.group_invite_notification_no_inviter,
                                        conversationName);
                    } else {
                        statusBody =
                                hasInviterName
                                        ? mXmppConnectionService.getString(
                                                R.string.group_invite_notification_with_inviter,
                                                conversationName,
                                                inviterName)
                                        : mXmppConnectionService.getString(
                                                R.string.group_invite_notification_no_inviter,
                                                conversationName);
                    }


                    final Message statusMessage =
                            Message.createStatusMessage(conversation, statusBody);
                    statusMessage.setUuid(UUID.randomUUID().toString());
                    statusMessage.setTime(System.currentTimeMillis());
                    statusMessage.markUnread();
                    if (!TextUtils.isEmpty(remoteId)) {
                        statusMessage.setServerMsgId(remoteId);
                        statusMessage.setRemoteMsgId(remoteId);
                    }
                    mXmppConnectionService.databaseBackend.createMessage(statusMessage);
                    conversation.add(statusMessage);

                    final Message notificationMessage =
                            Message.createStatusMessage(conversation, statusBody);
                    notificationMessage.setUuid(UUID.randomUUID().toString());
                    notificationMessage.setTime(statusMessage.getTimeSent());
                    notificationMessage.markUnread();
                    notificationMessage.setType(Message.TYPE_PRIVATE);
                    if (!TextUtils.isEmpty(remoteId)) {
                        notificationMessage.setServerMsgId(remoteId);
                        notificationMessage.setRemoteMsgId(remoteId);
                    }
                    mXmppConnectionService.getNotificationService().push(notificationMessage);

                    mXmppConnectionService.updateConversationUi();
                }
                return true;
            } else {
                return false;
            }
        }
        }


    private Message findMessageForReaction(
            final Conversation conversation, final String messageId) {
        if (conversation == null || messageId == null) {
            return null;
        }
        Message message = conversation.findMessageWithServerMsgId(messageId);
        if (message != null) {
            return message;
        }
        message =
                mXmppConnectionService.databaseBackend.getMessageWithServerMsgId(
                        conversation, messageId);
        if (message != null) {
            return message;
        }
        message = conversation.findMessageWithUuidOrRemoteId(messageId);
        if (message != null) {
            return message;
        }
        return mXmppConnectionService.databaseBackend.getMessageWithUuidOrRemoteId(
                conversation, messageId);
    }

    private String resolveGroupInviteDisplayName(final Conversation conversation) {
        final MucOptions mucOptions = conversation.getMucOptions();
        String groupName = mucOptions.getName();
        if (TextUtils.isEmpty(groupName)) {
            groupName = mucOptions.getSubject();
        }
        if (TextUtils.isEmpty(groupName)) {
            final Bookmark bookmark = conversation.getBookmark();
            groupName = bookmark == null ? null : bookmark.getBookmarkName();
        }
        if (TextUtils.isEmpty(groupName) && conversation.getJid() != null) {
            groupName = conversation.getJid().getEscapedLocal();
        }
        if (TextUtils.isEmpty(groupName) && conversation.getJid() != null) {
            groupName = conversation.getJid().toEscapedString();
        }
        return groupName;
    }
}

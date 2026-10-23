package com.glaciersecurity.glaciermessenger.xmpp.jingle;

import java.util.Map;

import com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas.Group;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas.IceUdpTransportInfo;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas.OmemoVerifiedIceUdpTransportInfo;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas.RtpDescription;

public class OmemoVerifiedRtpContentMap extends RtpContentMap {
    public OmemoVerifiedRtpContentMap(Group group, Map<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>> contents) {
        super(group, contents);
        for(final DescriptionTransport<RtpDescription,IceUdpTransportInfo> descriptionTransport : contents.values()) {
            if (descriptionTransport.transport instanceof OmemoVerifiedIceUdpTransportInfo) {
                ((OmemoVerifiedIceUdpTransportInfo) descriptionTransport.transport).ensureNoPlaintextFingerprint();
                continue;
            }
            throw new IllegalStateException("OmemoVerifiedRtpContentMap contains non-verified transport info");
        }
    }
}

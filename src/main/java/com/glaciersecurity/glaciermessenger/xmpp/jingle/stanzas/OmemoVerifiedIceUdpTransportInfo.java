package com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas;

import com.glaciersecurity.glaciermessenger.xml.Namespace;

public class OmemoVerifiedIceUdpTransportInfo extends IceUdpTransportInfo {


    public void ensureNoPlaintextFingerprint() {
        if (this.findChild("fingerprint", Namespace.JINGLE_APPS_DTLS) != null) {
            throw new IllegalStateException("OmemoVerifiedIceUdpTransportInfo contains plaintext fingerprint");
        }
    }

    // Named apart from the inherited IceUdpTransportInfo.upgrade(Element): an IceUdpTransportInfo
    // is an Element, so both applied and the argument's declared type picked the winner.
    public static IceUdpTransportInfo upgradeIfOmemoVerified(final IceUdpTransportInfo transportInfo) {
        if (transportInfo.hasChild("fingerprint", Namespace.JINGLE_APPS_DTLS)) {
            return transportInfo;
        }
        if (transportInfo.hasChild("fingerprint", Namespace.OMEMO_DTLS_SRTP_VERIFICATION)) {
            final OmemoVerifiedIceUdpTransportInfo omemoVerifiedIceUdpTransportInfo = new OmemoVerifiedIceUdpTransportInfo();
            omemoVerifiedIceUdpTransportInfo.setAttributes(transportInfo.getAttributes());
            omemoVerifiedIceUdpTransportInfo.setChildren(transportInfo.getChildren());
            return omemoVerifiedIceUdpTransportInfo;
        }
        return transportInfo;
    }

}

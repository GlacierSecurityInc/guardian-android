package im.conversations.android.xmpp.model.axolotl;

import im.conversations.android.xmpp.model.ByteContent;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECPublicKey;

public interface ECPublicKeyContent extends ByteContent {

    default ECPublicKey asECPublicKey() {
        try {
            return Curve.decodePoint(asBytes(), 0);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException(
                    String.format("%s does not contain a valid ECPublicKey", getClass().getName()),
                    e);
        }
    }

    default void setContent(final ECPublicKey ecPublicKey) {
        setContent(ecPublicKey.serialize());
    }
}

package im.conversations.android.xmpp.model.sasl2;

import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.StreamElement;

@XmlElement
public class Success extends StreamElement {


    public Success() {
        super(Success.class);
    }

    public Jid getAuthorizationIdentifier() {
        final var id = this.getExtension(AuthorizationIdentifier.class);
        if (id == null) {
            return null;
        }
        return id.get();
    }
}

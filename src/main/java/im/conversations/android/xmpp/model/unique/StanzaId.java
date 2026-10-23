package im.conversations.android.xmpp.model.unique;

import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class StanzaId extends Extension {

    public StanzaId() {
        super(StanzaId.class);
    }

    public Jid getBy() {
        return this.getAttributeAsJid("by");
    }

    public String getId() {
        return this.getAttribute("id");
    }
}

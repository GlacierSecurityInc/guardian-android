package im.conversations.android.xmpp.model.blocking;

import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class Item extends Extension {

    public Item() {
        super(Item.class);
    }

    public Jid getJid() {
        return getAttributeAsJid("jid");
    }
}

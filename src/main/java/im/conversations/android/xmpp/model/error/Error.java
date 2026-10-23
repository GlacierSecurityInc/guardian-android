package im.conversations.android.xmpp.model.error;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;
import java.util.Locale;
import com.glaciersecurity.glaciermessenger.xml.Namespace;

@XmlElement(namespace = Namespace.JABBER_CLIENT)
public class Error extends Extension {

    public Error() {
        super(Error.class);
    }

    public Condition getCondition() {
        return this.getExtension(Condition.class);
    }

    public void setCondition(final Condition condition) {
        this.addExtension(condition);
    }

    public Text getText() {
        return this.getExtension(Text.class);
    }

    public String getTextAsString() {
        final var text = getText();
        return text == null ? null : text.getContent();
    }

    public void setType(final Type type) {
        this.setAttribute("type", type.toString().toLowerCase(Locale.ROOT));
    }

    public enum Type {
        MODIFY,
        CANCEL,
        AUTH,
        WAIT
    }

    // Base for the extensions that live inside an <error/>. Not named Extension: that is the
    // model-wide base class this one extends, and inside Error the nested name won.
    public static class Child extends im.conversations.android.xmpp.model.Extension {

        public Child(Class<? extends im.conversations.android.xmpp.model.Extension> clazz) {
            super(clazz);
        }
    }
}

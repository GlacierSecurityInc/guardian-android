package im.conversations.android.xmpp.model.jmi;

import com.google.common.collect.ImmutableList;

import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas.FileTransferDescription;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas.GenericDescription;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas.RtpDescription;
import im.conversations.android.annotation.XmlElement;

import java.util.List;

@XmlElement
public class Propose extends JingleMessage {

    public Propose() {
        super(Propose.class);
    }

    public List<GenericDescription> getDescriptions() {
        final ImmutableList.Builder<GenericDescription> builder = new ImmutableList.Builder<>();
        for (final Element child : this.children) {
            if ("description".equals(child.getName())) {
                final String namespace = child.getNamespace();
                if (Namespace.JINGLE_APPS_FILE_TRANSFER.contains(namespace)) {
                    builder.add(FileTransferDescription.upgrade(child));
                } else if (Namespace.JINGLE_APPS_RTP.equals(namespace)) {
                    builder.add(RtpDescription.upgrade(child));
                } else {
                    builder.add(GenericDescription.upgrade(child));
                }
            }
        }
        return builder.build();
    }
}

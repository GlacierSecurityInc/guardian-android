package im.conversations.android.xmpp.model.sasl2;

import com.glaciersecurity.glaciermessenger.crypto.sasl.SaslMechanism;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.AuthenticationRequest;

@XmlElement
public class Authenticate extends AuthenticationRequest {

    public Authenticate() {
        super(Authenticate.class);
    }

    @Override
    public void setMechanism(final SaslMechanism mechanism) {
        this.setAttribute("mechanism", mechanism.getMechanism());
    }
}

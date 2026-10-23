package com.glaciersecurity.glaciermessenger.services;

import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailabilityLight;
import com.google.firebase.messaging.FirebaseMessaging;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.utils.PhoneHelper;
import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;
import com.glaciersecurity.glaciermessenger.xmpp.XmppConnection;
import com.glaciersecurity.glaciermessenger.xmpp.forms.Data;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import im.conversations.android.xmpp.model.stanza.Iq;

public class PushManagementService {

	protected final XmppConnectionService mXmppConnectionService;

	PushManagementService(XmppConnectionService service) {
		this.mXmppConnectionService = service;
	}

	private static Data findResponseData(Iq response) {
		final Element command = response.findChild("command", Namespace.COMMANDS);
		final Element x = command == null ? null : command.findChild("x", Namespace.DATA);
		return x == null ? null : Data.parse(x);
	}

	private Jid getAppServer() {
		return Jid.of(mXmppConnectionService.getString(R.string.app_server));
	}

	public void registerPushTokenOnServer(final Account account) {
		retrieveFcmInstanceToken(
				token -> {
					final String androidId = PhoneHelper.getAndroidId(mXmppConnectionService);
					final var packet =
							mXmppConnectionService
									.getIqGenerator()
									.pushTokenToAppServer(getAppServer(), token, androidId);
					mXmppConnectionService.sendIqPacket(
							account,
							packet,
							(response) -> {
								final Data data = findResponseData(response);
								if (response.getType() == Iq.Type.RESULT && data != null) {
									final Jid jid;
									try {
										jid = Jid.ofEscaped(data.getValue("jid"));
									} catch (final IllegalArgumentException e) {
										return;
									}
									final String node = data.getValue("node");
									final String secret = data.getValue("secret");
									if (node != null && secret != null) {
										enablePushOnServer(account, jid, node, secret);
									}
								}
							});
				});
	}

	private void enablePushOnServer(
			final Account account, final Jid appServer, final String node, final String secret) {
		final Iq enable =
				mXmppConnectionService.getIqGenerator().enablePush(appServer, node, secret);
		mXmppConnectionService.sendIqPacket(
				account,
				enable,
				(p) -> {
					if (p.getType() == Iq.Type.RESULT) {
					} else if (p.getType() == Iq.Type.ERROR) {
					}
				});
	}

	private void retrieveFcmInstanceToken(
			final OnGcmInstanceTokenRetrieved instanceTokenRetrieved) {
		final FirebaseMessaging firebaseMessaging;
		try {
			firebaseMessaging = FirebaseMessaging.getInstance();
		} catch (IllegalStateException e) {
			return;
		}
		firebaseMessaging
				.getToken()
				.addOnCompleteListener(
						task -> {
							if (!task.isSuccessful()) {
							}
							final String result;
							try {
								result = task.getResult();
							} catch (Exception e) {
								return;
							}
							if (result != null) {
								instanceTokenRetrieved.onGcmInstanceTokenRetrieved(result);
							}
						});
	}

	public boolean available(final Account account) {
		final XmppConnection connection = account.getXmppConnection();
		return connection != null
				&& connection.getFeatures().sm()
				&& connection.getFeatures().push()
				&& playServicesAvailable();
	}

	private boolean playServicesAvailable() {
		return GoogleApiAvailabilityLight.getInstance()
				.isGooglePlayServicesAvailable(mXmppConnectionService)
				== ConnectionResult.SUCCESS;
	}

	public boolean isStub() {
		return false;
	}

	interface OnGcmInstanceTokenRetrieved {
		void onGcmInstanceTokenRetrieved(String token);
	}
}

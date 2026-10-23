package com.glaciersecurity.glaciermessenger.services;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.DisplayMetrics;

import com.glaciersecurity.glaciermessenger.entities.NativeContact;
import android.util.LruCache;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Bookmark;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Conversational;
import com.glaciersecurity.glaciermessenger.entities.ListItem;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.entities.MucOptions;
import com.glaciersecurity.glaciermessenger.entities.RawBlockable;
import com.glaciersecurity.glaciermessenger.entities.Room;
import com.glaciersecurity.glaciermessenger.utils.UIHelper;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.OnAdvancedStreamFeaturesLoaded;
import com.glaciersecurity.glaciermessenger.xmpp.XmppConnection;

public class AvatarService implements OnAdvancedStreamFeaturesLoaded {

	private static final int TRANSPARENT = 0x00000000;
	private static final int PLACEHOLDER_COLOR = 0xFF202020;
	private static final int PLACEHOLDER_TEXT_COLOR = 0xFFFAFAFA;

	public static final int SYSTEM_UI_AVATAR_SIZE = 48;

	private static final String PREFIX_CONTACT = "contact";
	private static final String PREFIX_CONVERSATION = "conversation";
	private static final String PREFIX_ACCOUNT = "account";
	private static final String PREFIX_GENERIC = "generic";

	private static final String CHANNEL_SYMBOL = "#";

	final private Set<Integer> sizes = new HashSet<>();
	final private HashMap<String, Set<String>> conversationDependentKeys = new HashMap<>();

	protected XmppConnectionService mXmppConnectionService = null;

	AvatarService(XmppConnectionService service) {
		this.mXmppConnectionService = service;
	}

	public static int getSystemUiAvatarSize(final Context context) {
		return (int) (SYSTEM_UI_AVATAR_SIZE * context.getResources().getDisplayMetrics().density);
	}

	public Bitmap getForAvatarable(final Avatarable avatarable, final int size, final boolean cachedOnly) {
		if (avatarable instanceof Account) {
			return getForAccount((Account) avatarable,size,cachedOnly);
		} else if (avatarable instanceof Conversation) {
			return getForConversation((Conversation) avatarable, size, cachedOnly);
		} else if (avatarable instanceof Message) {
			return getForMessage((Message) avatarable, size, cachedOnly);
		} else if (avatarable instanceof ListItem) {
			return getForListItem((ListItem) avatarable, size, cachedOnly);
		} else if (avatarable instanceof MucOptions.User) {
			return getForUser((MucOptions.User) avatarable, size, cachedOnly);
		} else if (avatarable instanceof Room) {
			return getForRoom((Room) avatarable, size, cachedOnly);
		} else if (avatarable instanceof NativeContact) {
			return getForNativeContact((NativeContact) avatarable, size, cachedOnly);
		}
		throw new AssertionError("AvatarService does not know how to generate avatar from "+avatarable.getClass().getName());

	}

	private Bitmap getForRoom(final Room result, final int size, boolean cacheOnly) {
		final Jid room = result.getRoom();
		Conversation conversation = room != null ? mXmppConnectionService.findFirstMuc(room) : null;
		if (conversation != null) {
			return getForConversation(conversation,size,cacheOnly);
		}
		return getForName(CHANNEL_SYMBOL, room != null ? room.asBareJid().toEscapedString() : result.getName(), size, cacheOnly);
	}

	private Bitmap getForContact(final Contact contact, final int size, boolean cachedOnly) {
		if (contact.isSelf()) {
			return getForAccount(contact.getAccount(), size, cachedOnly);
		}
		final String KEY = key(contact, size);
		Bitmap avatar = this.mXmppConnectionService.getBitmapCache().get(KEY);
		if (avatar != null || cachedOnly) {
			return avatar;
		}
		if (contact.getAvatarFilename() != null && QuickConversationsService.isQuicksy()) {
			avatar = mXmppConnectionService.getFileBackend().getAvatar(contact.getAvatarFilename(), size);
		}
		if (avatar == null && contact.getProfilePhoto() != null) {
			avatar = mXmppConnectionService.getFileBackend().cropCenterSquare(Uri.parse(contact.getProfilePhoto()), size);
		}
		if (avatar == null && contact.getAvatarFilename() != null) {
			avatar = mXmppConnectionService.getFileBackend().getAvatar(contact.getAvatarFilename(), size);
		}
		if (avatar == null) {
			avatar = getForName(contact.getDisplayName(), contact.getJid().asBareJid().toString(), size, false);
		}
		this.mXmppConnectionService.getBitmapCache().put(KEY, avatar);
		return avatar;
	}

	public Bitmap getRoundedShortcut(final MucOptions mucOptions) {
		final DisplayMetrics metrics = mXmppConnectionService.getResources().getDisplayMetrics();
		final int size = Math.round(metrics.density * 48);
		final Bitmap bitmap = getForMucOptions(mucOptions, size, false);
		final Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
		final Canvas canvas = new Canvas(output);
		final Paint paint = new Paint();
		drawAvatar(bitmap, canvas, paint);
		return output;
	}

	public Bitmap getRoundedShortcut(final Contact contact) {
		return getRoundedShortcut(contact, false);
	}

	public Bitmap getRoundedShortcutWithIcon(final Contact contact) {
		return getRoundedShortcut(contact, true);
	}

	private Bitmap getRoundedShortcut(final Contact contact, boolean withIcon) {
		DisplayMetrics metrics = mXmppConnectionService.getResources().getDisplayMetrics();
		int size = Math.round(metrics.density * 48);
		Bitmap bitmap = getForListItem(contact, size);
		Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(output);
		final Paint paint = new Paint();

		drawAvatar(bitmap, canvas, paint);
		if (withIcon) {
			drawIcon(canvas, paint);
		}
		return output;
	}

	private static void drawAvatar(Bitmap bitmap, Canvas canvas, Paint paint) {
		final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
		paint.setAntiAlias(true);
		canvas.drawARGB(0, 0, 0, 0);
		canvas.drawCircle(bitmap.getWidth() / 2, bitmap.getHeight() / 2, bitmap.getWidth() / 2, paint);
		paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
		canvas.drawBitmap(bitmap, rect, rect, paint);
	}

	private void drawIcon(Canvas canvas, Paint paint) {
		final Resources resources = mXmppConnectionService.getResources();
		final Bitmap icon = getRoundLauncherIcon(resources);
		if (icon == null) {
			return;
		}
		paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));

		int iconSize = Math.round(canvas.getHeight() / 2.6f);

		int left = canvas.getWidth() - iconSize;
		int top = canvas.getHeight() - iconSize;
		final Rect rect = new Rect(left, top, left + iconSize, top + iconSize);
		canvas.drawBitmap(icon, null, rect, paint);
	}

	private static Bitmap getRoundLauncherIcon(Resources resources) {

		final Drawable drawable = ResourcesCompat.getDrawable(resources, R.mipmap.ic_launcher_round,null);
		if (drawable == null) {
			return null;
		}

		if (drawable instanceof BitmapDrawable) {
			return ((BitmapDrawable)drawable).getBitmap();
		}

		Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
		drawable.draw(canvas);

		return bitmap;
	}

	public Bitmap getForUser(final MucOptions.User user, final int size, boolean cachedOnly) {
		Contact c = user.getContact();
		if (c != null && (c.getProfilePhoto() != null || c.getAvatarFilename() != null || user.getAvatar() == null)) {
			return getForContact(c, size, cachedOnly);
		} else {
			return getImpl(user, size, cachedOnly);
		}
	}

	private Bitmap getImpl(final MucOptions.User user, final int size, boolean cachedOnly) {
		final String KEY = key(user, size);
		Bitmap avatar = this.mXmppConnectionService.getBitmapCache().get(KEY);
		if (avatar != null || cachedOnly) {
			return avatar;
		}
		if (user.getAvatar() != null) {
			avatar = mXmppConnectionService.getFileBackend().getAvatar(user.getAvatar(), size);
		}
		if (avatar == null) {
			Contact contact = user.getContact();
			if (contact != null) {
				avatar = getForContact(contact, size, false);
			} else {
				String seed = user.getRealJid() != null ? user.getRealJid().asBareJid().toString() : null;
				avatar = getForName(user.getName(), seed, size, false);
			}
		}
		this.mXmppConnectionService.getBitmapCache().put(KEY, avatar);
		return avatar;
	}

	public void clear(Contact contact) {
		synchronized (this.sizes) {
			for (final Integer size : sizes) {
				this.mXmppConnectionService.getBitmapCache().remove(key(contact, size));
			}
		}
		for (Conversation conversation : mXmppConnectionService.findAllConferencesWith(contact)) {
			MucOptions.User user = conversation.getMucOptions().findUserByRealJid(contact.getJid().asBareJid());
			if (user != null) {
				clear(user);
			}
			clear(conversation);
		}
	}

	private String key(Contact contact, int size) {
		synchronized (this.sizes) {
			this.sizes.add(size);
		}

		return PREFIX_CONTACT +
				'\0' +
				contact.getAccount().getJid().asBareJid() +
				'\0' +
				emptyOnNull(contact.getJid()) +
				'\0' +
				size;
	}

	private String key(NativeContact contact, int size) {
		synchronized (this.sizes) {
			this.sizes.add(size);
		}
		return PREFIX_CONTACT +
				'\0' +
				contact.getDisplayName() +
				'\0' +
				contact.getPrimaryNumber() +
				'\0' +
				size;
	}

	private String key(MucOptions.User user, int size) {
		synchronized (this.sizes) {
			this.sizes.add(size);
		}
		return PREFIX_CONTACT +
				'\0' +
				user.getAccount().getJid().asBareJid() +
				'\0' +
				emptyOnNull(user.getFullJid()) +
				'\0' +
				emptyOnNull(user.getRealJid()) +
				'\0' +
				size;
	}

	public Bitmap getForNativeContact(NativeContact item, int size) {
		return getForNativeContact(item, size, false);
	}

	private Bitmap getForNativeContact(final NativeContact contact, final int size, boolean cachedOnly) {
		final String KEY = key(contact, size);
		Bitmap avatar = this.mXmppConnectionService.getBitmapCache().get(KEY);
		if (avatar != null || cachedOnly) {
			return avatar;
		}
		if (avatar == null) {
			avatar = getForName(contact.getDisplayName(), contact.getPrimaryNumber(), size, false);
		}
		this.mXmppConnectionService.getBitmapCache().put(KEY, avatar);

		return avatar;
	}


	public Bitmap getForListItem(ListItem item, int size) {
		return getForListItem(item, size, false);
	}

	public Bitmap getForListItem(ListItem item, int size, boolean cachedOnly) {
		if (item instanceof RawBlockable) {
			return getForName(item.getDisplayName(), item.getJid().toEscapedString(), size, cachedOnly);
		} else if (item instanceof Contact) {
			return getForContact((Contact) item, size, cachedOnly);
		} else if (item instanceof Bookmark) {
			Bookmark bookmark = (Bookmark) item;
			if (bookmark.getConversation() != null) {
				return getForConversation(bookmark.getConversation(), size, cachedOnly);
			} else {
				Jid jid = bookmark.getJid();
				Account account = bookmark.getAccount();
				Contact contact = jid == null ? null : account.getRoster().getContact(jid);
				if (contact != null && contact.getAvatarFilename() != null) {
					return getForContact(contact, size, cachedOnly);
				}
				String seed = jid != null ? jid.asBareJid().toString() : null;
				return getForName(bookmark.getDisplayName(), seed, size, cachedOnly);
			}
		} else {
			String seed = item.getJid() != null ? item.getJid().asBareJid().toString() : null;
			return getForName(item.getDisplayName(), seed, size, cachedOnly);
		}
	}

	public Bitmap getForConversation(Conversation conversation, int size) {
		return getForConversation(conversation, size, false);
	}

	public Bitmap getForConversation(Conversation conversation, int size, boolean cachedOnly) {
		if (conversation.getMode() == Conversation.MODE_SINGLE) {
			return getForContact(conversation.getContact(), size, cachedOnly);
		} else {
			return getForMucOptions(conversation.getMucOptions(), size, cachedOnly);
		}
	}

	public void clear(Conversation conversation) {
		if (conversation.getMode() == Conversation.MODE_SINGLE) {
			clear(conversation.getContact());
		} else {
			clear(conversation.getMucOptions());
			synchronized (this.conversationDependentKeys) {
				Set<String> keys = this.conversationDependentKeys.get(conversation.getUuid());
				if (keys == null) {
					return;
				}
				LruCache<String, Bitmap> cache = this.mXmppConnectionService.getBitmapCache();
				for (String key : keys) {
					cache.remove(key);
				}
				keys.clear();
			}
		}
	}

	private Bitmap getForMucOptions(MucOptions mucOptions, int size, boolean cachedOnly) {
		final String KEY = key(mucOptions, size);
		Bitmap bitmap = this.mXmppConnectionService.getBitmapCache().get(KEY);
		if (bitmap != null || cachedOnly) {
			return bitmap;
		}

		bitmap = mXmppConnectionService.getFileBackend().getAvatar(mucOptions.getAvatar(), size);

		if (bitmap == null) {
			Conversation c = mucOptions.getConversation();
			if (mucOptions.isPrivateAndNonAnonymous()) {
				final List<MucOptions.User> users = mucOptions.getUsersRelevantForNameAndAvatar();
				if (users.size() == 0) {
					bitmap = getImpl(c.getName().toString(), c.getJid().asBareJid().toString(), size);
				} else {
					bitmap = getImpl(users, size);
				}
			} else {
				bitmap = getImpl(CHANNEL_SYMBOL, c.getJid().asBareJid().toString(), size);
			}
		}

		this.mXmppConnectionService.getBitmapCache().put(KEY, bitmap);

		return bitmap;
	}

	private Bitmap getForUsers(List<MucOptions.User> users, int size, boolean cachedOnly) {
		final String KEY = key(users, size);
		Bitmap bitmap = this.mXmppConnectionService.getBitmapCache().get(KEY);
		if (bitmap != null || cachedOnly) {
			return bitmap;
		}
		bitmap = getImpl(users, size);
		this.mXmppConnectionService.getBitmapCache().put(KEY, bitmap);
		return bitmap;
	}

	private Bitmap getImpl(List<MucOptions.User> users, int size) {
		int count = users.size();
		Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		bitmap.eraseColor(TRANSPARENT);
		if (count == 0) {
			throw new AssertionError("Unable to draw tiles for 0 users");
		} else if (count == 1) {
			drawTile(canvas, users.get(0), 0, 0, size / 2 - 1, size);
			drawTile(canvas, users.get(0).getAccount(), size / 2 + 1, 0, size, size);
		} else if (count == 2) {
			drawTile(canvas, users.get(0), 0, 0, size / 2 - 1, size);
			drawTile(canvas, users.get(1), size / 2 + 1, 0, size, size);
		} else if (count == 3) {
			drawTile(canvas, users.get(0), 0, 0, size / 2 - 1, size);
			drawTile(canvas, users.get(1), size / 2 + 1, 0, size, size / 2 - 1);
			drawTile(canvas, users.get(2), size / 2 + 1, size / 2 + 1, size,
					size);
		} else if (count == 4) {
			drawTile(canvas, users.get(0), 0, 0, size / 2 - 1, size / 2 - 1);
			drawTile(canvas, users.get(1), 0, size / 2 + 1, size / 2 - 1, size);
			drawTile(canvas, users.get(2), size / 2 + 1, 0, size, size / 2 - 1);
			drawTile(canvas, users.get(3), size / 2 + 1, size / 2 + 1, size,
					size);
		} else {
			drawTile(canvas, users.get(0), 0, 0, size / 2 - 1, size / 2 - 1);
			drawTile(canvas, users.get(1), 0, size / 2 + 1, size / 2 - 1, size);
			drawTile(canvas, users.get(2), size / 2 + 1, 0, size, size / 2 - 1);
			drawTile(canvas, "\u2026", PLACEHOLDER_COLOR, PLACEHOLDER_TEXT_COLOR, size / 2 + 1, size / 2 + 1,
					size, size);
		}
		return bitmap;
	}

	public void clear(final MucOptions options) {
		if (options == null) {
			return;
		}
		synchronized (this.sizes) {
			for (Integer size : sizes) {
				this.mXmppConnectionService.getBitmapCache().remove(key(options, size));
			}
		}
	}

	private String key(final MucOptions options, int size) {
		synchronized (this.sizes) {
			this.sizes.add(size);
		}
		return PREFIX_CONVERSATION + "_" + options.getConversation().getUuid() + "_" + size;
	}

	private String key(List<MucOptions.User> users, int size) {
		final Conversation conversation = users.get(0).getConversation();
		StringBuilder builder = new StringBuilder("TILE_");
		builder.append(conversation.getUuid());

		for (MucOptions.User user : users) {
			builder.append("\0");
			builder.append(emptyOnNull(user.getRealJid()));
			builder.append("\0");
			builder.append(emptyOnNull(user.getFullJid()));
		}
		builder.append('\0');
		builder.append(size);
		final String key = builder.toString();
		synchronized (this.conversationDependentKeys) {
			Set<String> keys;
			if (this.conversationDependentKeys.containsKey(conversation.getUuid())) {
				keys = this.conversationDependentKeys.get(conversation.getUuid());
			} else {
				keys = new HashSet<>();
				this.conversationDependentKeys.put(conversation.getUuid(), keys);
			}
			keys.add(key);
		}
		return key;
	}

	public Bitmap getForAccount(Account account, int size) {
		return getForAccount(account, size, false);
	}

	public Bitmap getForAccount(Account account, int size, boolean cachedOnly) {
		final String KEY = key(account, size);
		Bitmap avatar = mXmppConnectionService.getBitmapCache().get(KEY);
		if (avatar != null || cachedOnly) {
			return avatar;
		}
		avatar = mXmppConnectionService.getFileBackend().getAvatar(account.getAvatar(), size);
		if (avatar == null) {
			final String displayName = account.getDisplayName();
			final String jid = account.getJid().asBareJid().toEscapedString();
			if (QuickConversationsService.isQuicksy() && !TextUtils.isEmpty(displayName)) {
				avatar = getForName(displayName, jid, size, false);
			} else {
				avatar = getForName(jid, null, size, false);
			}
		}
		mXmppConnectionService.getBitmapCache().put(KEY, avatar);
		return avatar;
	}

	public Bitmap getForMessage(Message message, int size, boolean cachedOnly) {
		final Conversational conversation = message.getConversation();
		if (message.getType() == Message.TYPE_STATUS && message.getCounterparts() != null && message.getCounterparts().size() > 1) {
			return getForUsers(message.getCounterparts(), size, cachedOnly);
		} else if (message.getStatus() == Message.STATUS_RECEIVED) {
			Contact c = message.getContact();
			if (c != null && (c.getProfilePhoto() != null || c.getAvatarFilename() != null)) {
				return getForContact(c, size, cachedOnly);
			} else if (conversation instanceof Conversation && message.getConversation().getMode() == Conversation.MODE_MULTI) {
				final Jid trueCounterpart = message.getTrueCounterpart();
				final MucOptions mucOptions = ((Conversation) conversation).getMucOptions();
				MucOptions.User user;
				if (trueCounterpart != null) {
					user = mucOptions.findOrCreateUserByRealJid(trueCounterpart, message.getCounterpart());
				} else {
					user = mucOptions.findUserByFullJid(message.getCounterpart());
				}
				if (user != null) {
					return getImpl(user, size, cachedOnly);
				}
			} else if (c != null) {
				return getForContact(c, size, cachedOnly);
			}
			Jid tcp = message.getTrueCounterpart();
			String seed = tcp != null ? tcp.asBareJid().toString() : null;
			return getForName(UIHelper.getMessageDisplayName(message), seed, size, cachedOnly);
		} else {
			return getForAccount(conversation.getAccount(), size, cachedOnly);
		}
	}

	public void clear(Account account) {
		synchronized (this.sizes) {
			for (Integer size : sizes) {
				this.mXmppConnectionService.getBitmapCache().remove(key(account, size));
			}
		}
	}

	public void clear(MucOptions.User user) {
		synchronized (this.sizes) {
			for (Integer size : sizes) {
				this.mXmppConnectionService.getBitmapCache().remove(key(user, size));
			}
		}
	}

	private String key(Account account, int size) {
		synchronized (this.sizes) {
			this.sizes.add(size);
		}
		return PREFIX_ACCOUNT + "_" + account.getUuid() + "_"
				+ size;
	}

	/*public Bitmap get(String name, int size) {
		return get(name,null, size,false);
	}*/

	public Bitmap getForName(final String name, String seed, final int size, boolean cachedOnly) {
		final String KEY = key(seed == null ? name : name+"\0"+seed, size);
		Bitmap bitmap = mXmppConnectionService.getBitmapCache().get(KEY);
		if (bitmap != null || cachedOnly) {
			return bitmap;
		}
		bitmap = getImpl(name, seed, size);
		mXmppConnectionService.getBitmapCache().put(KEY, bitmap);
		return bitmap;
	}

	public static Bitmap getForJid(final Jid jid, final int size) {
		return getImpl(jid.asBareJid().toEscapedString(), null, size);
	}

	private static Bitmap getImpl(final String name, final String seed, final int size) {
		Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		final String trimmedName = name == null ? "" : name.trim();
		drawTile(canvas, trimmedName, seed, 0, 0, size, size);
		return bitmap;
	}

	private String key(String name, int size) {
		synchronized (this.sizes) {
			this.sizes.add(size);
		}
		return PREFIX_GENERIC + "_" + name + "_" + size;
	}

	private static boolean drawTile(Canvas canvas, String letter, int tileColor, int textColor, int left, int top, int right, int bottom) {
		letter = letter.toUpperCase(Locale.getDefault());
		Paint tilePaint = new Paint(), textPaint = new Paint();
		tilePaint.setColor(tileColor);
		textPaint.setFlags(Paint.ANTI_ALIAS_FLAG);
		textPaint.setColor(textColor);
		textPaint.setTypeface(Typeface.create("sans-serif-medium",
				Typeface.BOLD));
		textPaint.setTextSize((float) ((right - left) * 0.42));
		Rect rect = new Rect();

		canvas.drawRect(new Rect(left, top, right, bottom), tilePaint);
		textPaint.getTextBounds(letter, 0, 1, rect);
		float width = textPaint.measureText(letter);
		canvas.drawText(letter, (right + left) / 2 - width / 2, (top + bottom)
				/ 2 + rect.height() / 2, textPaint);
		return true;
	}

	private boolean drawTile(Canvas canvas, MucOptions.User user, int left, int top, int right, int bottom) {
		Contact contact = user.getContact();
		if (contact != null) {
			Uri uri = null;
			if (contact.getAvatarFilename() != null && QuickConversationsService.isQuicksy()) {
				uri = mXmppConnectionService.getFileBackend().getAvatarUri(contact.getAvatarFilename());
			} else if (contact.getProfilePhoto() != null) {
				uri = Uri.parse(contact.getProfilePhoto());
			} else if (contact.getAvatarFilename() != null) {
				uri = mXmppConnectionService.getFileBackend().getAvatarUri(contact.getAvatarFilename());
			}
			if (drawTile(canvas, uri, left, top, right, bottom)) {
				return true;
			}
		} else if (user.getAvatar() != null) {
			Uri uri = mXmppConnectionService.getFileBackend().getAvatarUri(user.getAvatar());
			if (drawTile(canvas, uri, left, top, right, bottom)) {
				return true;
			}
		}
		if (contact != null) {
			String seed = contact.getJid().asBareJid().toString();
			drawTile(canvas, contact.getDisplayName(), seed, left, top, right, bottom);
		} else {
			String seed = user.getRealJid() == null ? null : user.getRealJid().asBareJid().toString();
			drawTile(canvas, user.getName(), seed, left, top, right, bottom);
		}
		return true;
	}

	private boolean drawTile(Canvas canvas, Account account, int left, int top, int right, int bottom) {
		String avatar = account.getAvatar();
		if (avatar != null) {
			Uri uri = mXmppConnectionService.getFileBackend().getAvatarUri(avatar);
			if (uri != null) {
				if (drawTile(canvas, uri, left, top, right, bottom)) {
					return true;
				}
			}
		}
		String name = account.getJid().asBareJid().toString();
		return drawTile(canvas, name, name, left, top, right, bottom);
	}

	private static boolean drawTile(Canvas canvas, String name, String seed, int left, int top, int right, int bottom) {
		if (name != null) {
			final String letter = name.equals(CHANNEL_SYMBOL) ? name : getFirstLetter(name);
			final String colorSeed = seed == null ? name : seed;
			final int color = UIHelper.getColorForName(colorSeed);
			final int textColor = UIHelper.getInitialsColorForName();
			drawTile(canvas, letter, color, textColor, left, top, right, bottom);
			return true;
		}
		return false;
	}

	private static String getFirstLetter(String name) {
		for (char c : name.toCharArray()) {
			if (Character.isLetterOrDigit(c)) {
				return String.valueOf(c);
			}
		}
		return "X";
	}

	private boolean drawTile(Canvas canvas, Uri uri, int left, int top, int right, int bottom) {
		if (uri != null) {
			Bitmap bitmap = mXmppConnectionService.getFileBackend()
					.cropCenter(uri, bottom - top, right - left);
			if (bitmap != null) {
				drawTile(canvas, bitmap, left, top, right, bottom);
				return true;
			}
		}
		return false;
	}

	private boolean drawTile(Canvas canvas, Bitmap bm, int dstleft, int dsttop, int dstright, int dstbottom) {
		Rect dst = new Rect(dstleft, dsttop, dstright, dstbottom);
		canvas.drawBitmap(bm, null, dst, null);
		return true;
	}

	@Override
	public void onAdvancedStreamFeaturesAvailable(Account account) {
		XmppConnection.Features features = account.getXmppConnection().getFeatures();
		if (features.pep() && !features.pepPersistent()) {
			if (account.getAvatar() != null) {
				mXmppConnectionService.republishAvatarIfNeeded(account);
			}
		}
	}

	private static String emptyOnNull(@Nullable Jid value) {
		return value == null ? "" : value.toString();
	}

	public interface Avatarable {
		@ColorInt int getAvatarBackgroundColor();
		String getAvatarName();
	}
}

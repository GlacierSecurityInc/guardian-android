package com.glaciersecurity.glaciermessenger.entities;

import static com.glaciersecurity.glaciermessenger.GlacierGuardian.getContext;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.glaciersecurity.glaciermessenger.services.AvatarService;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.glaciersecurity.glaciermessenger.utils.UIHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * A contact read out of the device address book, used by the phone module's contact list.
 *
 * Unlike {@link Contact} this has no XMPP identity: no account, no JID, no roster subscription
 * and no OMEMO. It is a display name plus phone numbers. AvatarService draws it as a letter tile
 * from {@link #getAvatarName()} and {@link #getAvatarBackgroundColor()} rather than from a
 * published avatar, which is why nothing here stores one.
 */
public class NativeContact implements NativeListItem, AvatarService.Avatarable {

    private final String name;
    private final ArrayList<PhoneNumberEntry> numbers;

    public NativeContact(final String name, final HashSet<PhoneNumberEntry> phoneNumber){
        this.name = name;
        this.numbers = new ArrayList<>(phoneNumber);
    }

    public NativeContact(String name, String phoneNumber, String phoneNumberType) {
        this.name = name;
        this.numbers = new ArrayList<>();
        numbers.add(new PhoneNumberEntry(phoneNumber, phoneNumberType));
    }

    public static NativeContact fromCursor(final Cursor cursor) {
        final HashSet<PhoneNumberEntry> phoneNumbers = new HashSet<>();
        final int idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID);
        final int displayNameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
        final int hasPhoneNumberIndex = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER);
        final String id = cursor.getString(idIndex);
        final String name = cursor.getString(displayNameIndex);
        if (cursor.getInt(hasPhoneNumberIndex) > 0) {
            final Uri uriPhone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
            final String selection = ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " =?";
            // try-with-resources: this runs once per contact in the address book, and closing
            // only inside an "if (getCount() > 0)" leaked a CursorWindow for every contact whose
            // phone query came back empty -- and for any that threw part-way through the loop.
            try (final Cursor phoneCursor = getContext().getContentResolver()
                    .query(uriPhone, null, selection, new String[]{id}, null)) {
                if (phoneCursor != null) {
                    final int phoneNumberIndex = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                    final int phoneTypeIndex = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE);
                    while (phoneCursor.moveToNext()) {
                        final String phoneNumber = phoneCursor.getString(phoneNumberIndex);

                        final int phoneTypeInt = phoneCursor.getInt(phoneTypeIndex);

                        final String phoneType = (String) ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                getContext().getResources(), phoneTypeInt, "Custom"
                        );

                        phoneNumbers.add(new PhoneNumberEntry(PhoneUtils.getStripPhoneNumber(phoneNumber), phoneType));
                    }
                }
            }
        }
        return new NativeContact(name, phoneNumbers);
    }

    @Override
    public String getDisplayName() {
        return name;
    }

    @Override
    public String getPrimaryNumber() {
        if (numbers == null || numbers.isEmpty()){
            return null;
        }
        return numbers.get(0).phoneNumber;
    }

    @Override
    public PhoneNumberEntry getPrimaryNumberEntry() {
        if (numbers == null || numbers.isEmpty()){
            return null;
        }
        return numbers.get(0);
    }

    @Override
    public ArrayList<PhoneNumberEntry> getNumbers() {
        return numbers;
    }

    @Override
    public boolean match(Context context, String needle) {
        if (TextUtils.isEmpty(needle)) {
            return true;
        }
        needle = needle.toLowerCase(Locale.US).trim();
        String[] parts = needle.split("\\s+");
        if (parts.length > 1) {
            for (String part : parts) {
                if (!match(context, part)) {
                    return false;
                }
            }
            return true;
        } else {
            if (getDisplayName().toLowerCase(Locale.US).contains(needle)){
                return true;
            }
            for (PhoneNumberEntry numberEntry : numbers) {
                String number = numberEntry.phoneNumber;
                if (number.contains(needle)){
                    return true;
                }
            }
            return false;

        }
    }

    @Override
    public int compareTo(@NonNull final NativeListItem another) {
        return this.getDisplayName().compareToIgnoreCase(
                another.getDisplayName());
    }

    @Override
    public int getAvatarBackgroundColor() {
        return UIHelper.getColorForName(getDisplayName());
    }

    @Override
    public String getAvatarName() {
        return getDisplayName();
    }
}

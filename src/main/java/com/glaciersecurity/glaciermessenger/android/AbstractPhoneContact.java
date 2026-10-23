package com.glaciersecurity.glaciermessenger.android;

import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;

public abstract class AbstractPhoneContact {

    private final Uri lookupUri;
    private final String displayName;
    private final String photoUri;


    AbstractPhoneContact(Cursor cursor) {
        final int idIndex = cursor.getColumnIndex(ContactsContract.Data._ID);
        final int lookupKeyIndex = cursor.getColumnIndex(ContactsContract.Data.LOOKUP_KEY);
        final int displayNameIndex = cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME);
        final int photoUriIndex = cursor.getColumnIndex(ContactsContract.Data.PHOTO_URI);
        int phoneId = cursor.getInt(idIndex);
        String lookupKey = cursor.getString(lookupKeyIndex);
        this.lookupUri = ContactsContract.Contacts.getLookupUri(phoneId, lookupKey);
        this.displayName = cursor.getString(displayNameIndex);
        this.photoUri = cursor.getString(photoUriIndex);
    }

    public Uri getLookupUri() {
        return lookupUri;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPhotoUri() {
        return photoUri;
    }


    public int rating() {
        return (TextUtils.isEmpty(displayName) ? 0 : 2) + (TextUtils.isEmpty(photoUri) ? 0 : 1);
    }
}

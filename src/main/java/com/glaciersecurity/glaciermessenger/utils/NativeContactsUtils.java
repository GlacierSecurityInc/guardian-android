package com.glaciersecurity.glaciermessenger.utils;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

public class NativeContactsUtils {

    public static String getContactNameIfExists(Context context, String numberToCheck) {
        if (numberToCheck == null){
            return numberToCheck;
        }
        try {
            numberToCheck = PhoneUtils.getStripPhoneNumberWithCountryCode(numberToCheck);
            ContentResolver contentResolver = context.getContentResolver();

            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(numberToCheck)
            );

            Cursor cursor = contentResolver.query(
                    uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null, null, null
            );

            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    @SuppressLint("Range") String name = cursor.getString(
                            cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    );
                    cursor.close();
                    return name; // Contact found
                }
                cursor.close();
            }

            return PhoneUtils.reformatNumber(numberToCheck);

        } catch (Exception exception) {
            return PhoneUtils.reformatNumber(numberToCheck);
        }
    }

}


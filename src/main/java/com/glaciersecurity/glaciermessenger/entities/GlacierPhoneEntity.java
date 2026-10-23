package com.glaciersecurity.glaciermessenger.entities;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

public class GlacierPhoneEntity extends AbstractEntity {
    public static final String TABLENAME = "phone_table";
    public static final String USERNAME = "username";

    private String jid;




    public GlacierPhoneEntity(String jid){
        this.jid = jid;
    }
    public static GlacierPhoneEntity fromCursor(Cursor cursor) {
        final int usernameIndex = cursor.getColumnIndex(USERNAME);
        return new GlacierPhoneEntity(cursor.getString(usernameIndex));
    }

    public String getJid() {
        return jid;
    }

    public void setJid(String jid) {
        this.jid = jid;
    }

    @Override
    public ContentValues getContentValues() {
        final ContentValues values = new ContentValues();
        values.put(USERNAME, jid);
        return values;
    }

}

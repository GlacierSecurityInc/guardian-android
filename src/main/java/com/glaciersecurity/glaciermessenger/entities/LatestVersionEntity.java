package com.glaciersecurity.glaciermessenger.entities;

import android.content.ContentValues;
import android.database.Cursor;

import androidx.core.util.ObjectsCompat;


public class LatestVersionEntity extends AbstractEntity {
    public static final String TABLENAME = "latest_versions";
    public static final String ALT_OS ="alt_os";
    public static final String ALT_OS_NEW ="alt_os_new";
    public static final String ANDROID_OS = "android_os";
    public static final String SAMSUNG_OS = "samsung_os";

    public static final String GLACIER_ANDROID = "glacier_android";
    public static final String KEY = "key";
    private String alt_os;
    private String alt_os_new;
    private String android_os;
    private String samsung_os;

    private String glacier_android;
    private String key;

    public LatestVersionEntity(String alt_os, String alt_os_new, String android_os,String samsung_os_os, String glacier_android, String key){
        this.alt_os = alt_os;
        this.alt_os_new = alt_os_new;
        this.android_os = android_os;
        this.samsung_os = samsung_os_os;
        this.glacier_android = glacier_android;
        this.key = key;
    }


    @Override
    public ContentValues getContentValues() {
        ContentValues values = new ContentValues();
        values.put(ALT_OS, this.alt_os);
        values.put(ALT_OS_NEW, this.alt_os_new);
        values.put(ANDROID_OS, this.android_os);
        values.put(SAMSUNG_OS, this.samsung_os);
        values.put(GLACIER_ANDROID, this.glacier_android);
        values.put(KEY, this.key);
        return values;
    }

    public static LatestVersionEntity fromCursor(Cursor cursor) {
        return new LatestVersionEntity(
                cursor.getString(cursor.getColumnIndexOrThrow(ALT_OS)),
                cursor.getString(cursor.getColumnIndexOrThrow(ALT_OS_NEW)),
                cursor.getString(cursor.getColumnIndexOrThrow(ANDROID_OS)),
                cursor.getString(cursor.getColumnIndexOrThrow(SAMSUNG_OS)),
                cursor.getString(cursor.getColumnIndexOrThrow(GLACIER_ANDROID)),
                cursor.getString(cursor.getColumnIndexOrThrow(KEY)));
    }


    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if(obj == null || getClass() != obj.getClass()) {
            return false;
        } else {
            LatestVersionEntity latestVersionEntity = (LatestVersionEntity) obj;
            return ObjectsCompat.equals(getKey(), latestVersionEntity.getKey());
        }
    }

    @Override
    public int hashCode() {
        return ObjectsCompat.hashCode(getKey());
    }

    public String getSamsung_os() {
        return samsung_os;
    }

    public void setSamsung_os(String samsung_os) {
        this.samsung_os = samsung_os;
    }

    public String getAlt_os_new() {
        return alt_os_new;
    }

    public void setAlt_os_new(String alt_os_new) {
        this.alt_os_new = alt_os_new;
    }

    public String getAlt_os() {
        return alt_os;
    }

    public void setAlt_os(String alt_os) {
        this.alt_os = alt_os;
    }

    public String getAndroid_os() {
        return android_os;
    }

    public void setAndroid_os(String android_os) {
        this.android_os = android_os;
    }

    public String getGlacier_android() {
        return glacier_android;
    }

    public void setGlacier_android(String glacier_android) {
        this.glacier_android = glacier_android;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}

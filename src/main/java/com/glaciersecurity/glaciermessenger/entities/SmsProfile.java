package com.glaciersecurity.glaciermessenger.entities;
import android.content.ContentValues;
import android.database.Cursor;

import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.glaciersecurity.glaciermessenger.utils.UIHelper;
import com.google.gson.internal.LinkedTreeMap;
import java.util.Objects;

public class SmsProfile extends AbstractEntity{

    public static final String TABLENAME = "sms_profile";
    public static final String TWILIO_ID ="twilioId";
    public static final String PHONE_NUMBER ="phoneNumber";
    public static final String DISPLAY_NAME = "displayName";
    public static final String COLOR = "color";
    public static final String IS_ACTIVE = "is_active";
    protected Integer unread_count;

    protected String formattedNumber;
    protected String unformatted_number;
    protected String id;
    protected String nickname = "";
    protected Integer color;
    protected boolean isActive = true;

    public SmsProfile(LinkedTreeMap<String, String> linkedTreeMap) {
        unformatted_number = linkedTreeMap.get("text");
        formattedNumber = PhoneUtils.reformatNumber(unformatted_number);
        id = linkedTreeMap.get("id");
        nickname = linkedTreeMap.get("nickname");
        if (id != null && !id.isEmpty()) {
            this.color = UIHelper.getColorForSMS(id);
        } else {
            this.color = UIHelper.getColorForSMS(unformatted_number);
        }
        UIHelper.registerSmsColor(id != null && !id.isEmpty() ? id : unformatted_number, this.color);
        this.isActive = true;

    }

    public SmsProfile(String number, String id){
        this.unformatted_number = number;
        this.formattedNumber = PhoneUtils.reformatNumber(unformatted_number);
        this.id = id;
        if (id != null && !id.isEmpty()) {
            this.color = UIHelper.getColorForSMS(id);
        } else {
            this.color = UIHelper.getColorForSMS(unformatted_number);
        }
        UIHelper.registerSmsColor(id != null && !id.isEmpty() ? id : unformatted_number, this.color);
        this.isActive = true;
    }

    public SmsProfile(String twilioId, String phoneNumber, String displayName, Integer color) {
        this.unformatted_number = phoneNumber;
        this.formattedNumber = PhoneUtils.reformatNumber(unformatted_number);
        this.nickname = displayName;
        this.id = twilioId;
        this.color = color;
        if (color == null) {
            if (id != null && !id.isEmpty()) {
                this.color = UIHelper.getColorForSMS(id);
            } else {
                this.color = UIHelper.getColorForSMS(unformatted_number);
            }
        } else {
            UIHelper.registerSmsColor(id != null && !id.isEmpty() ? id : unformatted_number, this.color);
        }
        this.isActive = true;
    }

    public Integer getUnread_count() {
        return unread_count;
    }

    public void setUnread_count(Integer unread_count) {
        this.unread_count = unread_count;
    }


    public String getFormattedNumber() {
        if (formattedNumber == null) {
            this.formattedNumber = PhoneUtils.reformatNumber(unformatted_number);
        }
        return formattedNumber;
    }

    public String getNickname() {
        if (nickname == null || nickname.isEmpty()) {
            return formattedNumber;
        }
        return nickname;
    }

    public void setNickname(String name){
        this.nickname = name;
    }

    public int getColor() {
        if (color == null) {
            if (id != null && !id.isEmpty()) {
                this.color = UIHelper.getColorForSMS(id);
            } else {
                this.color = UIHelper.getColorForSMS(unformatted_number);
            }
        }
        return color;
    }

    public String getId() {
        return id;
    }

    public String getUnformattedNumber(){
        return unformatted_number;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SmsProfile that = (SmsProfile) o;
        return Objects.equals(formattedNumber, that.formattedNumber) && Objects.equals(unformatted_number, that.unformatted_number) && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(formattedNumber, unformatted_number, id);
    }

    @Override
    public ContentValues getContentValues() {
        ContentValues values = new ContentValues();
        values.put(TWILIO_ID, this.id);
        values.put(PHONE_NUMBER, this.unformatted_number);
        values.put(DISPLAY_NAME, this.nickname);
        values.put(COLOR, this.color);
        values.put(IS_ACTIVE, this.isActive ? 1 : 0);
        return values;
    }

    public static SmsProfile fromCursor(Cursor cursor) {
        SmsProfile profile = new SmsProfile(
                cursor.getString(cursor.getColumnIndexOrThrow(TWILIO_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(PHONE_NUMBER)),
                cursor.getString(cursor.getColumnIndexOrThrow(DISPLAY_NAME)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COLOR)));
        profile.setActive(cursor.getInt(cursor.getColumnIndexOrThrow(IS_ACTIVE)) == 1);
        UIHelper.registerSmsColor(profile.getId() != null && !profile.getId().isEmpty() ?
                profile.getId() : profile.getUnformattedNumber(), profile.getColor());
        return profile;
    }

}

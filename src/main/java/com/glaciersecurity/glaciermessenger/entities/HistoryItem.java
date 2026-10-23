package com.glaciersecurity.glaciermessenger.entities;

import android.content.ContentValues;
import android.database.Cursor;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.glaciersecurity.glaciermessenger.utils.DateParser;
import com.google.gson.internal.LinkedTreeMap;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;

public class HistoryItem extends AbstractEntity implements Comparable<HistoryItem>, Parcelable {
    public static final String TABLENAME = "call_history";
    public static final String TWILIO_SID ="twilioSid";
    public static final String PROXY_SID ="proxySid";
    public static final String TO= "to_number";
    public static final String TO_FORMATTED= "to_formatted";
    public static final String FROM= "from_number";
    public static final String FROM_FORMATTED= "from_formatted";
    public static final String STATUS= "status";
    public static final String START_TIME= "start_time";
    public static final String END_TIME = "end_time";
    public static final String DATE_CREATED = "date_created";
    public static final String DIRECTION = "direction";
    public static final String DURATION = "duration";
    public static final String REMOTE = "remote";
    public static final String IS_UNREAD = "is_unread";

    protected String from;
    protected String to;
    protected String remote;
    protected String dateCreated;
    protected Date timeStamp_date;
    protected String status;
    protected String direction;
    protected String duration;
    protected String sid;
    protected String proxySid;
    protected boolean isSelected;
    protected boolean isUnread;

    protected boolean isDeletePendingView;


    public HistoryItem(String proxySid, LinkedTreeMap<String, String> linkedTreeMap) {
        sid = linkedTreeMap.get("sid");
        this.proxySid = proxySid;
        to = linkedTreeMap.get("to");
        from = linkedTreeMap.get("from");
        dateCreated = linkedTreeMap.get("dateCreated");
        status = linkedTreeMap.get("status");
        direction = linkedTreeMap.get("direction");
        duration = linkedTreeMap.get("duration");
        setRemote();
        isUnread = isMissedInbound(direction, status);
        parseTimestamp(dateCreated);
        parseDuration(duration);
    }

    private void setRemote() {
        if (direction != null){
            if (direction.equals("inbound")){
                remote = from;
            } else {
                remote = to;
            }
        }
    }

    protected HistoryItem(Parcel in) {
        sid = in.readString();
        proxySid = in.readString();
        from = in.readString();
        to = in.readString();
        dateCreated = in.readString();
        status = in.readString();
        direction = in.readString();
        duration = in.readString();
        remote = in.readString();
        isUnread = in.readByte() != 0;
        parseTimestamp(dateCreated);
        parseDuration(duration);
    }

    public HistoryItem(String sid, String proxySid, String from, String to, String dateCreated, String status, String direction, String duration, String remote, boolean isUnread) {
        this.sid = sid;
        this.proxySid = proxySid;
        this.from = from;
        this.to = to;
        this.dateCreated = dateCreated;
        this.status = status;
        this.direction = direction;
        this.duration = duration;
        this.remote = remote;
        this.isUnread = isUnread;
        parseTimestamp(dateCreated);
        parseDuration(duration);
    }

    public void parseTimestamp(String timestamp)  {
        try {
            Date parsedDate = DateParser.parse(timestamp);
            timeStamp_date = parsedDate;
            DateFormat d_f = new SimpleDateFormat("MM/dd/yyyy hh:mm a");
            dateCreated = d_f.format(parsedDate);
        } catch (ParseException ignored) {
        }
    }

    public void parseDuration(String length)  {
        try {
            String l = Instant.ofEpochSecond(Long.parseLong(length)).toString();
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
            Date parsedDate = null;
            try {
                parsedDate = dateFormat.parse(l);
                DateFormat d_f = new SimpleDateFormat("HH:mm:ss");
                duration = (String) d_f.format(parsedDate);
            }
            catch (ParseException ignored) {
            }
        }
        catch (Exception ignored){

        }

    }
    public String getSid() {
        return sid;
    }

    public void setSid(String sid) {
        this.sid = sid;
    }
    public Date getTimeStamp_date() {
        return timeStamp_date;
    }

    public String getDateCreated() {
        return dateCreated;
    }

    public String getStatus() {
        return status;
    }

    public String getDirection() {
        return direction;
    }

    public String getDuration() {
        return duration;
    }

    public boolean isSelected() {
        return isSelected;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }

    public String getRemote() {
        return remote;
    }

    public void setRemote(String remote) {
        this.remote = remote;
    }

    public boolean isDeletePendingView() {
        return isDeletePendingView;
    }

    public void setDeletePendingView(boolean deletePendingView) {
        isDeletePendingView = deletePendingView;
    }

    public boolean isUnread() {
        return isUnread;
    }

    public void setUnread(boolean unread) {
        isUnread = unread;
    }

    public String getFrom() {
            return from;
    }

    public String getTo() {
        return to;
    }

    @Override
    public int compareTo(HistoryItem o) {
        return this.dateCreated.compareTo(o.dateCreated);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int i) {
        parcel.writeString(sid);
        parcel.writeString(proxySid);
        parcel.writeString(from);
        parcel.writeString(to);
        parcel.writeString(dateCreated);
        parcel.writeString(status);
        parcel.writeString(direction);
        parcel.writeString(duration);
        parcel.writeString(remote);
        parcel.writeByte((byte) (isUnread ? 1 : 0));
    }

    public static final Creator<HistoryItem> CREATOR = new Creator<HistoryItem>() {
        @Override
        public HistoryItem createFromParcel(Parcel in) {
            return new HistoryItem(in);
        }

        @Override
        public HistoryItem[] newArray(int size) {
            return new HistoryItem[size];
        }
    };

    @Override
    public ContentValues getContentValues() {
        ContentValues values = new ContentValues();
        values.put(TWILIO_SID, this.sid);
        values.put(PROXY_SID, this.proxySid);
        values.put(TO, this.to);
        values.put(FROM, this.from);
        values.put(DATE_CREATED, dateCreated);
        values.put(STATUS, this.status);
        values.put(DIRECTION, this.direction);
        values.put(DURATION, this.duration);
        values.put(REMOTE, this.remote);
        values.put(IS_UNREAD, this.isUnread ? 1 : 0);
        return values;
    }




    public static HistoryItem fromCursor(Cursor cursor) {
        return new HistoryItem(
                cursor.getString(cursor.getColumnIndexOrThrow(TWILIO_SID)),
                cursor.getString(cursor.getColumnIndexOrThrow(PROXY_SID)),
                cursor.getString(cursor.getColumnIndexOrThrow(TO)),
                cursor.getString(cursor.getColumnIndexOrThrow(FROM)),
                cursor.getString(cursor.getColumnIndexOrThrow(DATE_CREATED)),
                cursor.getString(cursor.getColumnIndexOrThrow(STATUS)),
                cursor.getString(cursor.getColumnIndexOrThrow(DIRECTION)),
                cursor.getString(cursor.getColumnIndexOrThrow(DURATION)),
                cursor.getString(cursor.getColumnIndexOrThrow(REMOTE)),
                cursor.getInt(cursor.getColumnIndexOrThrow(IS_UNREAD)) == 1
        );
    }

    public String getProxySid() {
        return proxySid;
    }

    public static boolean isMissedInbound(String direction, String status) {
        if (direction == null || status == null) {
            return false;
        }
        if (!"inbound".equalsIgnoreCase(direction)) {
            return false;
        }
        return "no-answer".equalsIgnoreCase(status)
                || "missed".equalsIgnoreCase(status)
                || "failed".equalsIgnoreCase(status);
    }
}

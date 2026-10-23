package com.glaciersecurity.glaciermessenger.entities;

import android.content.ContentValues;
import android.database.Cursor;


import com.glaciersecurity.glaciermessenger.utils.DateParser;
import com.google.gson.internal.LinkedTreeMap;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.TimeZone;

public class VoicemailRecord extends AbstractEntity implements Comparable<VoicemailRecord>{
    public static final String TABLENAME = "voicemail_records";
    public static final String DURATION ="duration";
    public static final String CALL_SID ="callsid";
    public static final String TIME = "time";
    public static final String FROM = "from_number";
    public static final String RECORDING_SID = "recordingsid";
    public static final String TO = "to_number";
    public static final String URL = "url";
    public static final String STATUS = "status";

    protected String duration;
    protected String callSid;
    protected String time;
    protected String from;
    protected String recordingSid;
    protected String to;
    protected String url;
    protected Date timeStamp_date;
    protected String timeStamp_string;
    protected VM_Status status;
    protected boolean isSelected;
    protected boolean isDeletePendingView;

    public VoicemailRecord(String duration, String callSid, String time, String from, String recordingSid, String to, String status, String url) {
        this.duration = duration;
        this.callSid = callSid;
        this.time = time;
        this.from = from;
        this.recordingSid = recordingSid;
        this.to = to;
        this.url = url;
        setStatus(status);
        parseDuration(duration);
        parseTimestamp(time);
    }

    public VoicemailRecord(LinkedTreeMap<String, String> linkedTreeMap) {
        this.duration = linkedTreeMap.get("duration");
        this.callSid = linkedTreeMap.get("callsid");
        this.time = linkedTreeMap.get("time");
        this.from = linkedTreeMap.get("from");
        this.recordingSid = linkedTreeMap.get("recordingsid");
        this.to = linkedTreeMap.get("to");
        this.url = linkedTreeMap.get("url");
        setStatus(linkedTreeMap.get("status"));
        parseDuration(duration);
        parseTimestamp(time);

    }

    public static VoicemailRecord fromCursor(Cursor cursor) {
        final int durationIndex = cursor.getColumnIndex(VoicemailRecord.DURATION);
        final int callSidIndex = cursor.getColumnIndex(VoicemailRecord.CALL_SID);
        final int timeIndex = cursor.getColumnIndex(VoicemailRecord.TIME);
        final int fromIndex = cursor.getColumnIndex(VoicemailRecord.FROM);
        final int recordingSidIndex = cursor.getColumnIndex(VoicemailRecord.RECORDING_SID);
        final int toIndex = cursor.getColumnIndex(VoicemailRecord.TO);
        final int statusIndex = cursor.getColumnIndex(VoicemailRecord.STATUS);
        final int urlIndex = cursor.getColumnIndex(VoicemailRecord.URL);
        return new VoicemailRecord(
                cursor.getString(durationIndex),
                cursor.getString(callSidIndex),
        cursor.getString(timeIndex),
        cursor.getString(fromIndex),
        cursor.getString(recordingSidIndex),
        cursor.getString(toIndex),
        cursor.getString(statusIndex),
                cursor.getString(urlIndex));

    }



        @Override
    public ContentValues getContentValues() {
        ContentValues values = new ContentValues();
        values.put(DURATION, this.duration);
        values.put(CALL_SID, this.callSid);
        values.put(TIME, this.time);
        values.put(FROM, this.from);
        values.put(RECORDING_SID, this.recordingSid);
        values.put(TO, this.to);
        values.put(URL, this.url);
        values.put(STATUS, this.status.label);
        return values;
    }

    public enum VM_Status{
        NEW("new"), READ("read"), DELETED("deleted");

        public final String label;

        VM_Status(String label) {
            this.label = label;
        }
    }

    public void setStatus(String statusString) {
        VM_Status  mStatus = VM_Status.NEW;

        if (statusString == null){
            this.status = mStatus;
            return;
        }

        switch (statusString){
            case "read":
                mStatus = VM_Status.READ;
                break;
            case "deleted":
                mStatus = VM_Status.DELETED;
                break;
            case "new":
            case "":
            default:
                mStatus = VM_Status.NEW;
                break;

        }
        this.status = mStatus;
    }


    public void parseTimestamp(String timestamp)  {
        timeStamp_string = timestamp;
        try {
            Date parsedDate = DateParser.parse(timestamp);
            timeStamp_date = parsedDate;
            DateFormat d_f = new SimpleDateFormat("MM/dd/yyyy hh:mm a");
            d_f.setTimeZone(TimeZone.getDefault());
            time = d_f.format(parsedDate);
        } catch (ParseException e) {
            timeStamp_date = null;
            time = timestamp;
        }
    }

    public void parseDuration(String length)  {
        try {
            String l = Instant.ofEpochSecond(Long.parseLong(length)).toString();
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
            Date parsedDate = null;
            try {
                parsedDate = dateFormat.parse(l);
                DateFormat d_f = new SimpleDateFormat("mm:ss");
                duration = (String) d_f.format(parsedDate);
            }
            catch (ParseException e) {
            }
        }
        catch (Exception e){

        }

    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public String getCallSid() {
        return callSid;
    }

    public void setCallSid(String callSid) {
        this.callSid = callSid;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        parseTimestamp(time);
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getRecordingSid() {
        return recordingSid;
    }

    public void setRecordingSid(String recordingSid) {
        this.recordingSid = recordingSid;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public Date getTimeStamp_date() {
        return timeStamp_date;
    }

    public void setTimeStamp_date(Date timeStamp_date) {
        this.timeStamp_date = timeStamp_date;
    }

    public String getTimeStamp_string() {
        return timeStamp_string;
    }

    public void setTimeStamp_string(String timeStamp_string) {
        this.timeStamp_string = timeStamp_string;
    }

    public String getUrl() {
        return url;
    }

    public VM_Status getStatus() {
        return status;
    }

    public void setStatus(VM_Status status) {
        this.status = status;
    }

    public boolean isSelected() {
        return isSelected;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }

    public boolean isDeletePendingView() {
        return isDeletePendingView;
    }

    public void setDeletePendingView(boolean deletePendingView) {
        isDeletePendingView = deletePendingView;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VoicemailRecord that = (VoicemailRecord) o;
        return Objects.equals(recordingSid, that.recordingSid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recordingSid);
    }

    @Override
    public int compareTo(VoicemailRecord o) {
        if (timeStamp_date != null && o.timeStamp_date != null) {
            return timeStamp_date.compareTo(o.timeStamp_date);
        }

        if (timeStamp_date == null && o.timeStamp_date == null) {
            if (time == null && o.time == null) {
                return 0;
            } else if (time == null) {
                return -1;
            } else if (o.time == null) {
                return 1;
            }
            return time.compareTo(o.time);
        }

        return timeStamp_date == null ? -1 : 1;
    }
}

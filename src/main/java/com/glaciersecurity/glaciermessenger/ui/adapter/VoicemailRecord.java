package com.glaciersecurity.glaciermessenger.ui.adapter;




import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.TimeZone;

public class VoicemailRecord implements Comparable<VoicemailRecord>{
    protected String duration;
    protected String callSid;
    protected String time;
    protected String from;
    protected String recordingSid;
    protected String to;
    protected Date timeStamp_date;
    protected String timeStamp_string;
    protected VM_Status status;
    protected boolean isSelected;
    protected boolean isDeletePendingView;

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

    public VoicemailRecord(String duration, String callSid, String time, String from, String recordingSid, String to, String status) {
        this.duration = duration;
        this.callSid = callSid;
        this.time = time;
        this.from = from;
        this.recordingSid = recordingSid;
        this.to = to;
        setStatus(status);
        parseDuration(duration);
        parseTimestamp(time);

    }

    public void parseTimestamp(String timestamp)  {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date parsedDate = null;
        try {
            parsedDate = dateFormat.parse(timestamp);
            DateFormat d_f = new SimpleDateFormat("MM/dd/yyyy hh:mm a");
            time = (String) d_f.format(parsedDate);
        } catch (ParseException e) {
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
        this.time = time;
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
        int compareVal = time.compareTo(o.time);
        if (compareVal >= 1) {
            return 1;
        }
        else if (compareVal <= -1) {
            return -1;
        }
        else {
            return 0;
        }
    }
}

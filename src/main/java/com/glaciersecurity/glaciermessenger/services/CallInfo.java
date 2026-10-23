package com.glaciersecurity.glaciermessenger.services;

import com.google.gson.internal.LinkedTreeMap;


public class CallInfo {
    private String to;
    private String toFormatted;
    private String from;
    private String fromFormatted;
    private String phoneNumberSid;
    private String direction;
    private String callerName;

    public CallInfo(LinkedTreeMap<Object, Object> data) {
        this.to = (String) data.get("to");
        this.toFormatted = (String) data.get("toFormatted");
        this.from = (String) data.get("from");
        this.fromFormatted = (String) data.get("fromFormatted");
        this.phoneNumberSid = (String) data.get("phoneNumberSid");
        this.direction = (String) data.get("direction");
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getToFormatted() {
        return toFormatted;
    }

    public void setToFormatted(String toFormatted) {
        this.toFormatted = toFormatted;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getFromFormatted() {
        return fromFormatted;
    }

    public void setFromFormatted(String fromFormatted) {
        this.fromFormatted = fromFormatted;
    }

    public String getCallerName() {
        return callerName;
    }

    public void setCallerName(String callerName) {
        this.callerName = callerName;
    }

    public String getProxy() {
        if (direction.equals("inbound")){
            return to;
        } else {
            return from;
        }
    }

    public String getRemote() {
        if (direction.equals("inbound")){
            return from;
        } else {
            return to;
        }
    }
}

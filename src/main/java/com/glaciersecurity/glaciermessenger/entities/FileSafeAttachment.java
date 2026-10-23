package com.glaciersecurity.glaciermessenger.entities;

import com.glaciersecurity.glaciermessenger.ui.util.Attachment;

import java.util.Objects;

public class FileSafeAttachment {
    public enum UploadStatus {
        SUCCESS, FAILED, INIT, IN_PROGRESS
    }
    private UploadStatus uploadStatus;
    private Attachment attachment;

    public FileSafeAttachment(Attachment attachment, UploadStatus uploadStatus){
        this.attachment = attachment;
        this.uploadStatus = uploadStatus;
    }

    public Attachment getAttachment() {
        return attachment;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileSafeAttachment that)) return false;
        return Objects.equals(attachment, that.attachment);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(attachment);
    }

    public void setAttachment(Attachment attachment) {
        this.attachment = attachment;
    }

    public UploadStatus getUploadStatus() {
        return uploadStatus;
    }

    public void setUploadStatus(UploadStatus uploadStatus) {
        this.uploadStatus = uploadStatus;
    }
}

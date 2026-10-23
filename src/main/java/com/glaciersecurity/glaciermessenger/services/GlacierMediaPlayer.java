package com.glaciersecurity.glaciermessenger.services;

public class GlacierMediaPlayer extends android.media.MediaPlayer {

    private int streamType;

    @Override
    public void setAudioStreamType(int streamType) {
        this.streamType = streamType;
        super.setAudioStreamType(streamType);
    }

    public int getAudioStreamType() {
        return streamType;
    }
}

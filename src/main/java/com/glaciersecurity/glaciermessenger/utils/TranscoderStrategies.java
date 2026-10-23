package com.glaciersecurity.glaciermessenger.utils;

import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy;
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy;

public final class TranscoderStrategies {

    public static final DefaultVideoStrategy VIDEO_720P = DefaultVideoStrategy.atMost(720)
            .bitRate(2L * 1000 * 1000)
            .frameRate(30)
            .keyFrameInterval(3F)
            .build();
    
    public static final DefaultVideoStrategy VIDEO_360P = DefaultVideoStrategy.atMost(360)
            .bitRate(1000 * 1000)
            .frameRate(30)
            .keyFrameInterval(3F)
            .build();


    public static final DefaultAudioStrategy AUDIO_HQ = DefaultAudioStrategy.builder()
            .bitRate(192 * 1000)
            .channels(2)
            .sampleRate(DefaultAudioStrategy.SAMPLE_RATE_AS_INPUT)
            .build();

    public static final DefaultAudioStrategy AUDIO_MQ = DefaultAudioStrategy.builder()
            .bitRate(128 * 1000)
            .channels(2)
            .sampleRate(DefaultAudioStrategy.SAMPLE_RATE_AS_INPUT)
            .build();


    private TranscoderStrategies() {
        throw new IllegalStateException("Do not instantiate me");
    }

}

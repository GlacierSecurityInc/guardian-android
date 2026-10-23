package com.glaciersecurity.glaciermessenger.services;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.emoji2.text.DefaultEmojiCompatConfig;
import androidx.emoji2.text.EmojiCompat;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.utils.Log;

public class EmojiInitializationService {

    public static void execute(final Context context) {
        final EmojiCompat.Config emojiConfig = DefaultEmojiCompatConfig.create(context);
        if (emojiConfig == null) {
            Log.w(Config.LOGTAG, "no system emoji font provider available");
            return;
        }
        EmojiCompat.init(emojiConfig.setReplaceAll(true))
                .registerInitCallback(
                        new EmojiCompat.InitCallback() {
                            @Override
                            public void onInitialized() {
                                super.onInitialized();
                            }

                            @Override
                            public void onFailed(@Nullable Throwable throwable) {
                                Log.e(Config.LOGTAG, "failed to initialize EmojiCompat", throwable);
                                super.onFailed(throwable);
                            }
                        });
    }
}

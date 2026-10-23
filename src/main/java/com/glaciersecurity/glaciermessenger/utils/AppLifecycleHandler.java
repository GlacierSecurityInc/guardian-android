package com.glaciersecurity.glaciermessenger.utils;

import android.app.Application;
import android.content.Intent;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class AppLifecycleHandler implements LifecycleObserver {
    private static volatile boolean isAppInForeground = false;
    private final Application app;

    public AppLifecycleHandler(Application application) {
        this.app = application;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void onEnterForeground() {
        isAppInForeground = true;

        Intent intent = new Intent("APP_FOREGROUNDED");
        LocalBroadcastManager.getInstance(app).sendBroadcast(intent);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void onEnterBackground() {
        isAppInForeground = false;
    }

    public static boolean isAppInForeground() {
        return isAppInForeground;
    }

}

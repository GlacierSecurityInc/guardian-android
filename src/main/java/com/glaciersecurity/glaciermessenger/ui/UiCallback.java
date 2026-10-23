package com.glaciersecurity.glaciermessenger.ui;

import android.app.PendingIntent;

public interface UiCallback<T> {
	void success(T object);

	void error(int errorCode);

	void userInputRequired(PendingIntent pi);
}

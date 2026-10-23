package com.glaciersecurity.glaciermessenger.entities;

import android.content.Context;

import com.glaciersecurity.glaciermessenger.services.AvatarService;

import java.util.ArrayList;


public interface NativeListItem extends Comparable<NativeListItem>, AvatarService.Avatarable {
	String getDisplayName();

	String getPrimaryNumber();

	PhoneNumberEntry getPrimaryNumberEntry();


	ArrayList<PhoneNumberEntry> getNumbers();

	boolean match(Context context, final String needle);
}

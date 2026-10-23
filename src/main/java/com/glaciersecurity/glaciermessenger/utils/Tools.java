package com.glaciersecurity.glaciermessenger.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class Tools {
    private static final String ALLOWED_CHARACTERS ="0123456789qwertyuiopasdfghjklzxcvbnmABCDEFGHIJKLMNOPQRSTUVWXYZ?!@";

    public static String getRandomString(final int sizeOfRandomString)
    {
        final java.util.Random random=new Random();
        final StringBuilder sb=new StringBuilder(sizeOfRandomString);
        for(int i=0;i<sizeOfRandomString;++i)
            sb.append(ALLOWED_CHARACTERS.charAt(random.nextInt(ALLOWED_CHARACTERS.length())));
        return sb.toString();
    }

    public static <T> ArrayList<T> removeDuplicates(List<T> list)
    {
        Set<T> set = new LinkedHashSet<>();
        if (list != null) {
            set.addAll(list);
        }
        return new ArrayList<>(set);
    }

    public static void copyToClipboard(Context context, String data, String label) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, data);
        clipboard.setPrimaryClip(clip);
    }
}

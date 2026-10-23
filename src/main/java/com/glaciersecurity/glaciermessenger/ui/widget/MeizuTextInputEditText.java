package com.glaciersecurity.glaciermessenger.ui.widget;


import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.TextView;

import java.lang.reflect.Field;

/**
 * A wrapper class to fix some weird fuck ups on Meizu devices
 * credit goes to the people in this thread https://github.com/android-in-china/Compatibility/issues/11
 */
public class MeizuTextInputEditText extends com.google.android.material.textfield.TextInputEditText {

    public MeizuTextInputEditText(Context context) {
        super(context);
    }

    public MeizuTextInputEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MeizuTextInputEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public CharSequence getHint() {
        String manufacturer = Build.MANUFACTURER.toUpperCase();
        if (!manufacturer.contains("MEIZU") || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return super.getHint();
        }else{
            try {
                return getSuperHintHack();
            }catch (Exception e){
                return super.getHint();
            }
        }
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private CharSequence getSuperHintHack() throws NoSuchFieldException, IllegalAccessException {
        Field hintField = TextView.class.getDeclaredField("mHint");
        hintField.setAccessible(true);
        return (CharSequence) hintField.get(this);
    }
}


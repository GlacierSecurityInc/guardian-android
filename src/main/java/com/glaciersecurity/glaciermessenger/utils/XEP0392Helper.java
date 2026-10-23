package com.glaciersecurity.glaciermessenger.utils;

import android.graphics.Color;

import org.hsluv.HsluvColorConverter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class XEP0392Helper {

    private static double angle(String nickname) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(nickname.getBytes(StandardCharsets.UTF_8));
            int angle = ((int) (digest[0]) & 0xff) + ((int) (digest[1]) & 0xff) * 256;
            return angle / 65536.;
        } catch (Exception e) {
            return 0.0;
        }
    }

    public static int rgbFromNick(String name) {
        HsluvColorConverter c = new HsluvColorConverter();

        c.hsluv_h = angle(name) * 360.0; // 0..360
        c.hsluv_s = 100.0;               // 0..100
        c.hsluv_l = 50.0;                // 0..100

        c.hsluvToRgb();                  // fills rgb_r/g/b in 0..1

        int r = (int) Math.round(c.rgb_r * 255.0);
        int g = (int) Math.round(c.rgb_g * 255.0);
        int b = (int) Math.round(c.rgb_b * 255.0);

        // Optional safety clamp (keeps Color.rgb happy if rounding hits 256)
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));

        return Color.rgb(r, g, b);
    }
}

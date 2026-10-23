/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.glaciersecurity.glaciermessenger.utils;

public class PhoneUtils {

    // Default country code applied to bare, country-code-less numbers (North America).
    private static final String DEFAULT_COUNTRY_CODE = "+1";

    /**
     * Removes the visual formatting characters ((), spaces, dashes) from a number
     * without touching any leading "+" or country code.
     */
    private static String removeSeparators(String number) {
        if (number == null) {
            return null;
        }
        return number.replace("(", "").replace(")", "").replace(" ", "").replace("-", "");
    }

    /**
     * Returns true when a number already carries an explicit country code, i.e. it
     * is in E.164 form ("+44...") or uses the international access prefix ("0044...").
     * Such numbers must never have the default ("+1") country code applied to them.
     */
    private static boolean hasExplicitCountryCode(String cleaned) {
        return cleaned != null && (cleaned.startsWith("+") || cleaned.startsWith("00"));
    }

    /**
     * Normalizes a dialled number to E.164. Numbers that already specify a country
     * code (leading "+" or "00") are preserved as-is; only bare numbers fall back to
     * the default country code.
     */
    public static String getPhoneNumber(String number) {
        if (number == null) {
            return null;
        }
        String cleaned = removeSeparators(number);
        if (cleaned.startsWith("+")) {
            return cleaned;
        }
        if (cleaned.startsWith("00")) {
            return cleaned.replaceFirst("00", "+");
        }
        String digits = cleaned.replaceAll("[^\\d]", "");
        if (digits.length() == 10) {
            return DEFAULT_COUNTRY_CODE + digits;
        }
        if (digits.length() == 11 && digits.startsWith("1")) {
            return "+" + digits;
        }
        return "+" + digits;
    }

    public static String getStripPhoneNumber(String number) {
        if (number == null){
            return null;
        }
        String cleaned = removeSeparators(number);
        // Strip only a *leading* default country code, never a "+1" appearing
        // mid-string (which a blind replace would wrongly delete) and never the
        // country code of an international number.
        if (cleaned.startsWith(DEFAULT_COUNTRY_CODE)) {
            return cleaned.substring(DEFAULT_COUNTRY_CODE.length());
        }
        return cleaned;
    }

    public static String getStripPhoneNumberWithCountryCode(String number) {
        try {
            if (number != null) {
                String cleaned = removeSeparators(number);
                if (hasExplicitCountryCode(cleaned)) {
                    return cleaned.startsWith("00") ? cleaned.replaceFirst("00", "+") : cleaned;
                }
                String digits = cleaned.replaceAll("[^\\d]", "");
                if (digits.length() == 10) {
                    return DEFAULT_COUNTRY_CODE + digits;
                }
                if (digits.length() == 11 && digits.startsWith("1")) {
                    return "+" + digits;
                }
                return cleaned;
            }
        }catch (Exception e){

        }
        return number;
    }

    public static String reformatNumber(String number){
        try {
            String cleaned = removeSeparators(number);
            // International (non-North-American) numbers are left in their original
            // form; the "+1 (xxx) xxx-xxxx" layout below only applies to US numbers.
            if (cleaned.startsWith("00")
                    || (cleaned.startsWith("+") && !cleaned.startsWith(DEFAULT_COUNTRY_CODE))) {
                return number;
            }
            String formattedNumber = number;
            String sub = getStripPhoneNumber(number);
            sub = sub.replaceAll("[^\\d]", "");
            try {
                if (sub.length() == 10) {
                    formattedNumber = DEFAULT_COUNTRY_CODE + " (" + sub.substring(0, 3) + ") " + sub.substring(3, 6) + "-" + sub.substring(6);
                }
            } catch (Exception e) {

            }
            return formattedNumber;
        } catch (Exception e){
            return number;
        }
    }
    public static String lastFourDigits(String number){
        String formattedNumber = number;
        try {
            formattedNumber = number.substring(number.length() - 4);
        } catch (Exception e){

        }
        return formattedNumber;
    }

    public static boolean areSameNumber(String number, String number2){
        if (number == null || number2 == null){
            return false;
        }
        return getStripPhoneNumber(number).equals(getStripPhoneNumber(number2));
    }


}


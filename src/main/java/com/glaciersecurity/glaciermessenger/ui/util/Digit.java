package com.glaciersecurity.glaciermessenger.ui.util;

/*
 * Copyright (c) 2010-2019 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import com.glaciersecurity.glaciermessenger.R;


@SuppressLint("AppCompatCustomView")
public class Digit extends Button implements AddressAware {
   private boolean mPlayDtmf;
   private AddressText mAddress;

   public Digit(Context context, AttributeSet attrs, int style) {
      super(context, attrs, style);
      init(context, attrs);
   }

   public Digit(Context context, AttributeSet attrs) {
      super(context, attrs);
      init(context, attrs);
   }

   public Digit(Context context) {
      super(context);
      setLongClickable(true);
   }

   private void init(Context context, AttributeSet attrs) {
      TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.Numpad);
      mPlayDtmf = 1 == a.getInt(R.styleable.Numpad_play_dtmf, 1);
      a.recycle();

      setLongClickable(true);
   }

   @Override
   protected void onTextChanged(CharSequence text, int start, int before, int after) {
      super.onTextChanged(text, start, before, after);

      if (text == null || text.length() < 1) {
         return;
      }

      DialKeyListener lListener = new DialKeyListener();
      setOnClickListener(lListener);
      setOnTouchListener(lListener);

      if ("0+".equals(text.toString())) {
         setOnLongClickListener(lListener);
      }

      if ("1".equals(text.toString())) {
         setOnLongClickListener(lListener);
      }
   }

   @Override
   public void setAddressWidget(AddressText address) {
      mAddress = address;
   }

   private class DialKeyListener implements OnClickListener, OnTouchListener, OnLongClickListener {
      final char mKeyCode;
      boolean mIsDtmfStarted;

      DialKeyListener() {
         mKeyCode = Digit.this.getText().subSequence(0, 1).charAt(0);
      }


      @Override
      public void onClick(View v) {
         AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
         int volume_level= am.getStreamVolume(AudioManager.STREAM_RING); // Highest Ring volume level is 7, lowest is 0
         final ToneGenerator mToneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, volume_level * 14); // Raising volume to 100% (For eg. 7 * 14 ~ 100)
         mToneGenerator.stopTone();

         int toneGen;
         switch (mKeyCode){
            case '1': toneGen = ToneGenerator.TONE_DTMF_1; break;
            case '2': toneGen = ToneGenerator.TONE_DTMF_2; break;
            case '3': toneGen = ToneGenerator.TONE_DTMF_3; break;
            case '4': toneGen = ToneGenerator.TONE_DTMF_4; break;
            case '5': toneGen = ToneGenerator.TONE_DTMF_5; break;
            case '6': toneGen = ToneGenerator.TONE_DTMF_6; break;
            case '7': toneGen = ToneGenerator.TONE_DTMF_7; break;
            case '8': toneGen = ToneGenerator.TONE_DTMF_8; break;
            case '9': toneGen = ToneGenerator.TONE_DTMF_9; break;
            default: toneGen = ToneGenerator.TONE_DTMF_0; break;

         }
         mToneGenerator.startTone(toneGen, 100);

         if (mAddress != null) {

            int begin = mAddress.getSelectionStart();

            if (begin == -1) {
               begin = mAddress.length();
            }
            if (begin >= 0) {
               mAddress.getEditableText().insert(begin, String.valueOf(mKeyCode));
            }
         }
      }



      @Override
      public boolean onTouch(View v, MotionEvent event) {
         if (!mPlayDtmf) return false;

         if (event.getAction() == MotionEvent.ACTION_DOWN && !mIsDtmfStarted) {
            //Play sound
            mIsDtmfStarted = true;
         } else {
            if (event.getAction() == MotionEvent.ACTION_UP) {
            }
         }
         return false;
      }

      @Override
      public boolean onLongClick(View v) {
         int id = v.getId();

         if (mAddress == null) {
            return true;
         } else { // mAddress != null
               if (id == R.id.Digit1 ) {
                  mAddress.setText("");
                  return true;
               } else if (id == R.id.Digit00) {
                  int begin = mAddress.getSelectionStart();
                  if (begin == -1) {
                     begin = mAddress.getEditableText().length();
                  }
                  if (begin >= 0) {
                     mAddress.getEditableText().insert(begin, "+");
                  }
                  return true;
               }
               return true;
            }
      }

   }
}

package com.fsck.k9.activity;


import java.util.Locale;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import com.fsck.k9.K9;
import com.fsck.k9.helper.DateFormatter;
import com.fsck.k9.view.ToggleScrollView;


public class K9Activity extends Activity {
    private GestureDetector gestureDetector;

    protected ToggleScrollView mTopView;

    @Override
    public void onCreate(Bundle icicle) {
        onCreate(icicle, true);
    }

    public void onCreate(Bundle icicle, boolean useTheme) {
        setLanguage(this, K9.getK9Language());
        if (useTheme) {
            setTheme(K9.getK9Theme());
        }
        super.onCreate(icicle);
        setupFormats();

        // Gesture detection
        gestureDetector = new GestureDetector(new MyGestureDetector());

    }

    public static void setLanguage(Context context, String language) {
        Locale locale;
        if (language == null || language.equals("")) {
            locale = Locale.getDefault();
        } else if (language.length() == 5 && language.charAt(2) == '_') {
            // language is in the form: en_US
            locale = new Locale(language.substring(0, 2), language.substring(3));
        } else {
            locale = new Locale(language);
        }
        Configuration config = new Configuration();
        config.locale = locale;
        context.getResources().updateConfiguration(config,
                context.getResources().getDisplayMetrics());
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        super.dispatchTouchEvent(ev);
        return gestureDetector.onTouchEvent(ev);
    }

    @Override
    public void onResume() {
        super.onResume();
        setupFormats();
    }

    private java.text.DateFormat mDateFormat;
    private java.text.DateFormat mTimeFormat;

    private void setupFormats() {

        mDateFormat = DateFormatter.getDateFormat(this);
        mTimeFormat = android.text.format.DateFormat.getTimeFormat(this);   // 12/24 date format
    }

    public java.text.DateFormat getTimeFormat() {
        return mTimeFormat;
    }

    public java.text.DateFormat getDateFormat() {
        return mDateFormat;
    }

    /**
     * Called when a swipe from right to left is handled by {@link MyGestureDetector}.  See
     * {@link android.view.GestureDetector.OnGestureListener#onFling(android.view.MotionEvent, android.view.MotionEvent, float, float)}
     * for more information on the {@link MotionEvent}s being passed.
     * @param e1 First down motion event that started the fling.
     * @param e2 The move motion event that triggered the current onFling.
     */
    protected void onSwipeRightToLeft(final MotionEvent e1, final MotionEvent e2) {
    }

    /**
     * Called when a swipe from left to right is handled by {@link MyGestureDetector}.  See
     * {@link android.view.GestureDetector.OnGestureListener#onFling(android.view.MotionEvent, android.view.MotionEvent, float, float)}
     * for more information on the {@link MotionEvent}s being passed.
     * @param e1 First down motion event that started the fling.
     * @param e2 The move motion event that triggered the current onFling.
     */
    protected void onSwipeLeftToRight(final MotionEvent e1, final MotionEvent e2) {
    }

    protected Animation inFromRightAnimation() {
        return slideAnimation(0.0f, +1.0f);
    }

    protected Animation outToLeftAnimation() {
        return slideAnimation(0.0f, -1.0f);
    }

    private Animation slideAnimation(float right, float left) {

        Animation slide = new TranslateAnimation(
            Animation.RELATIVE_TO_PARENT,  right, Animation.RELATIVE_TO_PARENT,  left,
            Animation.RELATIVE_TO_PARENT,  0.0f, Animation.RELATIVE_TO_PARENT,   0.0f
        );
        slide.setDuration(125);
        slide.setFillBefore(true);
        slide.setInterpolator(new AccelerateInterpolator());
        return slide;
    }

    class MyGestureDetector extends SimpleOnGestureListener {
        private boolean gesturesEnabled = false;

        /**
         * Creates a new {@link android.view.GestureDetector.OnGestureListener}.  Enabled/disabled based upon
         * {@link com.fsck.k9.K9#gesturesEnabled()}}.
         */
        public MyGestureDetector() {
            super();
        }

        /**
         * Create a new {@link android.view.GestureDetector.OnGestureListener}.
         * @param gesturesEnabled Setting to <code>true</code> will enable gesture detection,
         * regardless of the system-wide gesture setting.
         */
        public MyGestureDetector(final boolean gesturesEnabled) {
            super();
            this.gesturesEnabled = gesturesEnabled;
        }

        private static final float SWIPE_MAX_OFF_PATH_DIP = 250f;
        private static final float SWIPE_THRESHOLD_VELOCITY_DIP = 325f;

        @Override
        public boolean onDoubleTap(MotionEvent ev) {
            super.onDoubleTap(ev);
            if (mTopView != null) {
                int height = getResources().getDisplayMetrics().heightPixels;
                if (ev.getRawY() < (height / 4)) {
                    mTopView.fullScroll(View.FOCUS_UP);

                } else if (ev.getRawY() > (height - height / 4)) {
                    mTopView.fullScroll(View.FOCUS_DOWN);

                }
            }
            return false;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            // Do fling-detection if gestures are force-enabled or we have system-wide gestures enabled.
            if (gesturesEnabled || K9.gesturesEnabled()) {
                // Calculate the minimum distance required for this to count as a swipe.
                // Convert the constant dips to pixels.
                final float mGestureScale = getResources().getDisplayMetrics().density;
                final int minVelocity = (int)(SWIPE_THRESHOLD_VELOCITY_DIP * mGestureScale + 0.5f);
                final int maxOffPath = (int)(SWIPE_MAX_OFF_PATH_DIP * mGestureScale + 0.5f);
                
                // Calculate how much was actually swiped.
                final float deltaX = e2.getX() - e1.getX();
                final float deltaY = e2.getY() - e1.getY();
                
                // Calculate the minimum distance required for this to be considered a swipe.
                final int minDistance = (int)Math.abs(deltaY * 4);

                try {
                    if (Math.abs(deltaY) > maxOffPath) {
                        return false;
                    }
                    if(Math.abs(velocityX) < minVelocity) {
                        return false;
                    }
                    // right to left swipe
                    if (deltaX < (minDistance * -1)) {
                        onSwipeRightToLeft(e1, e2);
                    } else if (deltaX > minDistance) {
                        onSwipeLeftToRight(e1, e2);
                    } else {
                        return false;
                    }
                } catch (Exception e) {
                    // nothing
                }
            }
            return false;
        }
    }
}

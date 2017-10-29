/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.fsck.k9.view;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import timber.log.Timber;
import android.webkit.WebView;

import com.fsck.k9.Clock;
import com.fsck.k9.K9;
import com.fsck.k9.Throttle;
import com.fsck.k9.helper.Utility;

/**
 * A custom WebView that is robust to rapid resize events in sequence.
 *
 * This is useful for a WebView which needs to have a layout of {@code WRAP_CONTENT}, since any
 * contents with percent-based height will force the WebView to infinitely expand (or shrink).
 */
public class RigidWebView extends WebView {
    private static final boolean NO_THROTTLE = Build.VERSION.SDK_INT >= 21; //Build.VERSION_CODES.LOLLIPOP

    public RigidWebView(Context context) {
        super(context);
    }
    public RigidWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public RigidWebView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    private static final int MIN_RESIZE_INTERVAL = 200;
    private static final int MAX_RESIZE_INTERVAL = 300;
    private final Clock clock = Clock.INSTANCE;

    private final Throttle throttle = new Throttle(getClass().getName(),
            new Runnable() {
                @Override public void run() {
                    performSizeChangeDelayed();
                }
            }, Utility.getMainThreadHandler(),
            MIN_RESIZE_INTERVAL, MAX_RESIZE_INTERVAL);

    private int realWidth;
    private int realHeight;
    private boolean ignoreNext;
    private long lastSizeChangeTime = -1;

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (NO_THROTTLE) {
            super.onSizeChanged(w, h, ow, oh);
            return;
        }

        realWidth = w;
        realHeight = h;

        long now = clock.getTime();
        boolean recentlySized = (now - lastSizeChangeTime < MIN_RESIZE_INTERVAL);

        // It's known that the previous resize event may cause a resize event immediately. If
        // this happens sufficiently close to the last resize event, drop it on the floor.
        if (ignoreNext) {
            ignoreNext = false;
            if (recentlySized) {
                Timber.w("Supressing size change in RigidWebView");
                return;
            }
        }

        if (recentlySized) {
            throttle.onEvent();
        } else {
            // It's been a sufficiently long time - just perform the resize as normal. This should
            // be the normal code path.
            performSizeChange(ow, oh);
        }
    }

    private void performSizeChange(int ow, int oh) {
        super.onSizeChanged(realWidth, realHeight, ow, oh);
        lastSizeChangeTime = clock.getTime();
    }

    private void performSizeChangeDelayed() {
        ignoreNext = true;
        performSizeChange(getWidth(), getHeight());
    }
}

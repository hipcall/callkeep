/*
 * Copyright (c) 2016-2019 The CallKeep Authors (see the AUTHORS file)
 * SPDX-License-Identifier: ISC, MIT
 *
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package io.wazo.callkeep;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;

public class CallKeepBackgroundMessagingService extends Service {
  private static final String TAG = "FLT:CallKeepService";
  private static @Nullable PowerManager.WakeLock sWakeLock;

  // Timeout for wake lock: 60 seconds (sufficient for app wakeup + call setup)
  // This prevents battery drain if service crashes or fails to release
  private static final long WAKELOCK_TIMEOUT_MS = 60 * 1000; // 60 seconds

  /**
   * Acquire a wake lock to ensure the device doesn't go to sleep while processing background tasks.
   * Wake lock will automatically release after 60 seconds to prevent battery drain.
   */
  public static void acquireWakeLockNow(Context context) {
    // Release any existing wake lock first to avoid leaks
    if (sWakeLock != null && sWakeLock.isHeld()) {
      Log.d(TAG, "Releasing existing wake lock before acquiring new one");
      try {
        sWakeLock.release();
      } catch (RuntimeException e) {
        Log.e(TAG, "Error releasing existing wake lock", e);
      }
      sWakeLock = null;
    }

    if (sWakeLock == null || !sWakeLock.isHeld()) {
      PowerManager powerManager = (PowerManager) context.getSystemService(POWER_SERVICE);
      sWakeLock =
              powerManager.newWakeLock(
                      PowerManager.PARTIAL_WAKE_LOCK, CallKeepBackgroundMessagingService.class.getCanonicalName());
      sWakeLock.setReferenceCounted(false);
      // Acquire with timeout to prevent battery drain
      sWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
      Log.d(TAG, "Wake lock acquired with " + (WAKELOCK_TIMEOUT_MS / 1000) + "s timeout");
    }
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    Log.d(TAG, "wakeUpApplication: " + intent.getStringExtra(CallKeepConstants.EXTRA_CALL_UUID) +
            ", number : " + intent.getStringExtra(CallKeepConstants.EXTRA_CALL_NUMBER) +
            ", displayName:" + intent.getStringExtra(CallKeepConstants.EXTRA_CALLER_NAME));
    //TODO: not implemented
    return null;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    // Release wake lock when service is destroyed
    if (sWakeLock != null && sWakeLock.isHeld()) {
      Log.d(TAG, "Releasing wake lock in onDestroy()");
      try {
        sWakeLock.release();
      } catch (RuntimeException e) {
        // Catch potential exceptions if wake lock was already released or is in invalid state
        Log.e(TAG, "Error releasing wake lock in onDestroy()", e);
      }
      sWakeLock = null;
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Log.d(TAG, "onStartCommand: waking up application for call");
    // Service should not be restarted if killed by system
    // The wake lock will auto-release after timeout anyway
    return START_NOT_STICKY;
  }
}

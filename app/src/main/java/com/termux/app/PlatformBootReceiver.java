package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;

public final class PlatformBootReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "PlatformBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH).isDirectory()) return;
        try {
            ContextCompat.startForegroundService(context, PlatformRuntime.startIntent(context));
        } catch (RuntimeException error) {
            Logger.logStackTraceWithMessage(
                LOG_TAG,
                "Android prevented Nix Ship restart after boot; open the app to resume hosting",
                error
            );
        }
    }
}

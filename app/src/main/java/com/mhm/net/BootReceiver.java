package com.mhm.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts blocking and the auto-switch job after the phone reboots or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    public static final String ACTION_WAKE = "com.mhm.net.FW_WAKE";

    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            GroupStore.cleanProtected(c);
            if (!FirewallStore.load(c).isEmpty()) FirewallVpnService.refresh(c);
            SwitchJob.sync(c);
        } catch (Exception ignored) {}
    }
}

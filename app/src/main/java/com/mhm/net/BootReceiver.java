package com.mhm.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts blocking and auto-switch after the phone reboots or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            if (!FirewallStore.load(c).isEmpty()) FirewallVpnService.refresh(c);
            if (Prefs.autoSwitch(c)) MonitorService.start(c);
        } catch (Exception ignored) {}
    }
}

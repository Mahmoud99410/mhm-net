package com.mhm.net;

import android.content.Context;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

public final class NetUtil {
    private NetUtil() {}

    public static WifiManager wifi(Context c) {
        return (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    public static ConnectivityManager cm(Context c) {
        return (ConnectivityManager) c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /** The current Wi-Fi network (not the VPN), or null. */
    public static Network wifiNetwork(Context c) {
        ConnectivityManager cm = cm(c);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return n;
            }
        }
        return null;
    }

    public static boolean wifiValidated(Context c) {
        Network n = wifiNetwork(c);
        if (n == null) return false;
        NetworkCapabilities nc = cm(c).getNetworkCapabilities(n);
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /** Name of the connected Wi-Fi without quotes, or null (needs location permission + GPS on). */
    public static String currentSsid(Context c) {
        try {
            WifiInfo i = wifi(c).getConnectionInfo();
            if (i == null) return null;
            String s = i.getSSID();
            if (s == null || s.contains("unknown ssid") || s.equals("0x")) return null;
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    public static String activeLabel(Context c) {
        ConnectivityManager cm = cm(c);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
        if (nc == null) return "غير متصل";
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            String s = currentSsid(c);
            return "متصل عبر الواي فاي: " + (s != null ? s : "(فعّل الموقع لمعرفة الاسم)");
        }
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "متصل عبر بيانات الهاتف";
        return "متصل";
    }

    public static boolean locationEnabled(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
                return lm != null && lm.isLocationEnabled();
            }
            return Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.LOCATION_MODE, 0) != 0;
        } catch (Exception e) {
            return true;
        }
    }
}

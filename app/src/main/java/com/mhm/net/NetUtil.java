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

    private static Network find(Context c, int transport) {
        ConnectivityManager cm = cm(c);
        Network fallback = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !nc.hasTransport(transport)) continue;
            if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return n;
            if (fallback == null) fallback = n;
        }
        return fallback;
    }

    /** The current Wi-Fi network (never the VPN), or null. */
    public static Network wifiNetwork(Context c) { return find(c, NetworkCapabilities.TRANSPORT_WIFI); }

    public static Network cellularNetwork(Context c) { return find(c, NetworkCapabilities.TRANSPORT_CELLULAR); }

    /** The network the phone uses for internet right now (Wi-Fi first, then mobile data). */
    public static Network internetNetwork(Context c) {
        Network w = wifiNetwork(c);
        return w != null ? w : cellularNetwork(c);
    }

    public static boolean isWifi(Context c, Network n) {
        if (n == null) return false;
        NetworkCapabilities nc = cm(c).getNetworkCapabilities(n);
        return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
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

    /** Link rate between the phone and the router in Mbps (no data used), or -1. */
    public static int wifiLinkMbps(Context c) {
        try {
            if (wifiNetwork(c) == null) return -1;
            WifiInfo i = wifi(c).getConnectionInfo();
            return i == null ? -1 : i.getLinkSpeed();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Android's own estimate of the mobile-data bandwidth (kbps), or -1. */
    public static int[] cellularEstimateKbps(Context c) {
        Network n = cellularNetwork(c);
        if (n == null) return null;
        NetworkCapabilities nc = cm(c).getNetworkCapabilities(n);
        if (nc == null) return null;
        return new int[]{nc.getLinkDownstreamBandwidthKbps(), nc.getLinkUpstreamBandwidthKbps()};
    }

    public static String activeLabel(Context c) {
        if (wifiNetwork(c) != null) {
            String s = currentSsid(c);
            return "واي فاي: " + (s != null ? s : "(فعّل الموقع لمعرفة الاسم)");
        }
        if (cellularNetwork(c) != null) return "بيانات الهاتف";
        return "غير متصل";
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

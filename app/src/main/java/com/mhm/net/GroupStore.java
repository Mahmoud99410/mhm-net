package com.mhm.net;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Saved groups of apps that are blocked / unblocked together with one tap. */
public final class GroupStore {
    private GroupStore() {}

    public static class Group {
        public String id;
        public String name;
        public List<String> pkgs = new ArrayList<>();
    }

    /** Well-known social / video apps used for smart detection. */
    static final String[] SOCIAL = {
            "com.facebook.katana", "com.facebook.lite", "com.facebook.orca", "com.facebook.mlite",
            "com.instagram.android", "com.instagram.lite", "com.instagram.barcelona",
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go",
            "com.snapchat.android", "com.twitter.android", "com.twitter.android.lite",
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
            "com.google.android.youtube", "app.revanced.android.youtube", "com.reddit.frontpage",
            "com.linkedin.android", "com.pinterest", "com.discord", "com.viber.voip",
            "jp.naver.line.android", "com.tumblr", "com.bereal.ft", "com.imo.android.imoim",
            "com.kwai.video", "com.likee.video", "video.like", "com.vkontakte.android"
    };

    /** Apps that must never be blocked (blocking them breaks notifications / updates of other apps). */
    static final String[] PROTECTED = {
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
            "com.google.android.gms.policy_sidecar_aps", "com.google.android.ext.services",
            "com.android.providers.downloads", "com.android.phone", "com.android.systemui",
            "com.android.settings", "android"
    };

    public static boolean isProtected(String pkg) {
        if (pkg == null) return true;
        for (String p : PROTECTED) if (p.equals(pkg)) return true;
        return pkg.startsWith("com.google.android.gms.");
    }

    /** Removes protected apps from saved groups and from the block rules (fixes older versions). */
    public static synchronized void cleanProtected(Context c) {
        if (Prefs.p(c).getString("groups", null) != null) {
            List<Group> all = load(c);
            boolean changed = false;
            for (Group g : all) {
                Iterator<String> it = g.pkgs.iterator();
                while (it.hasNext()) if (isProtected(it.next())) { it.remove(); changed = true; }
            }
            if (changed) save(c, all);
        }
        for (FirewallStore.Rule r : FirewallStore.load(c)) {
            if (isProtected(r.pkg)) FirewallStore.remove(c, r.pkg);
        }
    }

    /** Installed social apps: known package names + launchable apps Android itself categorises as "social". */
    public static List<String> detectSocial(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> out = new LinkedHashSet<>();
        for (String p : SOCIAL) {
            try { pm.getApplicationInfo(p, 0); out.add(p); } catch (PackageManager.NameNotFoundException ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 26) {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                String p = ai.packageName;
                if (ai.category == ApplicationInfo.CATEGORY_SOCIAL
                        && (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                        && !p.equals(c.getPackageName())
                        && !isProtected(p)
                        && !(p.startsWith("com.google.") && !p.equals("com.google.android.youtube"))
                        && pm.getLaunchIntentForPackage(p) != null) {
                    out.add(p);
                }
            }
        }
        return new ArrayList<>(out);
    }

    public static synchronized List<Group> load(Context c) {
        List<Group> out = new ArrayList<>();
        String raw = Prefs.p(c).getString("groups", null);
        if (raw == null) {
            // first run: create the smart social-media group automatically
            Group g = new Group();
            g.id = "social";
            g.name = "مواقع التواصل";
            g.pkgs = detectSocial(c);
            out.add(g);
            save(c, out);
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Group g = new Group();
                g.id = o.getString("id");
                g.name = o.getString("name");
                JSONArray p = o.getJSONArray("pkgs");
                for (int k = 0; k < p.length(); k++) {
                    String pkg = p.getString(k);
                    if (!isProtected(pkg)) g.pkgs.add(pkg);
                }
                out.add(g);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static synchronized void save(Context c, List<Group> groups) {
        JSONArray a = new JSONArray();
        try {
            for (Group g : groups) {
                JSONObject o = new JSONObject();
                o.put("id", g.id);
                o.put("name", g.name);
                JSONArray p = new JSONArray();
                for (String s : g.pkgs) p.put(s);
                o.put("pkgs", p);
                a.put(o);
            }
        } catch (Exception ignored) {}
        Prefs.p(c).edit().putString("groups", a.toString()).apply();
    }

    public static synchronized void upsert(Context c, Group g) {
        List<Group> all = load(c);
        if (g.id == null) g.id = "g" + System.currentTimeMillis();
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(g.id)) { all.set(i, g); found = true; break; }
        }
        if (!found) all.add(g);
        save(c, all);
    }

    public static synchronized void delete(Context c, String id) {
        List<Group> all = load(c);
        Iterator<Group> it = all.iterator();
        while (it.hasNext()) if (it.next().id.equals(id)) it.remove();
        save(c, all);
    }

    /** Only the group's apps that are still installed. */
    public static List<String> installed(Context c, Group g) {
        PackageManager pm = c.getPackageManager();
        List<String> out = new ArrayList<>();
        for (String p : g.pkgs) {
            try { pm.getApplicationInfo(p, 0); out.add(p); } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return out;
    }
}

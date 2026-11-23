package com.mhm.net;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Stores the "block internet for app X" rules. */
public final class FirewallStore {
    private FirewallStore() {}

    public static final int UNTIL = 0;   // blocked until a time
    public static final int FOREVER = 1; // blocked until I re-enable it
    public static final int DAILY = 2;   // blocked every day between two times

    public static class Rule {
        public String pkg;
        public int mode;
        public long until;
        public int from, to; // minutes of day
    }

    public static synchronized List<Rule> load(Context c) {
        List<Rule> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.p(c).getString("fw_rules", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Rule r = new Rule();
                r.pkg = o.getString("pkg");
                r.mode = o.optInt("mode", FOREVER);
                r.until = o.optLong("until", 0);
                r.from = o.optInt("from", 0);
                r.to = o.optInt("to", 0);
                out.add(r);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static synchronized void save(Context c, List<Rule> rules) {
        JSONArray a = new JSONArray();
        try {
            for (Rule r : rules) {
                JSONObject o = new JSONObject();
                o.put("pkg", r.pkg);
                o.put("mode", r.mode);
                o.put("until", r.until);
                o.put("from", r.from);
                o.put("to", r.to);
                a.put(o);
            }
        } catch (Exception ignored) {}
        Prefs.p(c).edit().putString("fw_rules", a.toString()).apply();
    }

    public static synchronized void put(Context c, Rule rule) {
        List<Rule> rules = load(c);
        Iterator<Rule> it = rules.iterator();
        while (it.hasNext()) if (it.next().pkg.equals(rule.pkg)) it.remove();
        rules.add(rule);
        save(c, rules);
    }

    public static synchronized void remove(Context c, String pkg) {
        List<Rule> rules = load(c);
        Iterator<Rule> it = rules.iterator();
        while (it.hasNext()) if (it.next().pkg.equals(pkg)) it.remove();
        save(c, rules);
    }

    public static synchronized void clear(Context c) { save(c, new ArrayList<Rule>()); }

    public static boolean isActive(Rule r, long now) {
        switch (r.mode) {
            case UNTIL: return now < r.until;
            case FOREVER: return true;
            case DAILY: {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(now);
                int m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
                if (r.from == r.to) return false;
                if (r.from < r.to) return m >= r.from && m < r.to;
                return m >= r.from || m < r.to; // crosses midnight
            }
        }
        return false;
    }

    /** Packages that must be blocked right now. Also deletes expired timed rules. */
    public static synchronized Set<String> activePackages(Context c, long now) {
        List<Rule> rules = load(c);
        boolean changed = false;
        Set<String> out = new HashSet<>();
        Iterator<Rule> it = rules.iterator();
        while (it.hasNext()) {
            Rule r = it.next();
            if (r.mode == UNTIL && now >= r.until) { it.remove(); changed = true; continue; }
            if (isActive(r, now)) out.add(r.pkg);
        }
        if (changed) save(c, rules);
        return out;
    }

    public static String describe(Rule r, long now) {
        switch (r.mode) {
            case UNTIL: return "متبقي " + Fmt.minutes(r.until - now);
            case FOREVER: return "محجوب حتى تعيد التفعيل";
            case DAILY: return "يومياً من " + Fmt.hm(r.from) + " إلى " + Fmt.hm(r.to)
                    + (isActive(r, now) ? " (محجوب الآن)" : " (بانتظار الموعد)");
        }
        return "";
    }
}

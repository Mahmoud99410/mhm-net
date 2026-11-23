package com.mhm.net;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Periodic background check for the Wi-Fi auto-switch.
 * Uses JobScheduler, so it needs no notification at all.
 */
public class SwitchJob extends JobService {
    private static final int JOB_ID = 4711;

    /** Schedules or cancels the job according to the auto-switch setting. */
    public static void sync(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.cancel(JOB_ID);
        if (!Prefs.autoSwitch(c)) return;
        long interval = Math.max(15, Prefs.intervalMin(c)) * 60_000L;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, SwitchJob.class))
                .setPeriodic(interval)
                .setPersisted(true)
                .build();
        try { js.schedule(job); } catch (Exception ignored) {}
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!Prefs.autoSwitch(this)) return false;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    WifiSwitcher.runCheck(SwitchJob.this, null);
                } finally {
                    jobFinished(params, false);
                }
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return false; }
}

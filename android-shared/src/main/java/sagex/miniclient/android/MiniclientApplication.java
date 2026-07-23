package sagex.miniclient.android;

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;


import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import sagex.miniclient.MiniClient;
import sagex.miniclient.android.media.CodecCapabilityDetector;
import sagex.miniclient.android.util.Logger;
import sagex.miniclient.prefs.PrefStore;

/**
 * Created by seans on 12/10/15.
 */
public class MiniclientApplication extends Application
{
    MiniClient client = null;
    //static final Logger log = LoggerFactory.getLogger(MiniclientApplication.class);
    static final Logger log = Logger.getLogger(MiniclientApplication.class);
    private static MiniclientApplication INSTANCE = null;
    private int versionCode;
    private String versionName;
    private volatile Activity currentActivity;

    public static MiniclientApplication get() {
        return INSTANCE;
    }

    public Activity getCurrentActivity() {
        return currentActivity;
    }

    public static MiniclientApplication get(Context ctx)
    {
        if (ctx == null) return get();
        return (MiniclientApplication) ctx.getApplicationContext();
    }

    public MiniClient getClient()
    {
        return client;
    }

    @Override
    public void onCreate()
    {
        super.onCreate();

        MiniclientApplication.INSTANCE = this;
        AndroidMiniClientOptions options = new AndroidMiniClientOptions(this);

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle s) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) { currentActivity = a; }
            @Override public void onActivityPaused(Activity a) { if (currentActivity == a) currentActivity = null; }
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle s) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });

        try
        {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(options.getPrefs().getFirebaseCrashlyticsEnabled());
            FirebaseCrashlytics.getInstance().setUserId(options.getPrefs().getFirebaseCrashlyticsUser());
        }
        catch (Throwable t)
        {
            // Firebase may not be initialized (e.g. placeholder google-services.json)
        }

        PackageManager manager = this.getPackageManager();

        try
        {
            PackageInfo info = manager.getPackageInfo(this.getPackageName(), PackageManager.GET_ACTIVITIES);
            versionCode = info.versionCode;
            versionName = info.versionName;
        }
        catch (PackageManager.NameNotFoundException e)
        {
            versionCode = -1;
            versionName = "";
            log.logWarning("Unable to get Version Code or Version Name: " + e.getMessage());
        }

        // by default don't use the sdcard
        try
        {
            AppUtil.initLogging(this, options.getPrefs().getBoolean(PrefStore.Keys.use_log_to_sdcard, false));
            AppUtil.setLogLevel(options.getPrefs().getString(PrefStore.Keys.log_level, "warn"));
        }
        catch (Throwable t)
        {
            log.logWarning("Failed to configureList logging", t);
        }

        // start the client instance
        client = new MiniClient(options, Logger.getLogger("MiniClient"));

        // Initialise download/offline infrastructure if the optional
        // android-offline module is on the classpath (mobile flavour).
        // In the online-only flavour this is a no-op.
        OfflineModuleBridge.init(this, client);

        try
        {
            Intent i = new Intent(getBaseContext(), MiniclientService.class);
            startService(i);
        }
        catch (Throwable t)
        {
            log.logError("Failed to start MiniClient service", t);
        }

        log.logDebug("-------- LAYOUT: {"+ getResources().getString(R.string.layout) + "} ---------");

        // Run codec smoke tests on a background thread at app init.
        // Results are cached for the lifetime of the process and used by
        // capability advertisement and player pre-validation to filter out
        // codecs that are listed in MediaCodecList but actually broken.
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                CodecCapabilityDetector.runCodecSmokeTests();
            }
        }, "CodecSmokeTest").start();
    }

    @Override
    public void onTerminate()
    {
        log.logDebug("Destroying MiniClient");
        Intent i = new Intent(getBaseContext(), MiniclientService.class);
        stopService(i);
        super.onTerminate();
        MiniclientApplication.INSTANCE = null;
    }

    @Override
    public void onLowMemory()
    {
        super.onLowMemory();
    }

    public int getVersionCode()
    {
        return versionCode;
    }

    public String getVersionName()
    {
        return versionName;
    }

    private String getInfo()
    {
        StringBuffer sb = new StringBuffer();
        sb.append("abi: ").append(Build.SUPPORTED_ABIS[0]).append("\n");
        if (new File("/proc/cpuinfo").exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(new File("/proc/cpuinfo")));
                String aLine;
                while ((aLine = br.readLine()) != null) {
                    sb.append(aLine + "\n");
                }
                if (br != null) {
                    br.close();
                }
            } catch (IOException e) {
                log.logWarning("getInfo() failed", e);

            }
        }
        return sb.toString();
    }
}

package sagex.miniclient.android.util;

import sagex.miniclient.logging.ILogger;
import org.slf4j.LoggerFactory;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

public class Logger implements ILogger
{
    private Class cls;
    private org.slf4j.Logger log;
    private static FirebaseCrashlytics crashlytics;
    private static boolean crashlyticsChecked = false;

    private static FirebaseCrashlytics getCrashlytics()
    {
        if (!crashlyticsChecked)
        {
            crashlyticsChecked = true;
            try
            {
                crashlytics = FirebaseCrashlytics.getInstance();
            }
            catch (Throwable t)
            {
                crashlytics = null;
            }
        }
        return crashlytics;
    }

    public static Logger getLogger(Class cls)
    {
        Logger log = new Logger();
        log.log = LoggerFactory.getLogger(cls);
        return log;
    }

    public static Logger getLogger(String name)
    {
        Logger log = new Logger();
        log.log = LoggerFactory.getLogger(name);
        return log;
    }

    @Override
    public ILogger getLoggerInstance(String name)
    {
        return Logger.getLogger(name);
    }

    @Override
    public ILogger getLoggerInstance(Class cls)
    {
        return Logger.getLogger(cls);
    }

    @Override
    public void recordException(Throwable t)
    {
        if (getCrashlytics() != null) getCrashlytics().recordException(t);
    }

    @Override
    public void logError(String message)
    {
        if (getCrashlytics() != null) getCrashlytics().log(message);
        log.error(message);
    }

    @Override
    public void logError(String message, Throwable t)
    {
        if (getCrashlytics() != null) { getCrashlytics().log(message); getCrashlytics().recordException(t); }
        log.error(message);
    }

    @Override
    public void logWarning(String message)
    {
        if (getCrashlytics() != null) getCrashlytics().log(message);
        log.warn(message);
    }

    @Override
    public void logWarning(String message, Throwable t)
    {
        if (getCrashlytics() != null) { getCrashlytics().log(message); getCrashlytics().recordException(t); }
        log.warn(message, t);
    }

    @Override
    public void logDebug(String message)
    {
        if (getCrashlytics() != null) getCrashlytics().log(message);
        log.debug(message);
    }

    @Override
    public void logDebug(String message, Throwable t)
    {
        if (getCrashlytics() != null) { getCrashlytics().log(message); getCrashlytics().recordException(t); }
        log.debug(message, t);
    }

    @Override
    public void logInfo(String message)
    {
        if (getCrashlytics() != null) getCrashlytics().log(message);
        log.info(message);
    }

    @Override
    public void logInfo(String message, Throwable t)
    {
        if (getCrashlytics() != null) { getCrashlytics().log(message); getCrashlytics().recordException(t); }
        log.info(message, t);
    }

    @Override
    public void logTrace(String message)
    {
        if (getCrashlytics() != null) getCrashlytics().log(message);
        log.trace(message);
    }

    @Override
    public void logTrace(String message, Throwable t)
    {
        if (getCrashlytics() != null) { getCrashlytics().log(message); getCrashlytics().recordException(t); }
        log.trace(message, t);
    }

    @Override
    public void setCustomKey(String key, String value)
    {
        if (getCrashlytics() != null) getCrashlytics().setCustomKey(key, value);
    }

    @Override
    public void setUserID(String userID)
    {
        if (getCrashlytics() != null) getCrashlytics().setUserId(userID);
    }
}

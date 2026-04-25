package sagex.miniclient.android.util;

import sagex.miniclient.logging.ILogger;
import org.slf4j.LoggerFactory;
//import com.google.firebase.crashlytics.FirebaseCrashlytics;

public class Logger implements ILogger
{
    private Class cls;
    private org.slf4j.Logger log;

    public static Logger getLogger(Class cls)
    {
        Logger log = new Logger();
        log.log = LoggerFactory.getLogger(cls);

        //log.crashlogger = FirebaseCrashlytics.getInstance();

        return log;
    }

    public static Logger getLogger(String name)
    {
        Logger log = new Logger();
        log.log = LoggerFactory.getLogger(name);
        //log.crashlogger = FirebaseCrashlytics.getInstance();

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
        log.error("Exception recorded", t);
    }

    @Override
    public void logError(String message)
    {
        log.error(message);
    }

    @Override
    public void logError(String message, Throwable t)
    {
        log.error(message, t);
    }

    @Override
    public void logWarning(String message)
    {
        log.warn(message);
    }

    @Override
    public void logWarning(String message, Throwable t)
    {
        log.warn(message, t);
    }

    @Override
    public void logDebug(String message)
    {
        log.debug(message);
    }

    @Override
    public void logDebug(String message, Throwable t)
    {
        log.debug(message, t);
    }

    @Override
    public void logInfo(String message)
    {
        log.info(message);
    }

    @Override
    public void logInfo(String message, Throwable t)
    {
        log.info(message, t);
    }

    @Override
    public void logTrace(String message)
    {
        log.trace(message);
    }

    @Override
    public void logTrace(String message, Throwable t)
    {
        log.trace(message, t);
    }

    @Override
    public void setCustomKey(String key, String value)
    {
        log.debug("CustomKey: {} = {}", key, value);
    }

    @Override
    public void setUserID(String userID)
    {
        log.debug("UserID: {}", userID);
    }
}

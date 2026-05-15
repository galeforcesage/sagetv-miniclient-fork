/*
 * Copyright 2025 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package sagex.miniclient.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.net.SocketOption;

/**
 * Tunes TCP keep-alive timing on a {@link Socket} to detect dead control-channel
 * connections far sooner than the OS defaults (typically 2 hours idle on Linux,
 * minutes on Windows / macOS).
 *
 * <p>The standard {@code Socket.setKeepAlive(true)} only opts the socket in to
 * the OS keep-alive mechanism; it does not change the timing parameters. Java 11+
 * exposes those via {@code jdk.net.ExtendedSocketOptions} (Android API 30+).
 * We resolve the options reflectively so the call is a no-op on older platforms
 * — the standard OS keep-alive still works, just on the longer default schedule.
 *
 * <p>Defaults probe an idle connection after 30 s, retry every 10 s, and give up
 * after 5 failures — so a dead server is detected in roughly 80 s instead of
 * 2 h. Override at runtime with system properties:
 * <ul>
 *   <li>{@code sagetv.tcp.keepidle.s} — idle seconds before probing (default 30)</li>
 *   <li>{@code sagetv.tcp.keepinterval.s} — seconds between probes (default 10)</li>
 *   <li>{@code sagetv.tcp.keepcount} — number of failed probes before giving up (default 5)</li>
 * </ul>
 */
public final class SocketKeepAlive
{
    private static final Logger log = LoggerFactory.getLogger(SocketKeepAlive.class);

    private static final int IDLE_S     = Integer.getInteger("sagetv.tcp.keepidle.s", 30);
    private static final int INTERVAL_S = Integer.getInteger("sagetv.tcp.keepinterval.s", 10);
    private static final int COUNT      = Integer.getInteger("sagetv.tcp.keepcount", 5);

    // Resolved once. null = not available on this platform.
    private static final SocketOption<Integer> TCP_KEEPIDLE     = resolveOption("TCP_KEEPIDLE");
    private static final SocketOption<Integer> TCP_KEEPINTERVAL = resolveOption("TCP_KEEPINTERVAL");
    private static final SocketOption<Integer> TCP_KEEPCOUNT    = resolveOption("TCP_KEEPCOUNT");

    private SocketKeepAlive() {}

    @SuppressWarnings("unchecked")
    private static SocketOption<Integer> resolveOption(String name)
    {
        try {
            Class<?> cls = Class.forName("jdk.net.ExtendedSocketOptions");
            Object opt = cls.getField(name).get(null);
            return (SocketOption<Integer>) opt;
        } catch (Throwable t) {
            // jdk.net.ExtendedSocketOptions absent (Android < API 30) or option
            // unavailable on this platform. Fall back to OS defaults silently.
            return null;
        }
    }

    /**
     * Apply tuned keep-alive timing to {@code sock}. Caller should already have
     * invoked {@code sock.setKeepAlive(true)}. Failures are logged at debug
     * level only; the connection still works on OS defaults if tuning fails.
     */
    public static void apply(Socket sock)
    {
        if (TCP_KEEPIDLE == null) {
            // Old Android / unsupported platform — nothing to do.
            return;
        }
        try {
            sock.setOption(TCP_KEEPIDLE,     IDLE_S);
            sock.setOption(TCP_KEEPINTERVAL, INTERVAL_S);
            if (TCP_KEEPCOUNT != null) {
                sock.setOption(TCP_KEEPCOUNT, COUNT);
            }
            log.debug("TCP keep-alive tuned: idle={}s, interval={}s, count={}",
                    IDLE_S, INTERVAL_S, COUNT);
        } catch (Throwable t) {
            log.debug("TCP keep-alive tuning unavailable on this platform: {}",
                    t.toString());
        }
    }
}

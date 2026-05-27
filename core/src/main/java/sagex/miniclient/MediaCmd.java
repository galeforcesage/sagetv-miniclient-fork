/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sagex.miniclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import sagex.miniclient.prefs.PrefStore;
import sagex.miniclient.uibridge.Dimension;
import sagex.miniclient.uibridge.Rectangle;
import sagex.miniclient.util.Utils;
import sagex.miniclient.util.VerboseLogging;

/**
 * @author Narflex
 */
public class MediaCmd
{
    public static final int MEDIACMD_INIT = 0;
    public static final int MEDIACMD_DEINIT = 1;
    public static final int MEDIACMD_OPENURL = 16;
    public static final int MEDIACMD_GETMEDIATIME = 17;
    // length, url
    public static final int MEDIACMD_SETMUTE = 18;
    public static final int MEDIACMD_STOP = 19;
    // mute
    public static final int MEDIACMD_PAUSE = 20;
    public static final int MEDIACMD_PLAY = 21;
    public static final int MEDIACMD_FLUSH = 22;
    public static final int MEDIACMD_PUSHBUFFER = 23;
    public static final int MEDIACMD_GETVIDEORECT = 24;
    // size, flags, data
    public static final int MEDIACMD_SETVIDEORECT = 25;
    // returns 16bit width, 16bit height
    public static final int MEDIACMD_GETVOLUME = 26;
    // x, y, width, height, x, y, width, height
    public static final int MEDIACMD_SETVOLUME = 27;
    // volume
    public static final int MEDIACMD_FRAMESTEP = 28;
    public static final int MEDIACMD_SEEK = 29;

    public static final int MEDIACMD_DVD_STREAMS = 36;


    public static final int STREAM_TYPE_AUDIO = 0;
    public static final int STREAM_TYPE_SUBTITLE = 1;

    public static final Map<Integer, String> CMDMAP = new HashMap<Integer, String>();
    private static final Logger log = LoggerFactory.getLogger(MediaCmd.class);

    private final MiniClient client;
    private MiniPlayerPlugin playa;
    private boolean pushMode;
    private int DESIRED_VIDEO_PREBUFFER_SIZE = 16 * 1024 * 1024;
    private int DESIRED_AUDIO_PREBUFFER_SIZE = 2 * 1024 * 1024;
    private int maxPrebufferSize;
    private MiniClientConnection myConn;
    private long lastServerStartTime = -1;

    /*
     * Authoritative recording-relative position of the most recent
     * MEDIACMD_SEEK from the server (milliseconds), or -1 when no seek is
     * pending. After a seek the server flushes the push stream and starts
     * a new mux session whose first PUSHBUFFER.serverMuxTime is the
     * muxer-internal PTS (typically sub-second) -- NOT a recording-relative
     * position. If we blindly adopt that as lastServerStartTime, OSD time =
     * playerPos + lastServerStartTime sticks near 0 forever. Stashing the
     * MEDIACMD_SEEK target here lets the post-flush PUSHBUFFER handler use
     * the real seek position as the baseline instead.
     */
    private long pendingSeekTargetMs = -1;

    /*
     * Early-push holding buffer.
     *
     * The SageTV server can start firing MEDIACMD_PUSHBUFFER packets BEFORE the
     * MEDIACMD_OPENURL that creates the player. (Observed on Shield: 4 x 16KB push
     * packets arrive ~4ms before OPENURL.) Historically those bytes were silently
     * discarded because `playa` was still null, but the server has no idea they were
     * lost (TCP ACKed them) so it never retransmits. The lost prefix typically
     * contains the MPEG-TS PAT/PMT, leaving the player unable to identify any
     * streams -> blank screen forever.
     *
     * We buffer up to MAX_PENDING_PUSH bytes of pre-OPENURL data and, once the push
     * player is created in MEDIACMD_OPENURL, replay them into playa.pushData()
     * before returning so the ring buffer starts at byte 0.
     *
     * This is the SOLE pre-OPENURL hold buffer in the system: BaseMediaPlayerImpl.load()
     * blocks until setupPlayer() (and TrickplayController.open()) have run on the UI
     * thread, so by the time the replay below executes the native ring buffer is
     * already accepting writes and no second-stage staging is required.
     */
    private static final int MAX_PENDING_PUSH = 4 * 1024 * 1024;
    private byte[] pendingPushBuf = null;
    private int pendingPushSize = 0;
    private boolean pendingPushOverflow = false;

    static
    {
        CMDMAP.put(MEDIACMD_INIT, "MEDIACMD_INIT");
        CMDMAP.put(MEDIACMD_DEINIT, "MEDIACMD_DEINIT");
        CMDMAP.put(MEDIACMD_OPENURL, "MEDIACMD_OPENURL");
        CMDMAP.put(MEDIACMD_GETMEDIATIME, "MEDIACMD_GETMEDIATIME");

        CMDMAP.put(MEDIACMD_SETMUTE, "MEDIACMD_SETMUTE");
        CMDMAP.put(MEDIACMD_STOP, "MEDIACMD_STOP");

        CMDMAP.put(MEDIACMD_PAUSE, "MEDIACMD_PAUSE");
        CMDMAP.put(MEDIACMD_PLAY, "MEDIACMD_PLAY");
        CMDMAP.put(MEDIACMD_FLUSH, "MEDIACMD_FLUSH");
        CMDMAP.put(MEDIACMD_PUSHBUFFER, "MEDIACMD_PUSHBUFFER");
        CMDMAP.put(MEDIACMD_GETVIDEORECT, "MEDIACMD_GETVIDEORECT");

        CMDMAP.put(MEDIACMD_SETVIDEORECT, "MEDIACMD_SETVIDEORECT");

        CMDMAP.put(MEDIACMD_GETVOLUME, "MEDIACMD_GETVOLUME");

        CMDMAP.put(MEDIACMD_SETVOLUME, "MEDIACMD_SETVOLUME");

        CMDMAP.put(MEDIACMD_FRAMESTEP, "MEDIACMD_FRAMESTEP");
        CMDMAP.put(MEDIACMD_SEEK, "MEDIACMD_SEEK");

        CMDMAP.put(MEDIACMD_DVD_STREAMS, "MEDIACMD_DVD_STREAMS");
    }

    /**
     * Creates a new instance of MediaCmd
     */
    public MediaCmd(MiniClient client)
    {
        this.client = client;
        this.myConn = client.getCurrentConnection();
    }

    public static void writeInt(int value, byte[] data, int offset)
    {
        data[offset] = (byte) ((value >> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    public static void writeShort(short value, byte[] data, int offset)
    {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    public static int readInt(int pos, byte[] cmddata)
    {
        return ((cmddata[pos + 0] & 0xFF) << 24) | ((cmddata[pos + 1] & 0xFF) << 16) | ((cmddata[pos + 2] & 0xFF) << 8) | (cmddata[pos + 3] & 0xFF);
    }

    public static short readShort(int pos, byte[] cmddata)
    {
        return (short) (((cmddata[pos + 0] & 0xFF) << 8) | (cmddata[pos + 1] & 0xFF));
    }

    public MiniPlayerPlugin getPlaya()
    {
        return playa;
    }

    public void close()
    {
        if (myConn.getGfxCmd() != null)
            myConn.getGfxCmd().setVideoBounds(null, null);
        if (playa != null)
            playa.free();
        playa = null;
        // Drop any buffered pre-OPENURL push data; a fresh stream will start over.
        pendingPushBuf = null;
        pendingPushSize = 0;
        pendingPushOverflow = false;
    }

    public int ExecuteMediaCommand(int cmd, int len, byte[] cmddata, byte[] retbuf)
    {
        if (VerboseLogging.DETAILED_MEDIA_COMMAND)
        {
            if (VerboseLogging.DETAILED_MEDIA_COMMAND_PUSHBUFFER || cmd != MEDIACMD_PUSHBUFFER)
            {
                log.debug("MEDIACMD='{}[{}]'", cmd, CMDMAP.get(cmd));
            }
        }
        switch (cmd)
        {
            case MEDIACMD_INIT:
                try
                {
                    DESIRED_VIDEO_PREBUFFER_SIZE = client.properties().getInt(PrefStore.Keys.video_buffer_size, (4 * 1024 * 1024));
                    DESIRED_AUDIO_PREBUFFER_SIZE = client.properties().getInt(PrefStore.Keys.audio_buffer_size, (2 * 1024 * 1024));
                }
                catch (Exception e)
                {
                    log.error("MEDIACMD_INIT: ERROR", e);
                }

                readInt(0, cmddata); // video format code
                writeInt(1, retbuf, 0);
                return 4;

            case MEDIACMD_DEINIT:

                writeInt(1, retbuf, 0);
                close();
                return 4;

            case MEDIACMD_OPENURL:

                this.setLastServerStartPosition(-1);

                int strLen = readInt(0, cmddata);
                String urlString = "";
                maxPrebufferSize = DESIRED_VIDEO_PREBUFFER_SIZE;
                
                if (strLen > 1)
                {
                    urlString = new String(cmddata, 4, strLen - 1);
                    log.debug("JVL - MEDIACMD_OPENURL {}", urlString);
                    // C5: emit a single human-readable line summarizing what the
                    // server's PlaybackDecisionEngine actually chose so we can
                    // diagnose NG quality regressions at a glance (e.g. the
                    // server falling back to legacy DVD MPEG-2 720x480 instead
                    // of HEVC COPY remux). One line per OPENURL.
                    logOpenUrlDecision(urlString);
                }
                
                if (!urlString.startsWith("push:"))
                {
                    if (urlString.startsWith("dvd:"))
                    {
                        log.error("DVD PlayBack not supported");
                    }
                    else if (urlString.startsWith("file://"))
                    {
                        playa = myConn.newPlayerPlugin( urlString);//new MiniMPlayerPlugin(myConn.getGfxCmd(), myConn);
                        playa.setPushMode(false);
                        playa.load((byte) 0, (byte) 0, "", urlString, null, false, 0);
                        pushMode = false;
                    }
                    else
                    {
                    
                        playa = myConn.newPlayerPlugin( urlString);//new MiniMPlayerPlugin(myConn.getGfxCmd(), myConn);
                        // We always set it to be an active file because it'll get turned off by the streaming code if it is not.
                        // It's safe to say it's active when it's not (as long as it's a streamable file format), but the opposite is not true.
                        // So we always say it's active to avoid any problems loading the file if it's a streamable file format.
                        boolean isActive = urlString.toLowerCase().endsWith(".mpg")
                                || urlString.toLowerCase().endsWith(".ts")
                                || urlString.toLowerCase().endsWith(".flv");
                        playa.setPushMode(false);
                        playa.load((byte) 0, (byte) 0, "", urlString, myConn.getServerName(), isActive, 0);
                        pushMode = false;
                    }
                }
                else
                {
                    pushMode = true;
                    {
                        if (urlString.indexOf("audio") != -1 && urlString.indexOf("bf=vid") == -1) {
                            maxPrebufferSize = DESIRED_AUDIO_PREBUFFER_SIZE;
                        } else {
                            maxPrebufferSize = DESIRED_VIDEO_PREBUFFER_SIZE;
                        }
                        playa = myConn.newPlayerPlugin( urlString);//new MiniMPlayerPlugin(myConn.getGfxCmd(), myConn);
                        playa.setPushMode(true);
                        playa.load((byte) 0, (byte) 0, "", urlString, null, true, 0);

                        // If the server pushed bytes BEFORE this OPENURL was processed,
                        // they were stashed in pendingPushBuf. Replay them now so the ring
                        // buffer starts with the actual stream prefix (PAT/PMT, etc.) and
                        // ExoPlayer's TsExtractor can identify the streams.
                        if (pendingPushSize > 0)
                        {
                            try
                            {
                                playa.pushData(pendingPushBuf, 0, pendingPushSize);
                            }
                            catch (IOException e)
                            {
                                log.error("PUSHBUFFER replay error", e);
                            }
                            finally
                            {
                                pendingPushBuf = null;
                                pendingPushSize = 0;
                                pendingPushOverflow = false;
                            }
                        }
                    }
                }
                writeInt(1, retbuf, 0);

                return 4;
            case MEDIACMD_GETMEDIATIME:
                if (playa == null)
                    return 0;
                long theTime = getMediaTimeMillis();
                writeInt((int) theTime, retbuf, 0);
                if (MiniClientConnection.detailedBufferStats) {
                    if (playa != null) {
                        retbuf[4] = (byte) (playa.getState() & 0xFF);
                    } else {
                        retbuf[4] = 0;
                    }
                    return 5;
                } else
                    return 4;
            case MEDIACMD_SETMUTE:
                writeInt(1, retbuf, 0);
                if (playa == null)
                    return 4;
                playa.setMute(readInt(0, cmddata) != 0);
                return 4;
            case MEDIACMD_STOP:
                writeInt(1, retbuf, 0);
                if (playa == null)
                    return 4;
                playa.stop();
                return 4;
            case MEDIACMD_PAUSE:
                writeInt(1, retbuf, 0);
                if (playa == null)
                    return 4;
                log.debug("Pause was called");
                playa.pause();
                return 4;
            case MEDIACMD_PLAY:
                writeInt(1, retbuf, 0);
                if (playa == null)
                    return 4;
                log.debug("Play was called");
                playa.play();
                return 4;
            case MEDIACMD_FLUSH:
                writeInt(1, retbuf, 0);

                if (playa != null && pushMode)
                {
                    if (pendingSeekTargetMs >= 0)
                    {
                        // Server flushed in response to MEDIACMD_SEEK. Use the
                        // authoritative seek target as the new baseline directly
                        // -- do NOT mark lastServerStartTime = -1 because that
                        // would let the next PUSHBUFFER overwrite the baseline
                        // with the muxer's sub-second post-flush PTS.
                        log.debug("Flush - pushMode flush after MEDIACMD_SEEK; baseline := seek target {} ms", pendingSeekTargetMs);
                        playa.flush();
                        this.setLastServerStartPosition(pendingSeekTargetMs);
                        pendingSeekTargetMs = -1;
                    }
                    else
                    {
                        log.debug("Flush - Flush called on pushMode.  Setting last server time to -1");
                        playa.flush();
                        this.setLastServerStartPosition(-1);
                    }
                }

                return 4;

            case MEDIACMD_PUSHBUFFER:

                int buffSize = readInt(0, cmddata);
                int flags = readInt(4, cmddata);
                int bufDataOffset = 8;

                if (MiniClientConnection.detailedBufferStats && buffSize > 0 && len > buffSize + 13)
                {
                    bufDataOffset += 10;
                    //short statsChannelBWKbps = readShort(8, cmddata);
                    //short statsStreamBWKbps = readShort(10, cmddata);
                    //short statsTargetBWKbps = readShort(12, cmddata);
                    int serverMuxTime = readInt(14, cmddata);

                    if (playa != null)
                    {
                        /*
                         * During push playback, after the server calls for a flush (Most Likely a seek event)
                         * sagetv sends the playback position telling us about the buffer.
                         * We will store this position so that it can be used in players that do not read
                         * the position from the container on push playback
                        */
                        if (lastServerStartTime < 0 && serverMuxTime > 0)
                        {
                            log.debug("Flush - Last server time is -1, and serverMuxtime > 0.  ServerMuxTime: {}", Utils.toHHMMSS(serverMuxTime, true));
                            this.setLastServerStartPosition(serverMuxTime);
                        }
                        if (VerboseLogging.DETAILED_PUSHBUFFER_LOGGING)
                        {
                            log.debug("PushBuffer: PUSHED ServerMUXTime: {}, lastServerStartTime: {}", Utils.toHHMMSS(serverMuxTime, true), Utils.toHHMMSS(this.getLastServerStartPosition(), true));
                        }

                    }
                }

                //sometimes pushbuffer is called to just get bandwidth so don't pass that along to the player
                //boolean noMoreData = flags == 0x80 && playa != null;
                if (playa != null)
                {
                    if (buffSize > 0)
                    {
                        try
                        {
                            playa.pushData(cmddata, bufDataOffset, buffSize);
                        }
                        catch (IOException e)
                        {
                            log.error("Pushbuffer Error", e);
                            client.closeConnection();
                        }
                    }

                    if (flags == 0x80)
                    {
                        log.debug("------------------------- setServerEOS Called --------------------------------");
                        playa.setServerEOS();
                    }
                }
                else if (buffSize > 0 && bufDataOffset + buffSize <= len)
                {
                    // Player not yet created (race: PUSHBUFFER arrived before OPENURL).
                    // Stash the bytes so MEDIACMD_OPENURL can replay them after the
                    // push player is built. Without this the server-pushed prefix
                    // (PAT/PMT for MPEG-TS) is lost and playback wedges with a blank
                    // screen because TsExtractor never identifies the streams.
                    if (!pendingPushOverflow)
                    {
                        if (pendingPushBuf == null)
                        {
                            // First early packet: allocate exactly what we need; we'll
                            // grow geometrically if more arrive.
                            pendingPushBuf = new byte[Math.min(MAX_PENDING_PUSH, Math.max(buffSize, 64 * 1024))];
                        }
                        if (pendingPushSize + buffSize > pendingPushBuf.length)
                        {
                            int newCap = pendingPushBuf.length;
                            while (newCap < pendingPushSize + buffSize && newCap < MAX_PENDING_PUSH)
                            {
                                newCap = Math.min(MAX_PENDING_PUSH, newCap * 2);
                            }
                            if (newCap >= pendingPushSize + buffSize)
                            {
                                byte[] grown = new byte[newCap];
                                System.arraycopy(pendingPushBuf, 0, grown, 0, pendingPushSize);
                                pendingPushBuf = grown;
                            }
                            else
                            {
                                pendingPushOverflow = true;
                                log.warn("PUSHBUFFER pre-OPENURL holding buffer overflow at {} bytes; dropping further early bytes",
                                        pendingPushSize);
                            }
                        }
                        if (!pendingPushOverflow)
                        {
                            System.arraycopy(cmddata, bufDataOffset, pendingPushBuf, pendingPushSize, buffSize);
                            pendingPushSize += buffSize;
                        }
                    }
                }

                int rv;

                /*
                 * Always indicate we have at least 512K of buffer...there's NO reason to stop buffering additional
                 * data since as playback goes on we keep writing to the filesystem anyways. Yeah, we could recover some bandwidth
                 * but that's not how any online video players work and we shouldn't be any different than that.
                */
                if (playa == null)
                {
                    rv = maxPrebufferSize;
                }
                else
                {
                    //rv = (int)(PushBufferDataSource.PIPE_SIZE - (bufferFilePushedBytes - playa.getLastFileReadPos()));
                    rv = playa.getBufferLeft();
                    // log.debug("PUSHBUFFER: bufSize: " + buffSize + " availSize=" + rv);
                }
                
                if (VerboseLogging.DETAILED_PUSHBUFFER_LOGGING)
                {
                    if (rv < 0)
                    {
                        log.debug("PUSHBUFFER: We Letting Server know we are done:  rv: {}", rv);
                    }
                }

                writeInt(rv, retbuf, 0);

                if (MiniClientConnection.detailedBufferStats)
                {
                    if (playa != null)
                    {
                        writeInt((int) getMediaTimeMillis(), retbuf, 4);
                        retbuf[8] = (byte) (playa.getState() & 0xFF);
                    }
                    else
                    {
                        writeInt(0, retbuf, 4);
                        retbuf[8] = 0;
                    }

                    if (playa != null)
                    {
                        retbuf[8] = (byte) (playa.getState() & 0xFF);
                    }

                    return 9;
                }
                else
                {
                    return 4;
                }

            case MEDIACMD_GETVOLUME:
                if (playa == null)
                    writeInt(65535, retbuf, 0);
                else
                    writeInt(Math.round(playa.getVolume() * 65535), retbuf, 0);
                return 4;
            case MEDIACMD_SETVOLUME:
                if (playa == null)
                    writeInt(65535, retbuf, 0);
                else
                    writeInt(Math.round(playa.setVolume(readInt(0, cmddata) / 65535.0f) * 65535), retbuf, 0);
                return 4;
            case MEDIACMD_SETVIDEORECT:
                Rectangle srcRect = new Rectangle(readInt(0, cmddata), readInt(4, cmddata),
                        readInt(8, cmddata), readInt(12, cmddata));
                Rectangle destRect = new Rectangle(readInt(16, cmddata), readInt(20, cmddata),
                        readInt(24, cmddata), readInt(28, cmddata));
                if (playa != null)
                    playa.setVideoRectangles(srcRect, destRect, false);
                myConn.getGfxCmd().setVideoBounds(srcRect, destRect);
                writeInt(0, retbuf, 0);
                return 4;
            case MEDIACMD_GETVIDEORECT:
                Dimension vidRect = null;
                if (playa != null) {
                    vidRect = playa.getVideoDimensions();
                    writeShort((short) vidRect.width, retbuf, 0);
                    writeShort((short) vidRect.height, retbuf, 2);
                } else {
                    writeInt(0, retbuf, 0);
                }
                return 4;
            case MEDIACMD_SEEK:

                long seekTime = ((long) readInt(0, cmddata) << 32) | readInt(4, cmddata);

                if (playa != null)
                {
                    log.debug("MEDIACMD_SEEK called: {}", seekTime);
                    if (pushMode)
                    {
                        // Stash the authoritative target so the post-flush
                        // PUSHBUFFER handler can use it as the baseline (see
                        // pendingSeekTargetMs field doc).
                        pendingSeekTargetMs = seekTime;

                        // Legacy server quirk: for FORWARD seeks within its
                        // pushed-buffer window the server takes the
                        // seekPull0() path -- it adjusts the source file read
                        // offset and keeps pushing from the new position, but
                        // does NOT send MEDIACMD_FLUSH. Our ring buffer and
                        // IJK decoder queues still hold the pre-seek bytes,
                        // so without an explicit flush the player just keeps
                        // showing the old video while OSD jumps forward.
                        //
                        // Drive the same flush path here that we would have
                        // taken on an explicit MEDIACMD_FLUSH so the next
                        // bytes the player reads are the post-seek bytes the
                        // server is about to push. We set baseline := target
                        // directly (do NOT use lastServerStartTime = -1)
                        // because the post-seek PUSHBUFFER's serverMuxTime
                        // would otherwise overwrite it with the muxer's
                        // sub-second value.
                        log.debug("MEDIACMD_SEEK push-mode: forcing client flush; baseline := {} ms", seekTime);
                        playa.flush();
                        this.setLastServerStartPosition(seekTime);
                        pendingSeekTargetMs = -1;
                    }
                    playa.seek(seekTime);
                }

                return 0;

            case MEDIACMD_DVD_STREAMS:
                if (playa!=null)
                {
                    try
                    {
                        int streamType = readInt(0, cmddata);
                        int streamPos = readInt(4, cmddata);

                        log.debug("JVL - Stream Type: {}  Stream Pos: {}", streamType, streamPos);
                        if (streamType == STREAM_TYPE_AUDIO)
                        {
                            log.debug("JVL - Changing Audio Track");
                            playa.setAudioTrack(streamPos);
                        }
                        else if (streamType == STREAM_TYPE_SUBTITLE)
                        {
                            log.debug("JVL - Changing TEXT Track");
                            playa.setSubtitleTrack(streamPos);
                        }
                        else
                        {
                            log.error("JVL - UNKNOWN Stream Type");
                        }
                    }
                    catch (Throwable t)
                    {
                        log.error("Failed to set Stream Type", t);
                        return 0;
                    }
                    writeInt(0, retbuf, 0);

                    return 4;
                }
                else
                {
                    return 0;
                }

            default:
                log.error("MEDIACMD Unhandled Media Command: {}", cmd);
                return -1;
        }
    }

    private long getMediaTimeMillis()
    {
        if (playa != null)
        {
            return playa.getMediaTimeMillis(this.getLastServerStartPosition());
        }

        return lastServerStartTime;
    }

    private void setLastServerStartPosition(long position)
    {
        this.lastServerStartTime = position;
    }

    public long getLastServerStartPosition()
    {
        if(this.lastServerStartTime < 0)
        {
            return 0;
        }

        return lastServerStartTime;
    }

    /**
     * C5: parse the OPENURL string and emit a one-line summary of the
     * server's PlaybackDecisionEngine choice so quality regressions
     * (legacy DVD MPEG-2 720x480 fallback vs HEVC COPY remux, etc.)
     * are visible at a glance in logcat / SD-card logs.
     *
     * <p>Push URL form (best-effort parse, never throws):
     * {@code push:f=MPEG2-TS;[bf=vid;f=H.264;...][bf=aud;f=AAC;...]}</p>
     */
    private void logOpenUrlDecision(String urlString)
    {
        if (urlString == null || urlString.isEmpty()) return;
        try
        {
            String transport;
            String container = "?";
            String video = "?";
            String audio = "?";
            String videoMode = "";
            String audioMode = "";

            if (urlString.startsWith("push:"))
            {
                transport = "PUSH";
                String body = urlString.substring("push:".length());

                // Top-level f=CONTAINER (terminated by ';' or '[' or end).
                int fIdx = body.indexOf("f=");
                if (fIdx >= 0)
                {
                    int end = fIdx + 2;
                    while (end < body.length())
                    {
                        char ch = body.charAt(end);
                        if (ch == ';' || ch == '[') break;
                        end++;
                    }
                    container = body.substring(fIdx + 2, end);
                }

                // Per-track blocks: [bf=vid;...] and [bf=aud;...]
                video = extractTrackFormat(body, "bf=vid");
                audio = extractTrackFormat(body, "bf=aud");
                videoMode = extractTrackMode(body, "bf=vid");
                audioMode = extractTrackMode(body, "bf=aud");
            }
            else if (urlString.startsWith("file://"))
            {
                transport = "PULL_FILE";
                int dot = urlString.lastIndexOf('.');
                if (dot > 0) container = urlString.substring(dot + 1);
            }
            else if (urlString.startsWith("http://") || urlString.startsWith("https://"))
            {
                transport = "PULL_HTTP";
            }
            else if (urlString.startsWith("stv://"))
            {
                transport = "PULL_STV";
            }
            else if (urlString.startsWith("dvd:"))
            {
                transport = "DVD";
            }
            else
            {
                transport = "UNKNOWN";
            }

            StringBuilder line = new StringBuilder(128);
            line.append("NG OPENURL accepted: transport=").append(transport);
            line.append(" container=").append(container);
            line.append(" video=").append(video);
            if (!videoMode.isEmpty()) line.append('(').append(videoMode).append(')');
            line.append(" audio=").append(audio);
            if (!audioMode.isEmpty()) line.append('(').append(audioMode).append(')');
            log.info(line.toString());
        }
        catch (RuntimeException e)
        {
            log.debug("NG OPENURL decision parse failed for [{}]", urlString, e);
        }
    }

    /** Returns the {@code f=} value inside the first {@code [tag;...]} block, or "?" if absent. */
    private static String extractTrackFormat(String body, String tag)
    {
        int blockStart = body.indexOf("[" + tag);
        if (blockStart < 0) return "?";
        int blockEnd = body.indexOf(']', blockStart);
        if (blockEnd < 0) blockEnd = body.length();
        String block = body.substring(blockStart, blockEnd);
        // Skip the leading [tag; then find f=
        int fIdx = block.indexOf(";f=");
        if (fIdx < 0) return "?";
        int valStart = fIdx + 3;
        int valEnd = valStart;
        while (valEnd < block.length())
        {
            char ch = block.charAt(valEnd);
            if (ch == ';' || ch == ']') break;
            valEnd++;
        }
        return block.substring(valStart, valEnd);
    }

    /**
     * Best-effort hint at the per-track operation: returns "COPY" when the
     * block contains {@code m=copy}, else empty. Server tokens may evolve;
     * keep this strictly informational.
     */
    private static String extractTrackMode(String body, String tag)
    {
        int blockStart = body.indexOf("[" + tag);
        if (blockStart < 0) return "";
        int blockEnd = body.indexOf(']', blockStart);
        if (blockEnd < 0) blockEnd = body.length();
        String block = body.substring(blockStart, blockEnd).toLowerCase();
        if (block.contains(";m=copy") || block.contains(";copy")) return "COPY";
        return "";
    }
}

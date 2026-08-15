package sagex.miniclient.streaminfo;

/**
 * Computes the client ACK bitmask replied to MEDIACMD_STREAMINFO (command 40).
 * <p>
 * The server waits synchronously for this int before sending OPENURL, so the
 * ACK must be computed with no I/O.
 *
 * <pre>
 *   Bit 0 (0x01) parsed JSON successfully
 *   Bit 1 (0x02) video decoder pre-configurable (codec → known MIME)
 *   Bit 2 (0x04) audio decoder pre-configurable (codec → known MIME)
 *   Bit 3 (0x08) request server delay OPENURL until stream-ready (advisory)
 * </pre>
 *
 * <p>0x00 means "received but could not parse" — the server then proceeds with
 * the legacy OPENURL path.</p>
 */
public final class StreamInfoAck {

    public static final int PARSED = 0x01;
    public static final int VIDEO_CONFIGURED = 0x02;
    public static final int AUDIO_CONFIGURED = 0x04;
    public static final int WAIT_READY = 0x08;

    private StreamInfoAck() { }

    /**
     * Compute the ACK for a parsed StreamInfo. A null info yields 0x00.
     *
     * <p>The video/audio bits assert that the client can pre-select the demuxer
     * and build a decoder configuration from the metadata (the codec maps to a
     * known MIME). Actual hardware-decoder availability is handled separately by
     * the player-routing layer and does not change the ACK meaning.</p>
     */
    public static int compute(StreamInfo info) {
        if (info == null) return 0x00;
        int ack = PARSED;

        StreamInfo.VideoTrack v = info.primaryVideo();
        if (v != null && v.mime != null) {
            ack |= VIDEO_CONFIGURED;
        }

        StreamInfo.AudioTrack a = info.primaryAudio();
        if (a != null && a.mime != null) {
            ack |= AUDIO_CONFIGURED;
        }

        return ack;
    }

    /** Human-readable summary for logs, e.g. "0x07 video audio". */
    public static String describe(int ack) {
        StringBuilder sb = new StringBuilder(String.format("0x%02X", ack));
        if ((ack & VIDEO_CONFIGURED) != 0) sb.append(" video");
        if ((ack & AUDIO_CONFIGURED) != 0) sb.append(" audio");
        if ((ack & WAIT_READY) != 0) sb.append(" wait-ready");
        if (ack == 0) sb.append(" parse-failed");
        return sb.toString();
    }
}

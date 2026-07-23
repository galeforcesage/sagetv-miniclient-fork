package sagex.miniclient.android.video.exoplayer2;

import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.flac.FlacExtractor;
import com.google.android.exoplayer2.extractor.flv.FlvExtractor;
import com.google.android.exoplayer2.extractor.mkv.MatroskaExtractor;
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor;
import com.google.android.exoplayer2.extractor.mp4.FragmentedMp4Extractor;
import com.google.android.exoplayer2.extractor.mp4.Mp4Extractor;
import com.google.android.exoplayer2.extractor.ogg.OggExtractor;
import com.google.android.exoplayer2.extractor.ts.Ac3Extractor;
import com.google.android.exoplayer2.extractor.ts.AdtsExtractor;
import com.google.android.exoplayer2.extractor.ts.SagePsExtractor;
import com.google.android.exoplayer2.extractor.ts.TsExtractor;
import com.google.android.exoplayer2.extractor.wav.WavExtractor;

/**
 * An {@link ExtractorsFactory} that provides our patched {@link SagePsExtractor}
 * instead of the default {@link com.google.android.exoplayer2.extractor.ts.PsExtractor}.
 *
 * <p>SagePsExtractor properly demultiplexes MPEG-PS private_stream_1 sub-streams,
 * fixing crashes when files contain multiple AC3/EAC3 audio tracks sharing the
 * same PES stream ID.
 *
 * <p>If a {@link SagePsExtractor.LiveSizeProvider} is supplied, the
 * {@code SagePsExtractor} instance is constructed with it so seeks against
 * live/growing recordings map to correct byte positions even as the file grows.
 *
 * <p>All other extractors are the same as {@link
 * com.google.android.exoplayer2.extractor.DefaultExtractorsFactory}.
 */
public final class SageExtractorsFactory implements ExtractorsFactory {

    private final SagePsExtractor.LiveSizeProvider liveSizeProvider;
    private final boolean psOnly;
    private final String containerHint;

    public SageExtractorsFactory() {
        this(null, false, null);
    }

    public SageExtractorsFactory(SagePsExtractor.LiveSizeProvider liveSizeProvider) {
        this(liveSizeProvider, false, null);
    }

    /**
     * @param liveSizeProvider optional live-size provider for growing recordings
     * @param pushMode when {@code true}, restricts extractors to those the SageTV
     *                 server actually uses on the push wire: {@link SagePsExtractor}
     *                 (MPEG-PS, used for MPEG2/H.264 content) and
     *                 {@link TsExtractor} (MPEG2-TS, used for HEVC content since
     *                 HEVC-in-PS is non-standard). Excluding the rest avoids
     *                 spurious {@code ERROR_CODE_PARSING_CONTAINER_MALFORMED}
     *                 rebuilds when a post-flush sniff against a partially-filled
     *                 ring is mis-claimed by Mp3Extractor / AdtsExtractor / etc.
     */
    public SageExtractorsFactory(SagePsExtractor.LiveSizeProvider liveSizeProvider, boolean pushMode) {
        this(liveSizeProvider, pushMode, null);
    }

    /**
     * @param liveSizeProvider optional live-size provider for growing recordings
     * @param pushMode when {@code true} and no containerHint, restricts to PS/TS/MKV
     * @param containerHint if non-null, returns only the matching extractor (skip sniff).
     *                      Accepted values: "MPEG2-TS", "MPEG2-PS", "MPEG1-PS", "MATROSKA", "MP4"
     */
    public SageExtractorsFactory(SagePsExtractor.LiveSizeProvider liveSizeProvider, boolean pushMode, String containerHint) {
        this.liveSizeProvider = liveSizeProvider;
        this.psOnly = pushMode;
        this.containerHint = containerHint;
    }

    @Override
    public Extractor[] createExtractors() {
        SagePsExtractor psExtractor = new SagePsExtractor(
                new com.google.android.exoplayer2.util.TimestampAdjuster(0),
                liveSizeProvider);

        // If we have a container hint from the server, return only the matching
        // extractor. This eliminates the sniff phase (~200-500ms savings).
        if (containerHint != null) {
            Extractor hinted = resolveHintedExtractor(psExtractor);
            if (hinted != null) {
                return new Extractor[]{ hinted };
            }
            // Fall through to normal logic if hint didn't resolve
        }

        if (psOnly) {
            // Push-mode wire: MPEG-PS (MPEG2 / H.264 content), MPEG2-TS (HEVC,
            // since HEVC-in-PS is non-standard and the server muxes HEVC into TS),
            // or Matroska (NG server uses MKV for HEVC+EAC3/AC4 remux push).
            // TsExtractor's sniff is strict (multiple 0x47 sync bytes at 188-byte
            // intervals) so it won't false-positive on a PS stream, and vice
            // versa, making this two-extractor cascade safe against the
            // partial-buffer mis-sniff problem that motivated the original
            // ps-only restriction. MatroskaExtractor sniffs on EBML header bytes
            // which are distinct from both PS and TS sync patterns.
            return new Extractor[]{ psExtractor, new TsExtractor(), new MatroskaExtractor() };
        }
        return new Extractor[]{
                new MatroskaExtractor(),
                new FragmentedMp4Extractor(),
                new Mp4Extractor(),
                new Mp3Extractor(),
                new AdtsExtractor(),
                new Ac3Extractor(),
                new TsExtractor(),
                new FlvExtractor(),
                new OggExtractor(),
                // Use our patched PsExtractor that handles private_stream_1 sub-streams
                psExtractor,
                new WavExtractor(),
                new FlacExtractor(),
        };
    }

    /**
     * Map the container hint to a single Extractor instance.
     * Returns null if the hint is unrecognized (caller falls through to normal logic).
     */
    private Extractor resolveHintedExtractor(SagePsExtractor psExtractor) {
        switch (containerHint) {
            case "MPEG2-TS":
                return new TsExtractor();
            case "MPEG2-PS":
            case "MPEG1-PS":
                return psExtractor;
            case "MATROSKA":
                return new MatroskaExtractor();
            case "MP4":
                // Prefer fragmented to handle both fMP4 and regular MP4
                return new FragmentedMp4Extractor();
            default:
                return null;
        }
    }
}

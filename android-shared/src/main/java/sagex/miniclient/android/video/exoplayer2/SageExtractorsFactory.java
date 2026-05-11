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

    public SageExtractorsFactory() {
        this(null);
    }

    public SageExtractorsFactory(SagePsExtractor.LiveSizeProvider liveSizeProvider) {
        this.liveSizeProvider = liveSizeProvider;
    }

    @Override
    public Extractor[] createExtractors() {
        SagePsExtractor psExtractor = new SagePsExtractor(
                new com.google.android.exoplayer2.util.TimestampAdjuster(0),
                liveSizeProvider);
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
}

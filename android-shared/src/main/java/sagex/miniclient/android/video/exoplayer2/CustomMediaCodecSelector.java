package sagex.miniclient.android.video.exoplayer2;


import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.util.VerboseLogging;

import java.util.List;

public class CustomMediaCodecSelector implements MediaCodecSelector
{
    protected final Logger log = LoggerFactory.getLogger(this.getClass());
    

    
    @Override
    public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder) throws MediaCodecUtil.DecoderQueryException
    {
        List<MediaCodecInfo> codecs =  MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);

        if (codecs.size() > 0) {
            MediaCodecInfo selected = codecs.get(0);
            boolean isHardware = !selected.name.startsWith("OMX.google.") && !selected.name.startsWith("c2.android.");
            log.warn("Codec selection for {}: {} ({}hw, tunneling={})",
                mimeType, selected.name,
                isHardware ? "" : "NOT ",
                requiresTunnelingDecoder);
            for (int i = 1; i < codecs.size(); i++) {
                log.debug("  fallback[{}]: {}", i, codecs.get(i).name);
            }
        } else {
            log.warn("No decoders found for {}", mimeType);
        }

        return codecs;
    }
       /*
    @Nullable
    @Override

    public MediaCodecInfo getPassthroughDecoderInfo() throws MediaCodecUtil.DecoderQueryException
    {
        MediaCodecInfo codec = MediaCodecSelector.DEFAULT.getPassthroughDecoderInfo();
    
        log.debug("JVL - getPassthroughDecoderInfo called Decoder Name={} ", codec.name);
        
        return codec;
    }

     */
}

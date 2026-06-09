package sagex.miniclient.android.offline;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public class OfflineManifestV1Test {

    @Test
    public void parseAcceptsValidManifestV1() throws Exception {
        String json = "{"
                + "\"manifest_version\":1,"
                + "\"recording_id\":\"123\","
                + "\"title\":\"Holey Moley\","
                + "\"subtitle\":\"The Turducken of Golf\","
                + "\"runtime_ms\":2700000,"
                + "\"metadata\":{\"categories\":[\"Game show\"],\"rated\":\"TV-PG\"},"
                + "\"credits\":[{\"person_id\":\"P1\",\"person_name\":\"Jane\",\"role_name\":\"Correspondent\"}],"
                + "\"assets\":{\"images\":[{\"kind\":\"thumbnail\",\"url\":\"/thumb\"}],\"captions\":[]}" 
                + "}";

        OfflineManifestV1 manifest = OfflineManifestV1.parse(json);

        assertEquals("123", manifest.getRecordingId());
        assertEquals("Holey Moley", manifest.getTitle());
        assertEquals("The Turducken of Golf", manifest.getSubtitle());
        assertEquals(2700000L, manifest.getRuntimeMs());
        assertEquals(1, manifest.getArtworkCount());
        assertNotNull(manifest.pickHeroImage());
    }

    @Test(expected = OfflineManifestV1.ParseException.class)
    public void parseRejectsWrongVersion() throws Exception {
        String json = "{"
                + "\"manifest_version\":2,"
                + "\"title\":\"Bad\","
                + "\"metadata\":{},"
                + "\"credits\":[],"
                + "\"assets\":{}"
                + "}";
        OfflineManifestV1.parse(json);
    }

    @Test
    public void unknownMetadataKeysArePreservedInRemainingDetails() throws Exception {
        String json = "{"
                + "\"manifest_version\":1,"
                + "\"title\":\"Test\","
                + "\"metadata\":{\"mystery_key\":\"value\",\"rated\":\"TV-G\"},"
                + "\"credits\":[],"
                + "\"assets\":{}"
                + "}";

        OfflineManifestV1 manifest = OfflineManifestV1.parse(json);
        List<OfflineManifestV1.MetadataEntry> remaining = manifest.getRemainingMetadataEntries();

        assertFalse(remaining.isEmpty());
        assertEquals("mystery_key", remaining.get(0).key);
        assertEquals("value", remaining.get(0).value);
    }

    @Test
    public void creditsGroupByDynamicRoleName() throws Exception {
        String json = "{"
                + "\"manifest_version\":1,"
                + "\"title\":\"News\","
                + "\"metadata\":{},"
                + "\"credits\":["
                + "{\"person_name\":\"Alice\",\"role_name\":\"Correspondent\"},"
                + "{\"person_name\":\"Bob\",\"role_name\":\"Anchor\"},"
                + "{\"person_name\":\"Carol\",\"role_name\":\"Correspondent\"}"
                + "],"
                + "\"assets\":{}"
                + "}";

        OfflineManifestV1 manifest = OfflineManifestV1.parse(json);
        Map<String, List<OfflineManifestV1.Credit>> grouped = manifest.groupCreditsByRole();

        assertEquals(2, grouped.size());
        assertEquals(2, grouped.get("Correspondent").size());
        assertEquals(1, grouped.get("Anchor").size());
    }

    @Test
    public void parseAcceptsCoreAndTopLevelArtworkShape() throws Exception {
        String json = "{"
                + "\"manifest_version\":1,"
                + "\"recording_id\":\"12163311\","
                + "\"core\":{"
                + "\"title\":\"Holey Moley\","
                + "\"subtitle\":\"The Turducken of Golf\","
                + "\"description\":\"Finalists face off\","
                + "\"runtime_ms\":2700000"
                + "},"
                + "\"metadata\":{\"categories\":[\"Game show\"]},"
                + "\"credits\":[{\"person_id\":\"P1\",\"person_name\":\"Jane\",\"role_name\":\"Correspondent\"}],"
                + "\"artwork\":[{\"kind\":\"person\",\"url\":\"/art/1\",\"subject_id\":\"P1\"}],"
                + "\"assets\":{\"comskip\":{\"url\":\"/comskip\"}}"
                + "}";

        OfflineManifestV1 manifest = OfflineManifestV1.parse(json);

        assertEquals("12163311", manifest.getRecordingId());
        assertEquals("Holey Moley", manifest.getTitle());
        assertEquals("The Turducken of Golf", manifest.getSubtitle());
        assertEquals(2700000L, manifest.getRuntimeMs());
        assertEquals(1, manifest.getArtworkCount());
        assertNotNull(manifest.findPersonImage("P1"));
        assertEquals("Finalists face off", manifest.getPrimaryDescription());
    }

    @Test
    public void annexBCsdToAvcCFindsSpsAndPpsInsideSample() {
        byte[] sample = new byte[] {
                0, 0, 0, 1, 0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40,
                0, 0, 0, 1, 0x68, (byte) 0xEE, 0x3C, (byte) 0x80,
                0, 0, 0, 1, 0x65, (byte) 0x88, (byte) 0x84
        };

        byte[] avcC = AvccMuxerFactory.annexBCsdToAvcC(sample);

        assertNotNull(avcC);
        assertEquals(0x01, avcC[0]);
    }

    @Test
    public void annexBSampleToAvccRewritesStartCodesToLengths() {
        byte[] sample = new byte[] {
                0, 0, 0, 1, 0x67, 0x64, 0x00, 0x1F,
                0, 0, 0, 1, 0x68, (byte) 0xEE, 0x3C, (byte) 0x80
        };

        java.nio.ByteBuffer rewritten = AvccMuxerFactory.annexBSampleToAvcc(java.nio.ByteBuffer.wrap(sample));

        assertEquals(16, rewritten.remaining());
        assertEquals(0, rewritten.get(0));
        assertEquals(0, rewritten.get(1));
        assertEquals(0, rewritten.get(2));
        assertNotEquals(1, rewritten.get(3));
    }

    @Test
    public void annexBCsdToAvcCBuildsAfterCombiningSpsAndPpsSamples() {
        byte[] spsSample = new byte[] {
                0, 0, 0, 1, 0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40
        };
        byte[] ppsSample = new byte[] {
                0, 0, 0, 1, 0x68, (byte) 0xEE, 0x3C, (byte) 0x80
        };

        java.util.List<byte[]> sps = new java.util.ArrayList<>();
        java.util.List<byte[]> pps = new java.util.ArrayList<>();
        java.util.List<byte[]> spsNals = AvccMuxerFactory.splitAnnexBNals(spsSample);
        java.util.List<byte[]> ppsNals = AvccMuxerFactory.splitAnnexBNals(ppsSample);

        for (byte[] nal : spsNals) {
            if ((nal[0] & 0x1F) == 7) sps.add(nal);
        }
        for (byte[] nal : ppsNals) {
            if ((nal[0] & 0x1F) == 8) pps.add(nal);
        }

        byte[] avcC = AvccMuxerFactory.buildAvcCFromParameterSets(sps, pps);

        assertNotNull(avcC);
        assertEquals(0x01, avcC[0]);
    }
}

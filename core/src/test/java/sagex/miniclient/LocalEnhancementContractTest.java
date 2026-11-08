package sagex.miniclient;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LocalEnhancementContractTest {
    @Test
    public void policyNormalizationMigratesLegacyValues() {
        assertEquals("auto",
                LocalEnhancementContract.normalizePolicy("unknown"));
        assertEquals("always",
                LocalEnhancementContract.normalizePolicy("server"));
        assertEquals("always",
                LocalEnhancementContract.normalizePolicy("quality"));
        assertEquals("never",
                LocalEnhancementContract.normalizePolicy("local"));
        assertEquals("never",
                LocalEnhancementContract.normalizePolicy("savings"));
        assertEquals("ai_only",
                LocalEnhancementContract.normalizePolicy("ai_only"));
    }

    @Test
    public void invalidFieldsFailClosed() {
        assertEquals("pref=auto;cap=none;status=none",
                LocalEnhancementContract.build(
                        "invalid", "mystery", "maybe"));
    }

    @Test
    public void androidCapabilityUsesShieldOrHonest4kSink() {
        assertEquals("high_quality",
                LocalEnhancementContract.classifyAndroidCapability(
                        "SHIELD", "1920x1080"));
        assertEquals("high_quality",
                LocalEnhancementContract.classifyAndroidCapability(
                        "MID_PHONE", "3840x2160"));
        assertEquals("high_quality",
                LocalEnhancementContract.classifyAndroidCapability(
                        "MID_PHONE", "2160x3840"));
        assertEquals("standard",
                LocalEnhancementContract.classifyAndroidCapability(
                        "MID_PHONE", "1920x1080"));
        assertFalse(LocalEnhancementContract.is2160pOrBetter("unknown"));
        assertTrue(LocalEnhancementContract.is2160pOrBetter("4096x2160"));
    }

    @Test
    public void qualityHintProjectsPolicyWithoutChangingIt() {
        assertEquals("auto",
                LocalEnhancementContract.qualityHint("auto"));
        assertEquals("quality",
                LocalEnhancementContract.qualityHint("always"));
        assertEquals("quality",
                LocalEnhancementContract.qualityHint("ai_only"));
        assertEquals("savings",
                LocalEnhancementContract.qualityHint("never"));
    }

    @Test
    public void pullXcodeSetupPreservesUnknownParameters() {
        assertEquals("browserhd:enhance;tier=2160p;policy=ai_only;future=x",
                LocalEnhancementContract.pullXcodeSetupValue(
                        "pull-xcode:browserhd:enhance;tier=2160p;"
                                + "policy=ai_only;future=x"));
        assertNull(LocalEnhancementContract.pullXcodeSetupValue("pull:direct"));
        assertNull(LocalEnhancementContract.pullXcodeSetupValue("pull-xcode:"));
    }
}

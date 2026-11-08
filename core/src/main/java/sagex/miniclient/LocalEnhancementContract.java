package sagex.miniclient;

import java.util.Locale;

public final class LocalEnhancementContract {
    public static final String POLICY_AUTO = "auto";
    public static final String POLICY_ALWAYS = "always";
    public static final String POLICY_AI_ONLY = "ai_only";
    public static final String POLICY_NEVER = "never";

    public static final String CAP_NONE = "none";
    public static final String CAP_STANDARD = "standard";
    public static final String CAP_HIGH_QUALITY = "high_quality";
    public static final String CAP_AI = "ai";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_AVAILABLE = "available";
    public static final String STATUS_NONE = "none";

    private static final String PULL_XCODE_PREFIX = "pull-xcode:";

    private LocalEnhancementContract() {
    }

    public static String normalizePolicy(String raw) {
        String value = normalize(raw);
        if (POLICY_ALWAYS.equals(value) || "quality".equals(value)
                || "server".equals(value)) {
            return POLICY_ALWAYS;
        }
        if (POLICY_AI_ONLY.equals(value)) return POLICY_AI_ONLY;
        if (POLICY_NEVER.equals(value) || "savings".equals(value)
                || "local".equals(value)) {
            return POLICY_NEVER;
        }
        return POLICY_AUTO;
    }

    public static String normalizeCapability(String raw) {
        String value = normalize(raw);
        if (CAP_STANDARD.equals(value)) return CAP_STANDARD;
        if (CAP_HIGH_QUALITY.equals(value)) return CAP_HIGH_QUALITY;
        if (CAP_AI.equals(value)) return CAP_AI;
        return CAP_NONE;
    }

    public static String normalizeStatus(String raw) {
        String value = normalize(raw);
        if (STATUS_ACTIVE.equals(value)) return STATUS_ACTIVE;
        if (STATUS_AVAILABLE.equals(value)) return STATUS_AVAILABLE;
        return STATUS_NONE;
    }

    public static String build(String policy, String capability, String status) {
        return "pref=" + normalizePolicy(policy)
                + ";cap=" + normalizeCapability(capability)
                + ";status=" + normalizeStatus(status);
    }

    public static String qualityHint(String policy) {
        String normalized = normalizePolicy(policy);
        if (POLICY_NEVER.equals(normalized)) return "savings";
        if (POLICY_ALWAYS.equals(normalized)
                || POLICY_AI_ONLY.equals(normalized)) {
            return "quality";
        }
        return "auto";
    }

    public static String classifyAndroidCapability(String deviceClass,
                                                   String sinkResolution) {
        if ("SHIELD".equalsIgnoreCase(deviceClass)
                || is2160pOrBetter(sinkResolution)) {
            return CAP_HIGH_QUALITY;
        }
        return CAP_STANDARD;
    }

    public static String availableStatus(String capability) {
        return CAP_NONE.equals(normalizeCapability(capability))
                ? STATUS_NONE : STATUS_AVAILABLE;
    }

    public static boolean is2160pOrBetter(String resolution) {
        if (resolution == null) return false;
        String[] parts = resolution.trim().toLowerCase(Locale.ROOT).split("x");
        if (parts.length != 2) return false;
        try {
            int width = Integer.parseInt(parts[0].trim());
            int height = Integer.parseInt(parts[1].trim());
            return Math.max(width, height) >= 3840
                    && Math.min(width, height) >= 2160;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public static String pullXcodeSetupValue(String effectiveDelivery) {
        if (effectiveDelivery == null
                || !effectiveDelivery.startsWith(PULL_XCODE_PREFIX)) {
            return null;
        }
        String setup = effectiveDelivery.substring(PULL_XCODE_PREFIX.length());
        return setup.isEmpty() ? null : setup;
    }

    private static String normalize(String value) {
        return value == null ? ""
                : value.trim().toLowerCase(Locale.ROOT);
    }
}

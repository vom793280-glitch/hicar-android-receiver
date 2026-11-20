package local.hicarlab;

/** Configurable HiCar video capability tiers.
 *
 * The receiver advertises his_video_formats to the phone; the phone encodes to whatever
 * we declare. The original build hard-coded 800x480 @ 2 Mbps, which looks blurry on a
 * car head-unit screen. These tiers make quality tunable from the diagnostics page so
 * a field unit (which cannot be reflashed over USB) can switch tiers without a rebuild.
 *
 * Bitrate/resolution are deliberately kept conservative: a 2.4 GHz hotspot cannot carry
 * high bitrates reliably, so the 2.4G tier stays moderate while 5G allows full HD.
 */
final class VideoQuality {
    static final String PREF = "video_quality";
    static final int TIER_SMOOTH = 0, TIER_BALANCED = 1, TIER_HD = 2;

    private VideoQuality() {}

    /** Pick a default tier from the AP frequency: 2.4G -> balanced, 5G -> HD. */
    static int autoTier(int frequency) {
        return NetworkResource.is5GHz(frequency) ? TIER_HD : TIER_BALANCED;
    }

    /** Resolution is FIXED at 800x480 across all tiers. That is both the UIBC touch
     *  coordinate space (UiInput encodes 16-bit x/y capped at 799/479) and the display
     *  aspect (5:3). Advertising a larger width/height or scr-w/scr-h makes the phone
     *  scale touches into that larger space while our UIBC packets stay 800x480, so taps
     *  miss — and can stretch the picture. Clarity is improved by RAISING THE BITRATE
     *  at the same resolution instead (less compression loss), which keeps touch and
     *  display geometry perfectly aligned. */
    static String formats(int tier) {
        switch (tier) {
            case TIER_SMOOTH:
                return "codecs 1;fps 30;gop 30;bitrate 2000000;vbr-min 1000000;vbr-max 3000000;dpi 160;scr-w 800;scr-h 480;width 800;height 480";
            case TIER_BALANCED:
                return "codecs 1;fps 30;gop 30;bitrate 4000000;vbr-min 2500000;vbr-max 6000000;dpi 160;scr-w 800;scr-h 480;width 800;height 480";
            case TIER_HD:
                return "codecs 1;fps 30;gop 30;bitrate 6000000;vbr-min 4000000;vbr-max 8000000;dpi 160;scr-w 800;scr-h 480;width 800;height 480";
            default:
                return formats(TIER_BALANCED);
        }
    }

    /** Display aspect always matches the fixed UIBC coordinate space (800x480 = 5:3). */
    static int[] displayAspect() { return new int[]{800, 480}; }

    static String label(int tier) {
        switch (tier) {
            case TIER_SMOOTH: return "流畅 2Mbps";
            case TIER_HD: return "高清 6Mbps";
            default: return "均衡 4Mbps";
        }
    }

    /** -1 means "auto by frequency"; otherwise a fixed tier 0..2. Pure helpers below keep
     *  this class free of android imports so it can be unit-tested on the host; callers
     *  read/write the SharedPreferences directly. */
    static int resolve(int configuredTier, int frequency) {
        return configuredTier < 0 ? autoTier(frequency) : configuredTier;
    }
}

package local.hicarlab;

/** Host test for video quality tiers (pure logic; Context-backed getters excluded). */
public final class VideoQualityTest {
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
    static void has(String s, String token) { check(s.contains(token), "missing " + token); }

    public static void main(String[] args) {
        String smooth = VideoQuality.formats(VideoQuality.TIER_SMOOTH);
        String balanced = VideoQuality.formats(VideoQuality.TIER_BALANCED);
        String hd = VideoQuality.formats(VideoQuality.TIER_HD);

        // Resolution is FIXED across all tiers so UIBC touch coords stay aligned.
        for (String f : new String[]{smooth, balanced, hd}) {
            has(f, "scr-w 800;scr-h 480;width 800;height 480");
            has(f, "codecs 1"); has(f, "fps 30"); has(f, "gop 30");
            has(f, "vbr-min"); has(f, "vbr-max"); has(f, "dpi 160");
        }
        has(smooth, "bitrate 2000000");
        has(balanced, "bitrate 4000000");
        has(hd, "bitrate 6000000");

        // Only bitrate grows with tier; display aspect fixed at 5:3.
        check(bitrate(smooth) < bitrate(balanced) && bitrate(balanced) < bitrate(hd), "bitrate order");
        check(width(smooth) == 800 && width(balanced) == 800 && width(hd) == 800, "width fixed 800");
        int[] aspect = VideoQuality.displayAspect();
        check(aspect[0] == 800 && aspect[1] == 480, "display aspect 800x480");

        // Auto tier picks by band.
        check(VideoQuality.autoTier(2437) == VideoQuality.TIER_BALANCED, "2.4G -> balanced");
        check(VideoQuality.autoTier(5220) == VideoQuality.TIER_HD, "5G -> HD");

        // Labels are non-empty and distinct.
        check(!VideoQuality.label(0).equals(VideoQuality.label(1)), "labels differ 0/1");
        check(!VideoQuality.label(1).equals(VideoQuality.label(2)), "labels differ 1/2");

        System.out.println("VideoQualityTest passed: tiers, ordering, auto-by-band, labels");
    }

    static int bitrate(String f) {
        return Integer.parseInt(f.split("bitrate ", 2)[1].split(";", 2)[0]);
    }
    static int width(String f) {
        return Integer.parseInt(f.split("width ", 2)[1].split(";", 2)[0]);
    }
}

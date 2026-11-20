package local.hicarlab;

/** FDEE service data layout adapted from CarLink probe.py (MIT).
 * Copyright (c) 2026 CarLink contributors. See THIRD_PARTY_NOTICES.txt.
 * A syntactically valid model is not proof of a recognized/certified product.
 */
public final class DiscoveryBeacon {
    /** Original 11-byte layout kept for the protocol regression tests. */
    public static byte[] encode(String model, int sequence) {
        byte[] core=core(model,sequence);
        return core;
    }
    /**
     * 13-byte variant observed on the ALP-AL00 field build that actually obtained CCMP
     * frames from the phone. The trailing 0x11 0xE7 is capability/compat info; this is an
     * empirical compatibility option, not a claim about certified HiCar behaviour.
     * Both FIRMWARE_CAPABILITY and SEQUENCE are included so the phone can distinguish
     * a head unit that supports the extended wire format.
     */
    public static byte[] encodeExtended(String model, int sequence) {
        byte[] core=core(model,sequence);
        byte[] out=new byte[core.length+2];
        System.arraycopy(core,0,out,0,core.length);
        out[core.length]=(byte)0x11;
        out[core.length+1]=(byte)0xE7;
        return out;
    }
    /** Chooses the beacon variant from the local experiment preference. */
    public static byte[] select(String model,int sequence,boolean extended) {
        return extended?encodeExtended(model,sequence):encode(model,sequence);
    }
    private static byte[] core(String model,int sequence) {
        if (model == null || !model.matches("[0-9a-fA-F]{8}"))
            throw new IllegalArgumentException("model must be eight hexadecimal characters");
        if (sequence < 0 || sequence > 255)
            throw new IllegalArgumentException("sequence must be 0..255");
        byte[] raw = new byte[4];
        for (int i = 0; i < 4; i++) raw[i] = (byte)Integer.parseInt(model.substring(i * 2, i * 2 + 2), 16);
        return new byte[]{1, 1, 1, 3, raw[0], raw[1], raw[2], 4, raw[3], 16, (byte)sequence};
    }
}

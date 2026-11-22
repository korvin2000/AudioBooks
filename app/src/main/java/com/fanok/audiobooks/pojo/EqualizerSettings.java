package com.fanok.audiobooks.pojo;

public class EqualizerSettings {
    public int[] seekbarpos = new int[5];
    public int presetPos;
    public short reverbPreset;
    public short bassStrength;
    public boolean equalizerEnabled;
    /** Absent in settings saved before they existed: 0, the centre, and off. */
    public int balance;
    public boolean nightSound;
    /** Percent more volume, 0 to 100; absent in settings saved before it existed: 0, as recorded. */
    public int boost;
}

package vn.seasonsdiseases;

/** Dieu kien gay benh. event=true: tinh theo su kien; false: kiem tra moi 5 giay. */
public enum Trigger {
    RAIN(false), COLD_TEMP(false), HEAT_TEMP(false), FLOWERS(false), NIGHT_OUTSIDE(false),
    CROWD(false), WATER_SWIM(false), SWEAT_ARMOR(false), SUN(false), MOSQUITO(false),
    HUNGRY_LOW(false), DEHYDRATION(false), SWAMP(false), HIGH_ALTITUDE(false),
    SNOW_BIOME(false), SNOW_GLARE(false),
    EAT_RAW(true), EAT_SPOILED(true), EAT_BERRY(true), BREAK_CROP(true);

    public final boolean event;

    Trigger(boolean event) {
        this.event = event;
    }
}

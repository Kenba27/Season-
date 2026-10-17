package vn.seasonsdiseases;

import org.bukkit.potion.PotionEffectType;

import java.util.List;

public final class Disease {

    public record EffectSpec(PotionEffectType type, int amplifier, String key) { }

    public final String id;
    public final String name;
    public final String season;
    public final Trigger trigger;
    public final int threshold;
    public final double chance;
    public final double param;
    public final int durationSec;
    public final String description;
    public final String symptomMessage;
    public final String prevention;
    public final List<EffectSpec> effects;
    public final List<Cure> cures;

    public Disease(String id, String name, String season, Trigger trigger, int threshold, double chance,
                   double param, int durationSec, String description, String symptomMessage,
                   String prevention, List<EffectSpec> effects, List<Cure> cures) {
        this.id = id;
        this.name = name;
        this.season = season;
        this.trigger = trigger;
        this.threshold = threshold;
        this.chance = chance;
        this.param = param;
        this.durationSec = durationSec;
        this.description = description;
        this.symptomMessage = symptomMessage;
        this.prevention = prevention;
        this.effects = effects;
        this.cures = cures;
    }
}

package vn.seasonsdiseases;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.Locale;
import java.util.Map;

public final class Cure {

    public enum Kind { ITEM, WATER_BOTTLE, SLEEP, SWIM, FIRE }

    private static final Map<Material, String> NAMES = Map.ofEntries(
            Map.entry(Material.MILK_BUCKET, "Xô sữa"),
            Map.entry(Material.HONEY_BOTTLE, "Mật ong"),
            Map.entry(Material.GOLDEN_APPLE, "Táo vàng"),
            Map.entry(Material.ENCHANTED_GOLDEN_APPLE, "Táo vàng phù phép"),
            Map.entry(Material.MUSHROOM_STEW, "Súp nấm"),
            Map.entry(Material.RABBIT_STEW, "Súp thỏ"),
            Map.entry(Material.BEETROOT_SOUP, "Súp củ dền"),
            Map.entry(Material.CARROT, "Cà rốt"),
            Map.entry(Material.GOLDEN_CARROT, "Cà rốt vàng"),
            Map.entry(Material.MELON_SLICE, "Dưa hấu"),
            Map.entry(Material.SWEET_BERRIES, "Dâu ngọt"),
            Map.entry(Material.BREAD, "Bánh mì"),
            Map.entry(Material.APPLE, "Táo")
    );

    public final Kind kind;
    public final Material material;
    public final String nameFilter;
    public final boolean negate;

    private Cure(Kind kind, Material material, String nameFilter, boolean negate) {
        this.kind = kind;
        this.material = material;
        this.nameFilter = nameFilter;
        this.negate = negate;
    }

    /** Cu phap: SLEEP | SWIM | FIRE | WATER_BOTTLE | MATERIAL | MATERIAL~ten | MATERIAL~!ten */
    public static Cure parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        switch (s.toUpperCase(Locale.ROOT)) {
            case "SLEEP":
                return new Cure(Kind.SLEEP, null, null, false);
            case "SWIM":
                return new Cure(Kind.SWIM, null, null, false);
            case "FIRE":
                return new Cure(Kind.FIRE, null, null, false);
            case "WATER_BOTTLE":
                return new Cure(Kind.WATER_BOTTLE, null, null, false);
            default:
                break;
        }
        String matName = s;
        String name = null;
        boolean neg = false;
        int i = s.indexOf('~');
        if (i >= 0) {
            matName = s.substring(0, i);
            name = s.substring(i + 1).trim();
            if (name.startsWith("!")) {
                neg = true;
                name = name.substring(1).trim();
            }
            if (name.isEmpty()) {
                name = null;
            }
        }
        Material m = Material.matchMaterial(matName.trim());
        if (m == null) {
            return null;
        }
        return new Cure(Kind.ITEM, m, name, neg);
    }

    public boolean matches(ItemStack item) {
        if (item == null) {
            return false;
        }
        if (kind == Kind.WATER_BOTTLE) {
            if (item.getType() != Material.POTION) {
                return false;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof PotionMeta pm) {
                return pm.getBasePotionType() == PotionType.WATER && !pm.hasCustomEffects();
            }
            return false;
        }
        if (kind != Kind.ITEM || item.getType() != material) {
            return false;
        }
        if (nameFilter == null) {
            return true;
        }
        String n = "";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String stripped = ChatColor.stripColor(meta.getDisplayName());
            if (stripped != null) {
                n = stripped;
            }
        }
        boolean has = n.toLowerCase(Locale.ROOT).contains(nameFilter.toLowerCase(Locale.ROOT));
        return negate != has;
    }

    public String display() {
        switch (kind) {
            case SLEEP:
                return "Ngủ qua đêm trên giường";
            case SWIM:
                return "Xuống nước tắm/ngâm mình (10 giây)";
            case FIRE:
                return "Ngồi gần lửa/bếp lò (10 giây)";
            case WATER_BOTTLE:
                return "Chai nước";
            default:
                break;
        }
        if (nameFilter != null && !negate) {
            return nameFilter;
        }
        String base = NAMES.get(material);
        if (base == null) {
            base = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        return negate ? base + " (nguyên chất)" : base;
    }
}

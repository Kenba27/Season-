package vn.seasonsdiseases;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import tr.alperendemir.seasons.Seasons;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class SeasonDiseases extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final Set<Material> RAW = EnumSet.of(Material.BEEF, Material.CHICKEN, Material.PORKCHOP,
            Material.MUTTON, Material.RABBIT, Material.COD, Material.SALMON);
    private static final Set<Material> SPOILED = EnumSet.of(Material.ROTTEN_FLESH, Material.SPIDER_EYE,
            Material.POISONOUS_POTATO, Material.PUFFERFISH);
    private static final Set<Material> BERRY = EnumSet.of(Material.SWEET_BERRIES, Material.GLOW_BERRIES);
    private static final Set<Material> CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS,
            Material.POTATOES, Material.BEETROOTS);
    private static final Set<Material> DRINKS = EnumSet.of(Material.POTION, Material.MILK_BUCKET,
            Material.MELON_SLICE, Material.HONEY_BOTTLE);
    private static final Set<Material> HEAT = EnumSet.of(Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.FIRE, Material.SOUL_FIRE, Material.LAVA, Material.MAGMA_BLOCK, Material.FURNACE,
            Material.BLAST_FURNACE, Material.SMOKER);

    private static final Map<String, String> SEASON_NAMES = Map.of(
            "SPRING", "Mùa Xuân", "SUMMER", "Mùa Hè", "AUTUMN", "Mùa Thu", "WINTER", "Mùa Đông");
    private static final Map<String, String> EFFECT_NAMES = Map.of(
            "slowness", "Chậm chạp", "weakness", "Yếu ớt", "nausea", "Buồn nôn", "hunger", "Đói lả",
            "mining_fatigue", "Mệt mỏi (đào chậm)", "blindness", "Mờ mắt", "darkness", "Tối sầm");

    private final Map<String, Disease> diseases = new LinkedHashMap<>();
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    private File dataFile;
    private YamlConfiguration data;
    private int maxDiseases;
    private long immunityMs;
    private long eventWindowMs;
    private boolean cureOnDeath;
    private String fallbackSeason;

    private static final class PlayerState {
        final Map<String, Long> active = new LinkedHashMap<>();
        final Map<String, Long> immune = new HashMap<>();
        final Map<String, Integer> counters = new HashMap<>();
        final Map<String, Long> eventTime = new HashMap<>();
        long lastDrink = System.currentTimeMillis();
        int swim = 0;
        int fire = 0;
    }

    // ===================================================================== lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "diseases.yml").exists()) {
            saveResource("diseases.yml", false);
        }
        loadAll();

        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("benh");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tickAll, 100L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, this::saveData, 6000L, 6000L);
        for (Player p : Bukkit.getOnlinePlayers()) {
            loadState(p);
        }
        getLogger().info("Da nap " + diseases.size() + " benh.");
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            storeState(p.getUniqueId());
        }
        saveData();
    }

    private void loadAll() {
        reloadConfig();
        maxDiseases = Math.max(1, getConfig().getInt("max-diseases", 2));
        immunityMs = getConfig().getLong("immunity-seconds", 180) * 1000L;
        eventWindowMs = getConfig().getLong("event-window-seconds", 120) * 1000L;
        cureOnDeath = getConfig().getBoolean("cure-on-death", true);
        fallbackSeason = getConfig().getString("fallback-season", "SPRING").toUpperCase(Locale.ROOT);

        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        diseases.clear();
        File f = new File(getDataFolder(), "diseases.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = y.getConfigurationSection("diseases");
        if (root == null) {
            getLogger().warning("diseases.yml khong co muc 'diseases'.");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            try {
                Trigger trigger = Trigger.valueOf(s.getString("trigger", "").toUpperCase(Locale.ROOT));
                List<Disease.EffectSpec> effects = new ArrayList<>();
                for (String e : s.getStringList("symptoms")) {
                    Disease.EffectSpec spec = parseEffect(e);
                    if (spec != null) {
                        effects.add(spec);
                    } else {
                        getLogger().warning("Benh " + id + ": hieu ung khong hop le: " + e);
                    }
                }
                List<Cure> cures = new ArrayList<>();
                for (String c : s.getStringList("cures")) {
                    Cure cure = Cure.parse(c);
                    if (cure != null) {
                        cures.add(cure);
                    } else {
                        getLogger().warning("Benh " + id + ": cach chua khong hop le: " + c);
                    }
                }
                Disease d = new Disease(id, s.getString("name", id),
                        s.getString("season", "SPRING").toUpperCase(Locale.ROOT), trigger,
                        Math.max(1, s.getInt("threshold", 10)), s.getDouble("chance", 0.5),
                        s.getDouble("param", 0), Math.max(30, s.getInt("duration", 300)),
                        s.getString("description", ""), s.getString("symptom-message", ""),
                        s.getString("prevention", ""), effects, cures);
                diseases.put(id, d);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Bo qua benh " + id + ": " + ex.getMessage());
            }
        }
    }

    private Disease.EffectSpec parseEffect(String raw) {
        String[] a = raw.split(":");
        String key = a[0].trim().toLowerCase(Locale.ROOT);
        int amp = 0;
        if (a.length > 1) {
            try {
                amp = Integer.parseInt(a[1].trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(key));
        if (t == null) {
            t = PotionEffectType.getByName(key.toUpperCase(Locale.ROOT));
        }
        return t == null ? null : new Disease.EffectSpec(t, amp, key);
    }

    // ===================================================================== state / data

    private PlayerState state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new PlayerState());
    }

    private void loadState(Player p) {
        PlayerState st = state(p);
        st.lastDrink = System.currentTimeMillis();
        ConfigurationSection sec = data.getConfigurationSection("players." + p.getUniqueId());
        if (sec == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String id : sec.getKeys(false)) {
            long end = sec.getLong(id);
            if (end > now && diseases.containsKey(id)) {
                st.active.put(id, end);
            }
        }
    }

    private void storeState(UUID uuid) {
        PlayerState st = states.get(uuid);
        String path = "players." + uuid;
        data.set(path, null);
        if (st != null) {
            for (Map.Entry<String, Long> e : st.active.entrySet()) {
                data.set(path + "." + e.getKey(), e.getValue());
            }
        }
    }

    private void saveData() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            storeState(p.getUniqueId());
        }
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Khong luu duoc data.yml: " + ex.getMessage());
        }
    }

    // ===================================================================== helpers

    private static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void actionBar(Player p, String msg) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(c(msg)));
    }

    private String currentSeason() {
        try {
            return Seasons.getInstance().getSeasonManager().getCurrentSeason().name();
        } catch (Throwable t) {
            return fallbackSeason;
        }
    }

    private Double temperature(Player p) {
        try {
            var pt = Seasons.getInstance().getPlayerManager().getPlayerTemperature(p.getUniqueId());
            if (pt == null) {
                return null;
            }
            return pt.getTemperature();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private static String seasonName(String key) {
        return SEASON_NAMES.getOrDefault(key, key);
    }

    private Disease find(String key) {
        if (key == null) {
            return null;
        }
        Disease d = diseases.get(key.toLowerCase(Locale.ROOT));
        if (d != null) {
            return d;
        }
        for (Disease x : diseases.values()) {
            String stripped = ChatColor.stripColor(c(x.name));
            if (stripped != null && stripped.equalsIgnoreCase(key)) {
                return x;
            }
        }
        return null;
    }

    // ===================================================================== infection / cure

    private boolean infect(Player p, Disease d, boolean force) {
        PlayerState st = state(p);
        long now = System.currentTimeMillis();
        if (st.active.containsKey(d.id)) {
            return false;
        }
        if (!force) {
            if (p.hasPermission("seasondiseases.immune")) {
                return false;
            }
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
                return false;
            }
            if (st.active.size() >= maxDiseases) {
                return false;
            }
            Long im = st.immune.get(d.id);
            if (im != null && im > now) {
                return false;
            }
        }
        st.active.put(d.id, now + d.durationSec * 1000L);
        p.sendMessage(c("&8&m                                        "));
        p.sendMessage(c("&c🤒 Bạn đã bị bệnh &e" + d.name + "&c!"));
        p.sendMessage(c("&7Gõ &e/benh &7để xem cách chữa."));
        p.sendMessage(c("&8&m                                        "));
        p.sendTitle(c("&c🤒 " + d.name), c("&7Gõ &e/benh &7để xem cách chữa"), 10, 60, 20);
        p.playSound(p.getLocation(), "entity.villager.hurt", 1.0f, 1.2f);
        applySymptoms(p, d);
        return true;
    }

    private void cure(Player p, Disease d, String reason) {
        PlayerState st = state(p);
        if (st.active.remove(d.id) == null) {
            return;
        }
        st.immune.put(d.id, System.currentTimeMillis() + immunityMs);
        st.counters.put(d.id, 0);
        for (Disease.EffectSpec e : d.effects) {
            p.removePotionEffect(e.type());
        }
        p.sendMessage(c("&a💊 Bạn đã khỏi bệnh &e" + d.name + "&a (" + reason + ")."));
        p.playSound(p.getLocation(), "entity.player.levelup", 0.6f, 1.6f);
    }

    private void cureByKind(Player p, Cure.Kind kind, String reason) {
        PlayerState st = state(p);
        for (String id : new ArrayList<>(st.active.keySet())) {
            Disease d = diseases.get(id);
            if (d == null) {
                continue;
            }
            for (Cure cu : d.cures) {
                if (cu.kind == kind) {
                    cure(p, d, reason);
                    break;
                }
            }
        }
    }

    private void applySymptoms(Player p, Disease d) {
        for (Disease.EffectSpec e : d.effects) {
            p.addPotionEffect(new PotionEffect(e.type(), 160, e.amplifier(), true, false, true));
        }
    }

    // ===================================================================== tick

    private void tickAll() {
        String season = currentSeason();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                tickPlayer(p, season, now);
            } catch (Exception ex) {
                getLogger().warning("Loi tick " + p.getName() + ": " + ex);
            }
        }
    }

    private void tickPlayer(Player p, String season, long now) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        PlayerState st = state(p);

        // het han -> tu khoi
        Iterator<Map.Entry<String, Long>> it = st.active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() <= now) {
                it.remove();
                st.immune.put(e.getKey(), now + immunityMs);
                Disease d = diseases.get(e.getKey());
                if (d != null) {
                    p.sendMessage(c("&a💪 Bạn đã tự khỏi bệnh &e" + d.name + "&a."));
                }
            }
        }

        Ctx ctx = new Ctx(p, st, now);

        // lay benh
        if (p.getWorld().getEnvironment() == World.Environment.NORMAL) {
            for (Disease d : diseases.values()) {
                if (d.trigger.event || !d.season.equals(season) || st.active.containsKey(d.id)) {
                    continue;
                }
                Long im = st.immune.get(d.id);
                if (im != null && im > now) {
                    continue;
                }
                int cnt = st.counters.getOrDefault(d.id, 0);
                if (ctx.test(d)) {
                    cnt++;
                } else {
                    cnt = Math.max(0, cnt - 2);
                }
                if (cnt >= d.threshold) {
                    cnt = 0;
                    if (ThreadLocalRandom.current().nextDouble() < d.chance) {
                        infect(p, d, false);
                    }
                }
                st.counters.put(d.id, cnt);
            }
        }

        // chua bang boi / ngoi gan lua
        boolean needSwim = false;
        boolean needFire = false;
        for (String id : st.active.keySet()) {
            Disease d = diseases.get(id);
            if (d == null) {
                continue;
            }
            for (Cure cu : d.cures) {
                if (cu.kind == Cure.Kind.SWIM) {
                    needSwim = true;
                } else if (cu.kind == Cure.Kind.FIRE) {
                    needFire = true;
                }
            }
        }
        if (needSwim) {
            st.swim = p.isInWater() ? st.swim + 1 : 0;
            if (st.swim >= 2) {
                st.swim = 0;
                cureByKind(p, Cure.Kind.SWIM, "đã tắm mát");
            }
        } else {
            st.swim = 0;
        }
        if (needFire) {
            st.fire = ctx.nearHeat() ? st.fire + 1 : 0;
            if (st.fire >= 2) {
                st.fire = 0;
                cureByKind(p, Cure.Kind.FIRE, "đã sưởi ấm");
            }
        } else {
            st.fire = 0;
        }

        // trieu chung
        for (String id : new ArrayList<>(st.active.keySet())) {
            Disease d = diseases.get(id);
            if (d == null) {
                st.active.remove(id);
                continue;
            }
            applySymptoms(p, d);
            if (!d.symptomMessage.isEmpty() && ThreadLocalRandom.current().nextDouble() < 0.2) {
                actionBar(p, d.symptomMessage);
            }
        }
    }

    /** Du lieu moi truong cua mot nguoi choi trong mot lan kiem tra (tinh lazy). */
    private final class Ctx {
        final Player p;
        final PlayerState st;
        final long now;
        private Boolean outside;
        private Boolean heat;
        private Boolean warm;
        private Double temp;
        private boolean tempLoaded;
        private String biome;

        Ctx(Player p, PlayerState st, long now) {
            this.p = p;
            this.st = st;
            this.now = now;
        }

        boolean outside() {
            if (outside == null) {
                Location l = p.getLocation();
                outside = l.getWorld().getHighestBlockYAt(l) < l.getBlockY();
            }
            return outside;
        }

        boolean nearHeat() {
            if (heat == null) {
                heat = false;
                Location l = p.getLocation();
                World w = l.getWorld();
                int bx = l.getBlockX();
                int by = l.getBlockY();
                int bz = l.getBlockZ();
                outer:
                for (int x = -3; x <= 3; x++) {
                    for (int y = -2; y <= 2; y++) {
                        for (int z = -3; z <= 3; z++) {
                            if (HEAT.contains(w.getBlockAt(bx + x, by + y, bz + z).getType())) {
                                heat = true;
                                break outer;
                            }
                        }
                    }
                }
            }
            return heat;
        }

        int leatherPieces() {
            int n = 0;
            ItemStack[] arm = p.getInventory().getArmorContents();
            for (ItemStack a : arm) {
                if (a != null && a.getType().name().startsWith("LEATHER_")) {
                    n++;
                }
            }
            return n;
        }

        boolean warm() {
            if (warm == null) {
                warm = leatherPieces() >= 3 || nearHeat();
            }
            return warm;
        }

        Double temp() {
            if (!tempLoaded) {
                temp = temperature(p);
                tempLoaded = true;
            }
            return temp;
        }

        String biome() {
            if (biome == null) {
                try {
                    biome = p.getLocation().getBlock().getBiome().getKey().getKey().toLowerCase(Locale.ROOT);
                } catch (Throwable t) {
                    biome = "";
                }
            }
            return biome;
        }

        boolean snowy() {
            String b = biome();
            return b.contains("snow") || b.contains("frozen") || b.contains("ice")
                    || b.contains("grove") || b.contains("jagged");
        }

        boolean day() {
            return p.getWorld().getTime() < 12000;
        }

        boolean night() {
            long t = p.getWorld().getTime();
            return t >= 13000 && t <= 23000;
        }

        int count(boolean flowers) {
            Location l = p.getLocation();
            World w = l.getWorld();
            int bx = l.getBlockX();
            int by = l.getBlockY();
            int bz = l.getBlockZ();
            int n = 0;
            for (int x = -3; x <= 3; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -3; z <= 3; z++) {
                        Material m = w.getBlockAt(bx + x, by + y, bz + z).getType();
                        if (flowers ? Tag.FLOWERS.isTagged(m) : m == Material.WATER) {
                            n++;
                        }
                    }
                }
            }
            return n;
        }

        boolean test(Disease d) {
            switch (d.trigger) {
                case RAIN: {
                    if (!p.getWorld().hasStorm() || !outside() || warm()) {
                        return false;
                    }
                    String b = biome();
                    return !b.contains("desert") && !b.contains("badlands");
                }
                case COLD_TEMP: {
                    Double t = temp();
                    return t != null && t < d.param && !nearHeat();
                }
                case HEAT_TEMP: {
                    Double t = temp();
                    return t != null && t > d.param;
                }
                case FLOWERS:
                    return outside() && count(true) >= 3;
                case NIGHT_OUTSIDE:
                    return outside() && night();
                case CROWD: {
                    int n = 0;
                    for (Entity e : p.getNearbyEntities(6, 3, 6)) {
                        if (e instanceof Villager) {
                            n++;
                        } else if (e instanceof Player o && o.getGameMode() != GameMode.SPECTATOR) {
                            n++;
                        }
                    }
                    return n >= 3;
                }
                case WATER_SWIM:
                    return p.isInWater();
                case SWEAT_ARMOR: {
                    ItemStack chest = p.getInventory().getChestplate();
                    if (chest == null || !outside() || !day()) {
                        return false;
                    }
                    String n = chest.getType().name();
                    return n.contains("IRON") || n.contains("DIAMOND") || n.contains("NETHERITE")
                            || n.contains("GOLDEN") || n.contains("CHAINMAIL");
                }
                case SUN:
                    return outside() && day() && !p.getWorld().hasStorm()
                            && p.getInventory().getHelmet() == null && !p.isInWater();
                case MOSQUITO:
                    if (!outside() || !night()) {
                        return false;
                    }
                    String bm = biome();
                    return bm.contains("swamp") || bm.contains("mangrove") || count(false) >= 4;
                case HUNGRY_LOW:
                    return p.getFoodLevel() <= 6;
                case DEHYDRATION:
                    return outside() && day() && (now - st.lastDrink) > (long) (d.param * 1000);
                case SWAMP: {
                    String b = biome();
                    return b.contains("swamp") || b.contains("mangrove");
                }
                case HIGH_ALTITUDE:
                    return outside() && p.getLocation().getY() >= d.param;
                case SNOW_BIOME:
                    return outside() && snowy();
                case SNOW_GLARE:
                    return outside() && snowy() && day() && !p.getWorld().hasStorm();
                default:
                    return false;
            }
        }
    }

    // ===================================================================== events

    private void eventTrigger(Player p, Trigger t) {
        if (p.hasPermission("seasondiseases.immune")
                || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        PlayerState st = state(p);
        String season = currentSeason();
        long now = System.currentTimeMillis();
        for (Disease d : diseases.values()) {
            if (d.trigger != t || !d.season.equals(season) || st.active.containsKey(d.id)) {
                continue;
            }
            Long im = st.immune.get(d.id);
            if (im != null && im > now) {
                continue;
            }
            long last = st.eventTime.getOrDefault(d.id, 0L);
            if (now - last > eventWindowMs) {
                st.counters.put(d.id, 0);
            }
            st.eventTime.put(d.id, now);
            int cnt = st.counters.getOrDefault(d.id, 0) + 1;
            if (cnt >= d.threshold) {
                cnt = 0;
                if (ThreadLocalRandom.current().nextDouble() < d.chance) {
                    infect(p, d, false);
                }
            }
            st.counters.put(d.id, cnt);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        loadState(p);
        PlayerState st = state(p);
        if (!st.active.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (String id : st.active.keySet()) {
                Disease d = diseases.get(id);
                if (d != null) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(d.name);
                }
            }
            p.sendMessage(c("&c🤒 Bạn vẫn đang bị bệnh: &e" + names + "&c. Gõ &e/benh &cđể xem cách chữa."));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        storeState(id);
        states.remove(id);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (cureOnDeath) {
            PlayerState st = states.get(e.getEntity().getUniqueId());
            if (st != null) {
                st.active.clear();
                st.counters.clear();
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        Material m = item.getType();
        PlayerState st = state(p);

        if (DRINKS.contains(m)) {
            st.lastDrink = System.currentTimeMillis();
        }
        if (RAW.contains(m)) {
            eventTrigger(p, Trigger.EAT_RAW);
        }
        if (SPOILED.contains(m)) {
            eventTrigger(p, Trigger.EAT_SPOILED);
        }
        if (BERRY.contains(m)) {
            eventTrigger(p, Trigger.EAT_BERRY);
        }

        for (String id : new ArrayList<>(st.active.keySet())) {
            Disease d = diseases.get(id);
            if (d == null) {
                continue;
            }
            for (Cure cu : d.cures) {
                if (cu.matches(item)) {
                    cure(p, d, "đã dùng " + cu.display());
                    break;
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (CROPS.contains(e.getBlock().getType())) {
            eventTrigger(e.getPlayer(), Trigger.BREAK_CROP);
        }
    }

    @EventHandler
    public void onBedLeave(PlayerBedLeaveEvent e) {
        Player p = e.getPlayer();
        if (p.getSleepTicks() >= 100) {
            cureByKind(p, Cure.Kind.SLEEP, "đã ngủ một giấc ngon");
        }
    }

    @EventHandler
    public void onTimeSkip(TimeSkipEvent e) {
        if (e.getSkipReason() != TimeSkipEvent.SkipReason.NIGHT_SKIP) {
            return;
        }
        for (Player p : e.getWorld().getPlayers()) {
            if (p.isSleeping()) {
                cureByKind(p, Cure.Kind.SLEEP, "đã ngủ một giấc ngon");
            }
        }
    }

    // ===================================================================== command

    private void showDetail(CommandSender s, Disease d, Long end) {
        s.sendMessage(c("&8&m                                        "));
        String left = end == null ? "" : " &7(còn " + fmt(end - System.currentTimeMillis()) + ")";
        s.sendMessage(c("&c🤒 &e" + d.name + " &8[" + seasonName(d.season) + "]" + left));
        if (!d.description.isEmpty()) {
            s.sendMessage(c("&7" + d.description));
        }
        if (!d.effects.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Disease.EffectSpec e : d.effects) {
                names.add(EFFECT_NAMES.getOrDefault(e.key(), e.key()));
            }
            s.sendMessage(c("&7Triệu chứng: &f" + String.join(", ", names)));
        }
        s.sendMessage(c("&aCách chữa (chỉ cần một trong các cách):"));
        for (Cure cu : d.cures) {
            s.sendMessage(c("  &8▪ &f" + cu.display()));
        }
        if (!d.prevention.isEmpty()) {
            s.sendMessage(c("&7Phòng ngừa: &f" + d.prevention));
        }
        if (end != null) {
            s.sendMessage(c("&8Không chữa thì sẽ tự khỏi sau thời gian trên."));
        }
    }

    private void showList(CommandSender s, String season) {
        s.sendMessage(c("&6Các bệnh của &e" + seasonName(season) + "&6:"));
        for (Disease d : diseases.values()) {
            if (d.season.equals(season)) {
                s.sendMessage(c("  &8▪ &f" + d.name + " &8(" + d.id + ")"));
            }
        }
        s.sendMessage(c("&7Xem chi tiết: &e/benh info <tên bệnh>"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean admin = sender.hasPermission("seasondiseases.admin");

        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                showList(sender, currentSeason());
                return true;
            }
            PlayerState st = state(p);
            if (st.active.isEmpty()) {
                String season = currentSeason();
                p.sendMessage(c("&a💪 Bạn đang khỏe mạnh."));
                showList(p, season);
                p.sendMessage(c("&7Xem tất cả: &e/benh list <spring|summer|autumn|winter>"));
            } else {
                for (Map.Entry<String, Long> e : new ArrayList<>(st.active.entrySet())) {
                    Disease d = diseases.get(e.getKey());
                    if (d != null) {
                        showDetail(p, d, e.getValue());
                    }
                }
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list": {
                String season = args.length > 1 ? args[1].toUpperCase(Locale.ROOT) : currentSeason();
                if (!SEASON_NAMES.containsKey(season)) {
                    sender.sendMessage(c("&cMùa hợp lệ: spring, summer, autumn, winter"));
                    return true;
                }
                showList(sender, season);
                return true;
            }
            case "info": {
                if (args.length < 2) {
                    sender.sendMessage(c("&cDùng: /benh info <tên bệnh>"));
                    return true;
                }
                String key = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                Disease d = find(key);
                if (d == null) {
                    sender.sendMessage(c("&cKhông tìm thấy bệnh '" + key + "'. Dùng /benh list."));
                } else {
                    showDetail(sender, d, null);
                }
                return true;
            }
            case "give": {
                if (!admin) {
                    sender.sendMessage(c("&cBạn không có quyền."));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(c("&cDùng: /benh give <người chơi> <bệnh>"));
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                Disease d = find(args[2]);
                if (t == null || d == null) {
                    sender.sendMessage(c("&cKhông tìm thấy người chơi hoặc bệnh."));
                    return true;
                }
                infect(t, d, true);
                sender.sendMessage(c("&aĐã cho " + t.getName() + " bị " + d.name + "."));
                return true;
            }
            case "cure": {
                if (!admin) {
                    sender.sendMessage(c("&cBạn không có quyền."));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(c("&cDùng: /benh cure <người chơi> [bệnh]"));
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    sender.sendMessage(c("&cKhông tìm thấy người chơi."));
                    return true;
                }
                PlayerState st = state(t);
                if (args.length > 2) {
                    Disease d = find(args[2]);
                    if (d != null) {
                        cure(t, d, "quản trị viên chữa");
                    }
                } else {
                    for (String id : new ArrayList<>(st.active.keySet())) {
                        Disease d = diseases.get(id);
                        if (d != null) {
                            cure(t, d, "quản trị viên chữa");
                        }
                    }
                }
                sender.sendMessage(c("&aĐã chữa bệnh cho " + t.getName() + "."));
                return true;
            }
            case "reload": {
                if (!admin) {
                    sender.sendMessage(c("&cBạn không có quyền."));
                    return true;
                }
                loadAll();
                sender.sendMessage(c("&aĐã nạp lại " + diseases.size() + " bệnh."));
                return true;
            }
            default:
                sender.sendMessage(c("&cDùng: /benh [list|info <bệnh>]"));
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission("seasondiseases.admin");
        if (args.length == 1) {
            out.add("list");
            out.add("info");
            if (admin) {
                out.add("give");
                out.add("cure");
                out.add("reload");
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "list":
                    out.addAll(List.of("spring", "summer", "autumn", "winter"));
                    break;
                case "info":
                    out.addAll(diseases.keySet());
                    break;
                case "give":
                case "cure":
                    if (admin) {
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            out.add(p.getName());
                        }
                    }
                    break;
                default:
                    break;
            }
        } else if (args.length == 3 && admin) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("give") || sub.equals("cure")) {
                out.addAll(diseases.keySet());
            }
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}

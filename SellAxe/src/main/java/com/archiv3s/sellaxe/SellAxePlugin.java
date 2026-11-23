package com.archiv3s.sellaxe;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.Tag;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class SellAxePlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final String ESSC_SELL_HOLDER = "net.godlycow.org.essc.modules.shop.sell.SellHolder";

    private static final long SKILL_COOLDOWN_MS = 2000L;
    private static final double BLAZE_RADIUS = 7.0;         // Blaze (crouch): the ring of flame reaches 7 blocks out
    private static final double BLAZE_SPEED = 0.8;          // blocks per tick, so the edge arrives in about half a second
    private static final double BLAZE_DAMAGE = 2.0;         // one heart per entity the ring reaches
    private static final int BLAZE_FIRE_TICKS = 60;         // 3 seconds of burning
    private static final long BLAZE_FIRE_BLOCK_TICKS = 80L; // Blaze fire blocks burn out after 4 seconds
    private static final float FIRE_BLAST_YIELD = 2.5F;     // Fire Blast (attack): explosion size on impact

    private static final double ARENA_RADIUS = 37.0;        // fire presence and crack radius
    private static final long PVP_WINDOW_MS = 20_000L;      // a player hit by or hitting a player stays in PvP this long
    private static final double PRESENCE_CAP_SECONDS = 120.0;

    private static final int CRACK_LINES_PER_USE = 6;       // each wand use adds this many branches
    private static final int CRACK_MAX_LINES = 60;          // 10 uses; the oldest branches drop off after that
    private static final long CRACK_REDRAW_TICKS = 60L;     // persistent cracks are redrawn every 3 seconds

    private final Map<Material, Double> prices = new HashMap<>();
    private final Map<UUID, Long> readyAt = new HashMap<>();
    private final Map<UUID, BukkitTask> cooldownTasks = new HashMap<>();
    private final Map<UUID, XpState> savedXp = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, Double> fightSeconds = new HashMap<>();
    private final Map<UUID, CrackField> crackFields = new HashMap<>();
    private NamespacedKey axeKey;
    private NamespacedKey wandKey;
    private Economy economy;

    private record XpState(int level, float exp) {}

    /** A player's persistent cracks: one polyline of floor points per branch. */
    private static final class CrackField {
        World world;
        final List<List<double[]>> lines = new ArrayList<>();

        CrackField(World world) {
            this.world = world;
        }
    }

    @Override
    public void onEnable() {
        axeKey = new NamespacedKey(this, "sellaxe");
        wandKey = new NamespacedKey(this, "firewand");

        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
        } else {
            getLogger().warning("Vault economy not found. SellAxe will refuse to break containers.");
        }

        loadPrices();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("sellaxe").setExecutor(this);

        // Fire resistance while a Fire Wand is in the inventory, refreshed every 3 seconds for 10 seconds.
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player online : getServer().getOnlinePlayers()) {
                if (hasFireWandInInventory(online.getInventory())) {
                    online.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 200, 0, true, false, true));
                }
            }
        }, 20L, 60L);

        startArenaPresence();
        startCrackRedraw();
    }

    private boolean hasFireWandInInventory(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            if (isFireWand(inv.getItem(i))) return true;
        }
        return false;
    }

    /** Reads sell prices from every EconomyShopGUI shop YAML (files with a top-level "pages" section). */
    private void loadPrices() {
        prices.clear();
        File shopRoot = new File(getDataFolder().getParentFile(), "EconomyShopGUI");
        if (!shopRoot.isDirectory()) {
            getLogger().warning("EconomyShopGUI folder not found at " + shopRoot.getPath() + ". No prices loaded.");
            return;
        }

        int shopFiles = 0;
        for (File file : listYaml(shopRoot)) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection pages = cfg.getConfigurationSection("pages");
            if (pages == null) {
                continue;
            }
            shopFiles++;
            for (String pageKey : pages.getKeys(false)) {
                ConfigurationSection page = pages.getConfigurationSection(pageKey);
                if (page == null) {
                    continue;
                }
                ConfigurationSection items = page.getConfigurationSection("items");
                if (items == null) {
                    continue;
                }
                for (String slot : items.getKeys(false)) {
                    ConfigurationSection entry = items.getConfigurationSection(slot);
                    if (entry == null) {
                        continue;
                    }
                    Material material = Material.matchMaterial(entry.getString("material", ""));
                    double sell = entry.getDouble("sell", -1);
                    if (material == null || sell <= 0) {
                        continue;
                    }
                    prices.putIfAbsent(material, sell);
                }
            }
        }
        getLogger().info("Loaded " + prices.size() + " sell prices from " + shopFiles + " EconomyShopGUI shop files.");
    }

    private List<File> listYaml(File dir) {
        List<File> out = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) {
            return out;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                out.addAll(listYaml(child));
            } else if (child.getName().endsWith(".yml")) {
                out.add(child);
            }
        }
        return out;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!isSellAxe(player.getInventory().getItemInMainHand())) {
            return;
        }
        if (!(event.getBlock().getState() instanceof Container container)) {
            return;
        }
        if (!player.hasPermission("sellaxe.use")) {
            return;
        }
        if (economy == null) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "SellAxe can't pay out because Vault economy is missing.");
            return;
        }
        sellInventory(player, container.getInventory());
    }

    @EventHandler
    public void onSellAxeHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!isSellAxe(player.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
    }

    /** Any player-vs-player hit (melee or fireball) puts both players into PvP for a short window. */
    @EventHandler(ignoreCancelled = true)
    public void onPvpHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (event.getDamager() instanceof Player direct) {
            attacker = direct;
        } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker == null || attacker.equals(victim)) return;
        long until = System.currentTimeMillis() + PVP_WINDOW_MS;
        combatUntil.put(victim.getUniqueId(), until);
        combatUntil.put(attacker.getUniqueId(), until);
    }

    /** Fire Wand crouch: Blaze, a ring of flame out to 7 blocks around you. */
    @EventHandler(ignoreCancelled = true)
    public void onFireWandCrouch(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Player player = event.getPlayer();
        if (!isFireWand(player.getInventory().getItemInMainHand())) return;
        if (!player.hasPermission("sellaxe.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this.");
            return;
        }
        blaze(player);
    }

    /** Fire Wand attack on air or a block: Fire Blast, a fireball along your aim. */
    @EventHandler(ignoreCancelled = true)
    public void onFireWandAttack(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!isFireWand(player.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
        if (!player.hasPermission("sellaxe.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this.");
            return;
        }
        fireBlast(player);
    }

    /** Fire Wand attack on an entity: Fire Blast, and the wand does no melee damage. */
    @EventHandler(ignoreCancelled = true)
    public void onFireWandHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!isFireWand(player.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
        if (!player.hasPermission("sellaxe.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this.");
            return;
        }
        fireBlast(player);
    }

    /**
     * Blaze: a ring of flame expands outward to 7 blocks over about half a second, centred on the caster.
     * Each living entity the ring reaches catches fire and takes one heart, once. No blocks are changed.
     */
    private void blaze(Player player) {
        if (!skillReady(player)) return;
        startCooldown(player);
        drawCracks(player);

        Location center = player.getLocation().clone();
        World world = center.getWorld();
        Set<UUID> hit = new HashSet<>();
        new BukkitRunnable() {
            double radius = 0.0;

            @Override
            public void run() {
                if (radius >= BLAZE_RADIUS) {
                    cancel();
                    return;
                }
                radius = Math.min(BLAZE_RADIUS, radius + BLAZE_SPEED);
                drawBlazeRing(world, center, radius);
                hurtInBlaze(player, center, radius, hit);
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void drawBlazeRing(World world, Location center, double radius) {
        int points = Math.max(12, (int) (radius * 3));
        for (int i = 0; i < points; i++) {
            double rad = Math.toRadians(360.0 * i / points);
            double x = center.getX() + radius * Math.cos(rad);
            double z = center.getZ() + radius * Math.sin(rad);
            int floorY = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
            world.spawnParticle(Particle.FLAME, x, floorY + 1.0, z, 2, 0.1, 0.3, 0.1, 0.01);
            placeBlazeFire(world, (int) Math.floor(x), floorY, (int) Math.floor(z));
        }
    }

    /** Puts a real fire block on open ground: only on air above a solid, non-flammable tile, with no flammable neighbours. */
    private void placeBlazeFire(World world, int x, int floorY, int z) {
        Block ground = world.getBlockAt(x, floorY, z);
        Block target = ground.getRelative(BlockFace.UP);
        if (!target.getType().isAir() || !ground.getType().isSolid() || isFlammable(ground)) return;
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (isFlammable(target.getRelative(face))) return;
        }
        target.setType(Material.FIRE, false);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (target.getType() == Material.FIRE) target.setType(Material.AIR, false);
        }, BLAZE_FIRE_BLOCK_TICKS);
    }

    /** Wood, leaves, planks, wool and hay: blocks fire can spread into, so the blaze keeps away from them. */
    private static boolean isFlammable(Block block) {
        Material m = block.getType();
        return Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m) || Tag.PLANKS.isTagged(m)
                || Tag.WOOL.isTagged(m) || m == Material.HAY_BLOCK;
    }

    private void hurtInBlaze(Player caster, Location center, double radius, Set<UUID> hit) {
        World world = center.getWorld();
        for (Entity entity : world.getNearbyEntities(center, BLAZE_RADIUS + 1, 4, BLAZE_RADIUS + 1)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(caster)) continue;
            if (hit.contains(entity.getUniqueId())) continue;
            Location at = entity.getLocation();
            double dx = at.getX() - center.getX();
            double dz = at.getZ() - center.getZ();
            if (Math.sqrt(dx * dx + dz * dz) > radius) continue;
            hit.add(entity.getUniqueId());
            living.setFireTicks(BLAZE_FIRE_TICKS);
            living.damage(BLAZE_DAMAGE, caster);
        }
    }

    /**
     * Each wand use adds CRACK_LINES_PER_USE new red branches from the caster's feet out to radius 37, on the
     * floor surface. Branches accumulate in the player's crack field, so the cracks grow with every use, and the
     * field is redrawn every 3 seconds. The oldest branches drop off after CRACK_MAX_LINES. No blocks are changed.
     */
    private void drawCracks(Player player) {
        World world = player.getWorld();
        Location origin = player.getLocation();
        int floorY = world.getHighestBlockYAt(origin.getBlockX(), origin.getBlockZ());
        double y = floorY + 1.02;
        CrackField field = crackFields.computeIfAbsent(player.getUniqueId(), id -> new CrackField(world));
        if (field.world != world) {
            field.world = world;
            field.lines.clear();
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int line = 0; line < CRACK_LINES_PER_USE; line++) {
            // Each new branch starts in its own slice of the circle, so repeated uses fill the gaps.
            double angle = (line + rnd.nextDouble()) * (Math.PI * 2 / CRACK_LINES_PER_USE);
            double dirX = Math.cos(angle);
            double dirZ = Math.sin(angle);
            double x = origin.getX();
            double z = origin.getZ();
            double travelled = 0;
            List<double[]> points = new ArrayList<>();
            points.add(new double[] {x, y, z});
            while (travelled < ARENA_RADIUS) {
                double step = 1.0 + rnd.nextDouble(1.5);
                x += dirX * step + rnd.nextDouble(-0.4, 0.4);
                z += dirZ * step + rnd.nextDouble(-0.4, 0.4);
                travelled += step;
                points.add(new double[] {x, y, z});
            }
            field.lines.add(points);
        }
        while (field.lines.size() > CRACK_MAX_LINES) {
            field.lines.remove(0);
        }
        drawCrackField(field);
    }

    private void drawCrackField(CrackField field) {
        Particle.DustOptions red = new Particle.DustOptions(Color.fromRGB(200, 20, 0), 1.0F);
        for (List<double[]> line : field.lines) {
            for (double[] p : line) {
                field.world.spawnParticle(Particle.DUST, p[0], p[1], p[2], 1, 0.05, 0.0, 0.05, 0.0, red);
            }
        }
    }

    /** Keeps every player's cracks visible between uses. Cracks stay where they were drawn, and are removed when the player logs out. */
    private void startCrackRedraw() {
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Map.Entry<UUID, CrackField> entry : crackFields.entrySet()) {
                Player owner = getServer().getPlayer(entry.getKey());
                if (owner == null || owner.getWorld() != entry.getValue().world) continue;
                drawCrackField(entry.getValue());
            }
        }, CRACK_REDRAW_TICKS, CRACK_REDRAW_TICKS);
    }

    /**
     * Fire presence across the 37-block arena while the holder is in PvP. Density grows with fight time.
     * Runs every 10 ticks at about half the original particle count for mobile.
     */
    private void startArenaPresence() {
        getServer().getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            for (Player player : getServer().getOnlinePlayers()) {
                UUID id = player.getUniqueId();
                if (!hasFireWandInInventory(player.getInventory()) || combatUntil.getOrDefault(id, 0L) <= now) {
                    fightSeconds.remove(id);
                    continue;
                }
                double seconds = Math.min(PRESENCE_CAP_SECONDS, fightSeconds.getOrDefault(id, 0.0) + 0.5);
                fightSeconds.put(id, seconds);
                spawnPresence(player, Math.min(1.0, seconds / 90.0));
            }
        }, 20L, 10L);
    }

    private void spawnPresence(Player player, double intensity) {
        World world = player.getWorld();
        Location center = player.getLocation();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        int flames = 4 + (int) Math.round(14 * intensity);
        for (int i = 0; i < flames; i++) {
            double angle = rnd.nextDouble(Math.PI * 2);
            double r = ARENA_RADIUS * Math.sqrt(rnd.nextDouble());
            double x = center.getX() + r * Math.cos(angle);
            double z = center.getZ() + r * Math.sin(angle);
            int floorY = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
            Particle type = rnd.nextDouble() < 0.7 ? Particle.SMALL_FLAME : Particle.FLAME;
            world.spawnParticle(type, x, floorY + 1.05, z, 1, 0.2, 0.05, 0.2, 0.01);
        }

        if (rnd.nextDouble() < intensity * 0.4) {       // lava sparks join once the fight is well under way
            double angle = rnd.nextDouble(Math.PI * 2);
            double r = ARENA_RADIUS * Math.sqrt(rnd.nextDouble());
            double x = center.getX() + r * Math.cos(angle);
            double z = center.getZ() + r * Math.sin(angle);
            int floorY = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
            world.spawnParticle(Particle.LAVA, x, floorY + 1.05, z, 1);
        }
    }

    /** Fire Blast: a large fireball flies along your aim and explodes on impact. */
    private void fireBlast(Player player) {
        if (!skillReady(player)) return;
        startCooldown(player);
        drawCracks(player);

        Fireball fireball = player.launchProjectile(Fireball.class);
        fireball.setIsIncendiary(true);
        fireball.setYield(FIRE_BLAST_YIELD);
    }

    private boolean skillReady(Player player) {
        Long ready = readyAt.get(player.getUniqueId());
        if (ready != null && System.currentTimeMillis() < ready) {
            player.sendMessage(ChatColor.RED + "Fire ability recharging.");
            return false;
        }
        return true;
    }

    /**
     * Starts the shared 2-second cooldown. The XP bar starts empty and fills to full over the cooldown,
     * then your real level and XP are restored.
     */
    private void startCooldown(Player player) {
        UUID id = player.getUniqueId();
        readyAt.put(id, System.currentTimeMillis() + SKILL_COOLDOWN_MS);
        if (!savedXp.containsKey(id)) {
            savedXp.put(id, new XpState(player.getLevel(), player.getExp()));
        }
        BukkitTask old = cooldownTasks.remove(id);
        if (old != null) {
            old.cancel();
        }
        player.setLevel(0);
        player.setExp(0F);

        BukkitRunnable bar = new BukkitRunnable() {
            @Override
            public void run() {
                Long ready = readyAt.get(id);
                long now = System.currentTimeMillis();
                if (ready == null || now >= ready) {
                    player.setExp(1F);
                    cancel();
                    cooldownTasks.remove(id);
                    SellAxePlugin.this.getServer().getScheduler().runTaskLater(SellAxePlugin.this,
                            () -> finishCooldown(player), 10L);
                    return;
                }
                float fill = 1F - (float) (ready - now) / SKILL_COOLDOWN_MS;
                player.setExp(Math.max(0F, Math.min(1F, fill)));
            }
        };
        cooldownTasks.put(id, bar.runTaskTimer(this, 1L, 1L));
    }

    private void finishCooldown(Player player) {
        UUID id = player.getUniqueId();
        readyAt.remove(id);
        BukkitTask task = cooldownTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
        XpState state = savedXp.remove(id);
        if (state != null) {
            player.setLevel(state.level());
            player.setExp(state.exp());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (savedXp.containsKey(id)) {
            finishCooldown(player);
        }
        combatUntil.remove(id);
        fightSeconds.remove(id);
        crackFields.remove(id);
    }

    /** Blocks EssentialsC /sell, /sellall, /sellgui and /quicksell while the player carries a Sell Axe. */
    @EventHandler(ignoreCancelled = true)
    public void onSellCommand(PlayerCommandPreprocessEvent event) {
        String label = event.getMessage().split(" ")[0].substring(1).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!label.equals("sell") && !label.equals("sellall") && !label.equals("sellgui") && !label.equals("quicksell")) return;
        Player player = event.getPlayer();
        if (!hasSellAxe(player.getInventory())) return;
        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "You can't use /" + label + " while carrying a Sell Axe.");
    }

    /** Blocks moving a Sell Axe into EssentialsC's sell GUI by click, shift-click, hotbar swap or drag. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSellGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!isEssentialsSellGui(top)) return;

        boolean clickedTop = event.getRawSlot() >= 0 && event.getRawSlot() < top.getSize();
        boolean blocked = false;
        if (clickedTop && isSellAxe(event.getCursor())) {
            blocked = true;
        } else if (!clickedTop && event.isShiftClick() && isSellAxe(event.getCurrentItem())) {
            blocked = true;
        } else if (clickedTop && event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0
                && isSellAxe(player.getInventory().getItem(event.getHotbarButton()))) {
            blocked = true;
        }

        if (blocked) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You can't sell a Sell Axe in the sell GUI.");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSellGuiDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!isEssentialsSellGui(top)) return;
        if (!isSellAxe(event.getOldCursor())) return;

        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot < top.getSize()) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You can't sell a Sell Axe in the sell GUI.");
                return;
            }
        }
    }

    private boolean isEssentialsSellGui(Inventory inv) {
        return inv.getHolder() != null && inv.getHolder().getClass().getName().equals(ESSC_SELL_HOLDER);
    }

    private boolean hasSellAxe(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            if (isSellAxe(inv.getItem(i))) return true;
        }
        return false;
    }

    private void sellInventory(Player player, Inventory inv) {
        double total = 0;
        int count = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir() || !isPlainItem(item)) {
                continue;
            }
            Double price = prices.get(item.getType());
            if (price == null) {
                continue;
            }
            total += price * item.getAmount();
            count += item.getAmount();
            inv.setItem(i, null);
        }

        if (count == 0) {
            player.sendMessage(ChatColor.YELLOW + "Nothing in that container has a sell price.");
            return;
        }

        total = Math.round(total * 100.0) / 100.0;
        economy.depositPlayer(player, total);
        player.sendMessage(ChatColor.GREEN + "Sold " + count + " items for " + economy.format(total) + ".");
    }

    /** Skips enchanted or renamed items so they sell only at the plain material price when they are plain. */
    private boolean isPlainItem(ItemStack item) {
        if (!item.hasItemMeta()) {
            return true;
        }
        ItemMeta meta = item.getItemMeta();
        return !meta.hasEnchants() && !meta.hasDisplayName();
    }

    private boolean isSellAxe(ItemStack item) {
        return hasFlag(item, axeKey);
    }

    private boolean isFireWand(ItemStack item) {
        return hasFlag(item, wandKey);
    }

    private boolean hasFlag(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!player.hasPermission("sellaxe.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("egg")) {
            giveItem(player, createEmber());
            player.sendMessage(ChatColor.GOLD + "The axe has been sold a lot of chests, and it remembers every one. Keep the ember.");
            return true;
        }
        // Op-only. Non-ops fall through to the normal Sell Axe, so the subcommand is not revealed to them.
        if (args.length > 0 && args[0].equalsIgnoreCase("fire") && player.isOp()) {
            giveItem(player, createFireWand());
            player.sendMessage(ChatColor.GREEN + "You received a Fire Wand. Crouch for Blaze, a ring of fire out to 7 blocks. Attack for Fire Blast, a fireball along your aim.");
            return true;
        }
        giveItem(player, createSellAxe());
        player.sendMessage(ChatColor.GREEN + "You received a Sell Axe.");
        return true;
    }

    private void giveItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack extra : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
    }

    private ItemStack createSellAxe() {
        ItemStack axe = new ItemStack(Material.NETHERITE_AXE);
        ItemMeta meta = axe.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Sell Axe");
        meta.setLore(List.of(
                ChatColor.GRAY + "Break a chest, barrel or shulker box",
                ChatColor.GRAY + "to sell its items at shop prices."));
        meta.getPersistentDataContainer().set(axeKey, PersistentDataType.BYTE, (byte) 1);
        axe.setItemMeta(meta);
        return axe;
    }

    private ItemStack createFireWand() {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "Fire Wand");
        meta.setLore(List.of(
                ChatColor.GRAY + "Crouch: Blaze, a ring of fire out to 7 blocks",
                ChatColor.GRAY + "Attack: Fire Blast, a fireball along your aim",
                ChatColor.GRAY + "In PvP: fire presence grows across the arena"));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(meta);
        return wand;
    }

    private ItemStack createEmber() {
        ItemStack ember = new ItemStack(Material.BLAZE_POWDER);
        ItemMeta meta = ember.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Ember");
        meta.setLore(List.of(ChatColor.DARK_GRAY + "It is still warm."));
        ember.setItemMeta(meta);
        return ember;
    }
}

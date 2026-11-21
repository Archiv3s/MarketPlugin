package com.archiv3s.sellaxe;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SellAxePlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final String ESSC_SELL_HOLDER = "net.godlycow.org.essc.modules.shop.sell.SellHolder";

    private static final long SKILL_COOLDOWN_MS = 2000L;
    private static final long WALL_LIFETIME_TICKS = 100L;   // 5 seconds
    private static final long PANE_LIFETIME_TICKS = 60L;    // 3 seconds if not clicked
    private static final double WALL_RADIUS = 4.0;
    private static final double WALL_HEIGHT = 3.0;
    private static final double WALL_DAMAGE = 2.0;          // one heart per hit
    private static final int WALL_DAMAGE_INTERVAL = 10;     // ticks between damage checks
    private static final int WALL_FIRE_TICKS = 60;          // 3 seconds of burning

    private final Map<Material, Double> prices = new HashMap<>();
    private final Map<UUID, Long> readyAt = new HashMap<>();
    private final Map<UUID, BukkitTask> cooldownTasks = new HashMap<>();
    private final Map<UUID, XpState> savedXp = new HashMap<>();
    private final Map<UUID, Block> activePanes = new HashMap<>();
    private NamespacedKey axeKey;
    private NamespacedKey wandKey;
    private Economy economy;

    private record XpState(int level, float exp) {}

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

    @EventHandler(ignoreCancelled = true)
    public void onFireWandUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!isFireWand(player.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
        if (!player.hasPermission("sellaxe.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this.");
            return;
        }

        Block clicked = event.getClickedBlock();
        Block pane = activePanes.get(player.getUniqueId());
        if (clicked != null && pane != null && clicked.equals(pane)) {
            fireBlast(player);          // clicking the glass pane fires the blast
        } else if (player.isSneaking()) {
            summonPane(player);         // crouch + right-click places the glass pane
        } else {
            fireWall(player);           // right-click makes the flame ring around you
        }
    }

    /**
     * Flame ring centred on the caster: radius 4, height 3, lasting 5 seconds. Particles are only visuals;
     * the server checks the ring every 10 ticks and sets living entities inside it on fire and damages them.
     */
    private void fireWall(Player player) {
        if (!skillReady(player)) return;
        startCooldown(player);

        Location center = player.getLocation().clone();
        World world = center.getWorld();
        new BukkitRunnable() {
            int age = 0;

            @Override
            public void run() {
                if (age >= WALL_LIFETIME_TICKS) {
                    cancel();
                    return;
                }
                if (age % 4 == 0) {
                    drawRing(world, center);
                }
                if (age % WALL_DAMAGE_INTERVAL == 0) {
                    hurtInsideRing(player, center);
                }
                age++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void drawRing(World world, Location center) {
        for (int angle = 0; angle < 360; angle += 12) {
            double rad = Math.toRadians(angle);
            double x = center.getX() + WALL_RADIUS * Math.cos(rad);
            double z = center.getZ() + WALL_RADIUS * Math.sin(rad);
            for (double y = 0.2; y < WALL_HEIGHT; y += 0.8) {
                world.spawnParticle(Particle.FLAME, x, center.getY() + y, z, 1, 0D, 0.05D, 0D, 0.01D);
            }
        }
    }

    private void hurtInsideRing(Player caster, Location center) {
        World world = center.getWorld();
        for (Entity entity : world.getNearbyEntities(center, WALL_RADIUS + 1, WALL_HEIGHT + 1, WALL_RADIUS + 1)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(caster)) continue;
            Location at = entity.getLocation();
            double dx = at.getX() - center.getX();
            double dz = at.getZ() - center.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double dy = at.getY() - center.getY();
            if (horizontal < WALL_RADIUS - 1.0 || horizontal > WALL_RADIUS + 0.5) continue;
            if (dy < -1.0 || dy > WALL_HEIGHT) continue;
            living.setFireTicks(WALL_FIRE_TICKS);
            living.damage(WALL_DAMAGE, caster);
        }
    }

    /** Crouch + right-click: a flat glass pane in front of your head. It vanishes after 3 seconds or when clicked. */
    private void summonPane(Player player) {
        UUID id = player.getUniqueId();
        Block spot = findPaneSpot(player);
        if (spot == null) {
            player.sendMessage(ChatColor.YELLOW + "No open air in front for the glass pane.");
            return;
        }
        removePane(id);

        Vector dir = player.getEyeLocation().getDirection();
        MultipleFacing pane = (MultipleFacing) Material.GLASS_PANE.createBlockData();
        if (Math.abs(dir.getX()) >= Math.abs(dir.getZ())) {
            pane.setFace(BlockFace.NORTH, true);
            pane.setFace(BlockFace.SOUTH, true);
        } else {
            pane.setFace(BlockFace.EAST, true);
            pane.setFace(BlockFace.WEST, true);
        }
        spot.setBlockData(pane, false);
        activePanes.put(id, spot);

        final Block placed = spot;
        getServer().getScheduler().runTaskLater(this, () -> {
            if (placed.equals(activePanes.get(id))) {
                removePane(id);
            }
        }, PANE_LIFETIME_TICKS);
    }

    private Block findPaneSpot(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        for (double distance : new double[] {2.0, 1.5, 1.0}) {
            Block candidate = eye.clone().add(dir.clone().multiply(distance)).getBlock();
            if (candidate.getType().isAir()) {
                return candidate;
            }
        }
        return null;
    }

    private void removePane(UUID id) {
        Block pane = activePanes.remove(id);
        if (pane != null && pane.getType() == Material.GLASS_PANE) {
            pane.setBlockData(Material.AIR.createBlockData(), false);
        }
    }

    /** Right-click on the glass pane: a large fireball flies along your aim, and the pane is consumed. */
    private void fireBlast(Player player) {
        if (!skillReady(player)) return;
        startCooldown(player);
        removePane(player.getUniqueId());

        Fireball fireball = player.launchProjectile(Fireball.class);
        fireball.setIsIncendiary(true);
        fireball.setYield(2.5F);
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
        if (savedXp.containsKey(player.getUniqueId())) {
            finishCooldown(player);
        }
        removePane(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreakSkillBlock(BlockBreakEvent event) {
        // Keeps glass panes from being picked up as normal blocks if a player breaks one.
        if (activePanes.containsValue(event.getBlock())) {
            activePanes.values().remove(event.getBlock());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak2(BlockBreakEvent event) {
        // placeholder removed below
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
            player.sendMessage(ChatColor.GREEN + "You received a Fire Wand. Right-click for a flame ring, crouch + right-click for a glass pane, then click the pane for a fire blast.");
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
                ChatColor.GRAY + "Right-click: flame ring around you (5 s)",
                ChatColor.GRAY + "Crouch + right-click: glass pane ahead",
                ChatColor.GRAY + "Click the pane: fire blast"));
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

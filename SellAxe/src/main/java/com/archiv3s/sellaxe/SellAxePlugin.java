package com.archiv3s.sellaxe;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Fireball;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
    private static final long WALL_COOLDOWN_MS = 5000;
    private static final long BLAST_COOLDOWN_MS = 1500;
    private static final int WALL_DISTANCE = 6;
    private static final int WALL_REACH = 5;
    private static final double WALL_INNER_RADIUS = 3.5;
    private static final double WALL_OUTER_RADIUS = 4.5;
    private static final int WALL_HEIGHT = 3;

    private final Map<Material, Double> prices = new HashMap<>();
    private final Map<UUID, Long> wallReady = new HashMap<>();
    private final Map<UUID, Long> blastReady = new HashMap<>();
    private NamespacedKey axeKey;
    private NamespacedKey wandKey;
    private Economy economy;

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

        // Fire resistance while a Fire Wand is in the inventory. Refreshed every 3 seconds with a 10-second duration.
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
        if (player.isSneaking()) {
            fireBlast(player);
        } else {
            fireWall(player);
        }
    }

    /** Places a hollow ring of fire, 9 blocks across and 3 high, only into air, so existing blocks are never replaced. */
    private void fireWall(Player player) {
        if (onCooldown(player, wallReady, WALL_COOLDOWN_MS)) return;
        Vector facing = player.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 1.0E-6) {
            player.sendMessage(ChatColor.RED + "Look horizontally to place the fire wall.");
            return;
        }
        facing.normalize();
        Location center = player.getLocation().add(facing.multiply(WALL_DISTANCE));
        World world = player.getWorld();
        int cx = center.getBlockX();
        int cy = player.getLocation().getBlockY();
        int cz = center.getBlockZ();
        int placed = 0;
        for (int dx = -WALL_REACH; dx <= WALL_REACH; dx++) {
            for (int dz = -WALL_REACH; dz <= WALL_REACH; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < WALL_INNER_RADIUS || distance > WALL_OUTER_RADIUS) continue;
                for (int dy = 0; dy < WALL_HEIGHT; dy++) {
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (block.getType().isAir()) {
                        block.setType(Material.FIRE);
                        placed++;
                    }
                }
            }
        }
        if (placed == 0) {
            player.sendMessage(ChatColor.YELLOW + "No open air there for the fire wall.");
        }
    }

    private void fireBlast(Player player) {
        if (onCooldown(player, blastReady, BLAST_COOLDOWN_MS)) return;
        // Large fireball: explodes on impact with blocks or entities, and is launched along the aim, so it works at air too.
        Fireball fireball = player.launchProjectile(Fireball.class);
        fireball.setIsIncendiary(true);
        fireball.setYield(2.5F);
    }

    private boolean onCooldown(Player player, Map<UUID, Long> readyAt, long millis) {
        long now = System.currentTimeMillis();
        Long ready = readyAt.get(player.getUniqueId());
        if (ready != null && now < ready) {
            player.sendMessage(ChatColor.RED + "Fire ability recharging.");
            return true;
        }
        readyAt.put(player.getUniqueId(), now + millis);
        return false;
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
            player.sendMessage(ChatColor.GREEN + "You received a Fire Wand. Right-click for a fire wall, Shift + right-click for a fire blast.");
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
                ChatColor.GRAY + "Right-click: fire wall ahead (9 wide, 3 high)",
                ChatColor.GRAY + "Shift + right-click: fire blast"));
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

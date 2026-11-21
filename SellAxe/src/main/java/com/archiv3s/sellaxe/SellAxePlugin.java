package com.archiv3s.sellaxe;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SellAxePlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final String ESSC_SELL_HOLDER = "net.godlycow.org.essc.modules.shop.sell.SellHolder";

    private final Map<Material, Double> prices = new HashMap<>();
    private NamespacedKey axeKey;
    private Economy economy;

    @Override
    public void onEnable() {
        axeKey = new NamespacedKey(this, "sellaxe");

        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
        } else {
            getLogger().warning("Vault economy not found. SellAxe will refuse to break containers.");
        }

        loadPrices();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("sellaxe").setExecutor(this);
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
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(axeKey, PersistentDataType.BYTE);
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
        java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(createSellAxe());
        for (ItemStack item : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        player.sendMessage(ChatColor.GREEN + "You received a Sell Axe.");
        return true;
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
}

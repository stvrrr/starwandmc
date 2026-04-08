package com.arcanewand;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;

public class ArcaneWandPlugin extends JavaPlugin implements Listener {

    // -------------------------------------------------------
    //  CONFIG
    // -------------------------------------------------------
    private static final String WAND_NAME     = ChatColor.GOLD + "" + ChatColor.BOLD + "✦ Star's Wand ✦";
    private static final double HIT_DAMAGE    = 10.0;   // 2-shot on 20 HP
    private static final int    BLINK_BLOCKS  = 8;
    private static final long   BLINK_COOLDOWN_MS = 5000; // 5 seconds

    // Tracks last blink time per player UUID
    private final Map<UUID, Long> blinkCooldowns = new HashMap<>();

    // -------------------------------------------------------
    //  ENABLE
    // -------------------------------------------------------
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ArcaneWand enabled!");
    }

    // -------------------------------------------------------
    //  HELPER: build the wand ItemStack
    // -------------------------------------------------------
    private ItemStack buildWand() {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        meta.setDisplayName(WAND_NAME);
        meta.setLore(Arrays.asList(
            ChatColor.GRAY + "Left-click" + ChatColor.WHITE  + ": 2-shot true damage",
            ChatColor.GRAY + "Right-click" + ChatColor.WHITE + ": Blink 8 blocks forward",
            ChatColor.DARK_GRAY + "Bypasses all armor & enchantments"
        ));
        // Prevent the wand from being enchanted or used as fuel
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        wand.setItemMeta(meta);
        return wand;
    }

    // -------------------------------------------------------
    //  HELPER: check if an item is the wand
    // -------------------------------------------------------
    private boolean isWand(ItemStack item) {
        if (item == null) return false;
        if (item.getType() != Material.BLAZE_ROD) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasDisplayName() && meta.getDisplayName().equals(WAND_NAME);
    }

    // -------------------------------------------------------
    //  COMMAND: /givewand [player]
    // -------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("givewand")) return false;

        Player target = null;

        if (args.length == 0) {
            // No argument — give to self if sender is a player
            if (sender instanceof Player) {
                target = (Player) sender;
            } else {
                sender.sendMessage(ChatColor.RED + "Usage: /givewand <player>");
                return true;
            }
        } else {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[0]);
                return true;
            }
        }

        target.getInventory().addItem(buildWand());
        target.sendMessage(ChatColor.GOLD + "✦ You received " + WAND_NAME + ChatColor.GOLD + "!");
        if (sender != target) {
            sender.sendMessage(ChatColor.GREEN + "Gave Arcane Wand to " + target.getName());
        }
        return true;
    }

    // -------------------------------------------------------
    //  LEFT CLICK: 2-shot true damage (players + mobs)
    //
    //  We cancel the normal damage event so Minecraft's armor
    //  and enchantment reduction never runs, then directly
    //  subtract HP. This guarantees exactly 2 hits to kill
    //  regardless of gear.
    // -------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        // Attacker must be a player holding the wand
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!isWand(attacker.getInventory().getItemInMainHand())) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        // Don't hit yourself
        if (victim.equals(attacker)) return;

        // Cancel the original hit — no armor/enchant reduction
        event.setCancelled(true);

        double newHp = victim.getHealth() - HIT_DAMAGE;

        if (newHp <= 0) {
            // Kill the entity
            victim.setHealth(0);
            if (victim instanceof Player vPlayer) {
                attacker.sendMessage(ChatColor.DARK_RED + "⚔ KILL! " +
                    ChatColor.GRAY + vPlayer.getName() + " eliminated.");
            } else {
                attacker.sendMessage(ChatColor.DARK_RED + "⚔ KILL! " +
                    ChatColor.GRAY + formatEntityName(victim) + " slain.");
            }
        } else {
            victim.setHealth(newHp);
            if (victim instanceof Player vPlayer) {
                attacker.sendMessage(ChatColor.RED + "⚔ Hit! " +
                    ChatColor.GRAY + vPlayer.getName() + " HP: " +
                    ChatColor.WHITE + String.format("%.1f", newHp) + " / 20");
            } else {
                attacker.sendMessage(ChatColor.RED + "⚔ Hit! " +
                    ChatColor.GRAY + formatEntityName(victim) + " HP: " +
                    ChatColor.WHITE + String.format("%.1f", newHp));
            }
        }

        // Play hurt sound since we cancelled the event (which suppresses it)
        victim.getWorld().playSound(victim.getLocation(),
            Sound.ENTITY_PLAYER_HURT, 1f, 1f);

        // Crit particles at chest height
        Location partLoc = victim.getLocation().add(0, 1, 0);
        victim.getWorld().spawnParticle(Particle.CRIT, partLoc, 12, 0.3, 0.3, 0.3, 0.1);
        victim.getWorld().spawnParticle(Particle.LARGE_SMOKE, partLoc, 6, 0.2, 0.2, 0.2, 0);
    }

    // -------------------------------------------------------
    //  RIGHT CLICK: Blink 8 blocks forward (not sneaking)
    //
    //  Pure vector math — no loops, no location mutation.
    //  Direction is derived from yaw only (horizontal) so the
    //  blink is always a flat forward dash at the same Y level.
    // -------------------------------------------------------
    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        // Only right-click air or block
        if (event.getAction() != Action.RIGHT_CLICK_AIR &&
            event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!isWand(player.getInventory().getItemInMainHand())) return;
        if (player.isSneaking()) return; // sneaking = normal interact

        event.setCancelled(true);

        // Cooldown check
        long now = System.currentTimeMillis();
        if (blinkCooldowns.containsKey(player.getUniqueId())) {
            long elapsed = now - blinkCooldowns.get(player.getUniqueId());
            if (elapsed < BLINK_COOLDOWN_MS) {
                long remaining = (BLINK_COOLDOWN_MS - elapsed) / 1000 + 1;
                player.sendMessage(ChatColor.RED + "✦ Blink on cooldown! " +
                    ChatColor.GRAY + "(" + remaining + "s remaining)");
                return;
            }
        }

        // --- Compute destination ---
        // Use yaw only for a flat horizontal blink
        float yaw = player.getLocation().getYaw();
        double yawRad = Math.toRadians(yaw);
        double dx = -Math.sin(yawRad) * BLINK_BLOCKS;
        double dz =  Math.cos(yawRad) * BLINK_BLOCKS;

        Location dest = player.getLocation().clone().add(dx, 0, dz);

        // Keep the player's look direction
        dest.setYaw(player.getLocation().getYaw());
        dest.setPitch(player.getLocation().getPitch());

        // Departure effects
        Location depLoc = player.getLocation().clone().add(0, 1, 0);
        player.getWorld().spawnParticle(Particle.PORTAL, depLoc, 30, 0.3, 0.5, 0.3, 0.5);
        player.getWorld().spawnParticle(Particle.WITCH, depLoc, 10, 0.2, 0.2, 0.2, 0);
        player.getWorld().playSound(player.getLocation(),
            Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);

        // Teleport
        player.teleport(dest);

        // Arrival effects
        Location arrLoc = player.getLocation().clone().add(0, 1, 0);
        player.getWorld().spawnParticle(Particle.PORTAL, arrLoc, 30, 0.3, 0.5, 0.3, 0.5);
        player.getWorld().spawnParticle(Particle.FLAME, arrLoc, 12, 0.2, 0.2, 0.2, 0.05);
        player.getWorld().playSound(player.getLocation(),
            Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);

        player.sendMessage(ChatColor.AQUA + "✦ " + ChatColor.DARK_AQUA + "BLINK! " + ChatColor.AQUA + "✦");

        blinkCooldowns.put(player.getUniqueId(), now);
    }

    // -------------------------------------------------------
    //  HELPER: friendly entity name for kill messages
    // -------------------------------------------------------
    private String formatEntityName(LivingEntity entity) {
        String raw = entity.getType().name().toLowerCase().replace("_", " ");
        // Capitalise first letter
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}


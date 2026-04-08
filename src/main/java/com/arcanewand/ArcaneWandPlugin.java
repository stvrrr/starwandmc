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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class ArcaneWandPlugin extends JavaPlugin implements Listener {

    // -------------------------------------------------------
    //  CONFIG
    // -------------------------------------------------------
    private static final String WAND_NAME            = ChatColor.GOLD + "" + ChatColor.BOLD + "✦ Star's Wand ✦";
    private static final double HIT_DAMAGE           = 10.0;
    private static final int    BLINK_BLOCKS         = 8;
    private static final long   BLINK_COOLDOWN_MS    = 5000;
    private static final int    BLACKHOLE_SPAWN_DIST = 5;
    private static final double BLACKHOLE_RADIUS     = 15.0;
    private static final int    BLACKHOLE_DURATION   = 100; // ticks (5s)
    private static final long   BLACKHOLE_COOLDOWN_MS = 15000;

    private final Map<UUID, Long> blinkCooldowns     = new HashMap<>();
    private final Map<UUID, Long> blackholeCooldowns = new HashMap<>();

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
            ChatColor.GRAY + "Sneak + Right-click" + ChatColor.WHITE + ": Summon Blackhole",
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

        // ---- KNOCKBACK ----
        // Push victim 7 blocks away from the attacker, horizontally
        Vector knockback = victim.getLocation().toVector()
            .subtract(attacker.getLocation().toVector())
            .setY(0)        // keep it horizontal
            .normalize()
            .multiply(1.8)  // 1.8 gives ~7 block launch distance
            .setY(0.4);     // small upward arc so it looks natural
        victim.setVelocity(knockback);

        double newHp = victim.getHealth() - HIT_DAMAGE;

        if (newHp <= 0) {
            // Store kill location before health is set to 0
            Location killLoc = victim.getLocation().clone().add(0, 1, 0);
            World killWorld  = victim.getWorld();

            // Kill the entity
            victim.setHealth(0);

            // ---- KILL EFFECT ----
            // Small shockwave of particles at the death location
            killWorld.spawnParticle(Particle.EXPLOSION,    killLoc, 1, 0, 0, 0, 0);
            killWorld.spawnParticle(Particle.CRIT,         killLoc, 30, 0.4, 0.4, 0.4, 0.6);
            killWorld.spawnParticle(Particle.LARGE_SMOKE,  killLoc, 20, 0.3, 0.3, 0.3, 0.05);
            killWorld.spawnParticle(Particle.FLAME,        killLoc, 15, 0.3, 0.3, 0.3, 0.08);
            killWorld.playSound(killLoc, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
            killWorld.playSound(killLoc, Sound.ENTITY_PLAYER_DEATH,    1f,   1f);

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

        event.setCancelled(true);

        // ---- SNEAK + RIGHT CLICK → BLACKHOLE ----
        if (player.isSneaking()) {
            long now = System.currentTimeMillis();
            if (blackholeCooldowns.containsKey(player.getUniqueId())) {
                long elapsed = now - blackholeCooldowns.get(player.getUniqueId());
                if (elapsed < BLACKHOLE_COOLDOWN_MS) {
                    long remaining = (BLACKHOLE_COOLDOWN_MS - elapsed) / 1000 + 1;
                    player.sendMessage(ChatColor.DARK_PURPLE + "✦ Blackhole on cooldown! " +
                        ChatColor.GRAY + "(" + remaining + "s remaining)");
                    return;
                }
            }
            spawnBlackhole(player);
            blackholeCooldowns.put(player.getUniqueId(), now);
            return;
        }

        // ---- RIGHT CLICK (not sneaking) → BLINK ----
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
    //  SNEAK + RIGHT CLICK: BLACKHOLE
    //
    //  Spawns an invisible marker 5 blocks ahead of the player.
    //  Every 2 ticks for 5 seconds it:
    //    - Pulls all living entities within 15 blocks toward it
    //    - Plays a growing spiral of portal/smoke particles
    //    - Rumbles with a wither sound
    //  At the end it kills everything still within 15 blocks
    //  and fires a final explosion of particles.
    // -------------------------------------------------------
    private void spawnBlackhole(Player caster) {
        // Compute spawn point 5 blocks in front of caster (horizontal)
        float yaw       = caster.getLocation().getYaw();
        double yawRad   = Math.toRadians(yaw);
        double dx       = -Math.sin(yawRad) * BLACKHOLE_SPAWN_DIST;
        double dz       =  Math.cos(yawRad) * BLACKHOLE_SPAWN_DIST;
        final Location center = caster.getLocation().clone().add(dx, 1, dz);
        final World world     = center.getWorld();

        caster.sendMessage(ChatColor.DARK_PURPLE + "✦ " + ChatColor.LIGHT_PURPLE +
            "BLACKHOLE SUMMONED!" + ChatColor.DARK_PURPLE + " ✦");

        // Spawn sound at creation
        world.playSound(center, Sound.ENTITY_WITHER_SPAWN, 1f, 0.5f);

        // Keep track of elapsed ticks
        final int[] ticksElapsed = {0};

        new BukkitRunnable() {
            @Override
            public void run() {
                ticksElapsed[0] += 2;

                // ---- PARTICLE SPIRAL ----
                // Rings of portal particles that spin and contract over time
                double progress = (double) ticksElapsed[0] / BLACKHOLE_DURATION;
                int ringCount   = 3;
                int pointsPerRing = 16;
                for (int ring = 0; ring < ringCount; ring++) {
                    double ringRadius = BLACKHOLE_RADIUS * 0.3 * (1.0 - progress * 0.5);
                    double angleOffset = ticksElapsed[0] * 0.3 + ring * (Math.PI * 2 / ringCount);
                    for (int i = 0; i < pointsPerRing; i++) {
                        double angle = angleOffset + (i * Math.PI * 2 / pointsPerRing);
                        double px    = center.getX() + Math.cos(angle) * ringRadius;
                        double py    = center.getY() + (ring - 1) * 0.6;
                        double pz    = center.getZ() + Math.sin(angle) * ringRadius;
                        world.spawnParticle(Particle.PORTAL,
                            new Location(world, px, py, pz), 1, 0, 0, 0, 0);
                    }
                }
                // Dense smoke core
                world.spawnParticle(Particle.LARGE_SMOKE, center, 8, 0.3, 0.3, 0.3, 0.01);
                world.spawnParticle(Particle.SMOKE,       center, 5, 0.1, 0.1, 0.1, 0.05);

                // ---- PULL EFFECT ----
                // Every tick, drag nearby living entities toward the center
                for (Entity entity : world.getNearbyEntities(center, BLACKHOLE_RADIUS,
                        BLACKHOLE_RADIUS, BLACKHOLE_RADIUS)) {
                    if (!(entity instanceof LivingEntity)) continue;
                    if (entity.equals(caster)) continue; // don't pull caster

                    LivingEntity le    = (LivingEntity) entity;
                    Vector toCenter    = center.toVector().subtract(le.getLocation().toVector());
                    double distance    = toCenter.length();
                    if (distance > BLACKHOLE_RADIUS) continue;
                    if (distance < 0.5) continue; // already at center

                    // Pull strength increases as entity gets closer
                    double pullStrength = 0.3 + (1.0 - distance / BLACKHOLE_RADIUS) * 0.5;
                    Vector pull        = toCenter.normalize().multiply(pullStrength);
                    // Zero out current velocity first so pull feels snappy
                    le.setVelocity(le.getVelocity().multiply(0.3).add(pull));
                }

                // Ambient rumble sound every 10 ticks
                if (ticksElapsed[0] % 10 == 0) {
                    world.playSound(center, Sound.ENTITY_WITHER_AMBIENT, 0.6f, 0.3f);
                }

                // ---- EXPIRY ----
                if (ticksElapsed[0] >= BLACKHOLE_DURATION) {
                    // Kill everything still within radius
                    int killCount = 0;
                    for (Entity entity : world.getNearbyEntities(center, BLACKHOLE_RADIUS,
                            BLACKHOLE_RADIUS, BLACKHOLE_RADIUS)) {
                        if (!(entity instanceof LivingEntity)) continue;
                        if (entity.equals(caster)) continue;
                        ((LivingEntity) entity).setHealth(0);
                        killCount++;
                    }

                    // Final implosion particle burst
                    world.spawnParticle(Particle.EXPLOSION, center, 3, 0.5, 0.5, 0.5, 0);
                    world.spawnParticle(Particle.PORTAL,    center, 200, 1, 1, 1, 2);
                    world.spawnParticle(Particle.LARGE_SMOKE, center, 50, 1, 1, 1, 0.1);
                    world.playSound(center, Sound.ENTITY_WITHER_DEATH, 1f, 0.5f);

                    if (killCount > 0) {
                        caster.sendMessage(ChatColor.DARK_PURPLE + "✦ Blackhole collapsed! " +
                            ChatColor.GRAY + killCount + " entit" +
                            (killCount == 1 ? "y" : "ies") + " consumed.");
                    } else {
                        caster.sendMessage(ChatColor.DARK_PURPLE + "✦ Blackhole collapsed.");
                    }
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 2L); // runs every 2 ticks
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

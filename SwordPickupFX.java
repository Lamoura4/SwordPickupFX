package com.example.swordfx;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Display;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 1) Announces (chat + sounds) when a player picks up the Lifesteal Sword.
 * 2) Animates the "Sword Altar" (an item_display lid over a chest/barrel): the lid swings open,
 *    the sword rises like an eye of ender, hovers, then pops out as a pickup item.
 */
public final class SwordPickupFX extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String LID_TAG = "sword_altar_lid";

    private final Set<UUID> holders = new HashSet<>();
    private final Map<Block, Runnable> running = new HashMap<>();
    private NamespacedKey swordKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        swordKey = NamespacedKey.fromString("lifestealsword:lifesteal_sword");

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (holdsSword(p)) holders.add(p.getUniqueId());
        }

        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::scan, 20L, 10L);
    }

    @Override
    public void onDisable() {
        // Finish any running reveal so the sword is never lost
        for (Runnable cleanup : new ArrayList<>(running.values())) cleanup.run();
        running.clear();
    }

    // ------------------------------------------------------------------ pickup announcement

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (holdsSword(event.getPlayer())) holders.add(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        holders.remove(event.getPlayer().getUniqueId());
    }

    private void scan() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean has = holdsSword(p);
            UUID id = p.getUniqueId();
            if (has) {
                if (holders.add(id)) announce(p);
            } else {
                holders.remove(id);
            }
        }
    }

    private boolean holdsSword(Player p) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (isSword(item)) return true;
        }
        return false;
    }

    private boolean isSword(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (swordKey != null && meta.getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE)) {
            return true;
        }
        if (getConfig().getBoolean("match-name", true) && item.getType() == Material.NETHERITE_SWORD) {
            Component name = meta.displayName();
            if (name != null) {
                String plain = PlainTextComponentSerializer.plainText().serialize(name);
                return plain.equalsIgnoreCase("Lifesteal Sword");
            }
        }
        return false;
    }

    private void announce(Player claimer) {
        if (getConfig().getBoolean("ignore-ops", false) && claimer.isOp()) return;

        String broadcast = getConfig().getString("broadcast", "");
        if (broadcast != null && !broadcast.isBlank()) {
            Bukkit.broadcast(LEGACY.deserialize(broadcast.replace("%player%", claimer.getName())));
        }
        for (String line : getConfig().getStringList("private-message")) {
            claimer.sendMessage(LEGACY.deserialize(line.replace("%player%", claimer.getName())));
        }

        String thunder = getConfig().getString("thunder-sound", "entity.lightning_bolt.thunder");
        String portal = getConfig().getString("portal-sound", "block.end_portal.spawn");
        float volume = (float) getConfig().getDouble("sound-volume", 1.0);
        long delay = Math.max(0, getConfig().getLong("portal-delay-ticks", 20));

        playToAll(thunder, volume);
        Bukkit.getScheduler().runTaskLater(this, () -> playToAll(portal, volume), delay);
    }

    private void playToAll(String sound, float volume) {
        if (sound == null || sound.isBlank()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), sound, SoundCategory.MASTER, volume, 1.0f);
        }
    }

    // ------------------------------------------------------------------ altar: lid helpers

    private ItemDisplay findLid(Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        for (ItemDisplay d : block.getWorld().getNearbyEntitiesByType(ItemDisplay.class, center, 0.6)) {
            if (d.getScoreboardTags().contains(LID_TAG)) return d;
        }
        return null;
    }

    private Transformation lidTransform(boolean open) {
        Vector3f scale = new Vector3f(1f, 1f, 1f);
        AxisAngle4f none = new AxisAngle4f(0f, 0f, 0f, 1f);
        if (!open) return new Transformation(new Vector3f(), none, scale, new AxisAngle4f(0f, 0f, 0f, 1f));

        float rad = (float) Math.toRadians(getConfig().getDouble("altar-open-angle", 100));
        String side = getConfig().getString("altar-lid-hinge", "west").toLowerCase();
        float h = 0.40625f;      // hinge distance from the centre, in blocks
        float hy = 0.3125f;      // hinge height above the centre (lid bottom is at 13/16)
        Vector3f axis;
        float angle;
        Vector3f hinge;
        switch (side) {
            case "south" -> { axis = new Vector3f(1, 0, 0); angle = rad;  hinge = new Vector3f(0, hy,  h); }
            case "east"  -> { axis = new Vector3f(0, 0, 1); angle = -rad; hinge = new Vector3f( h, hy, 0); }
            case "west"  -> { axis = new Vector3f(0, 0, 1); angle = rad;  hinge = new Vector3f(-h, hy, 0); }
            default      -> { axis = new Vector3f(1, 0, 0); angle = -rad; hinge = new Vector3f(0, hy, -h); }
        }
        AxisAngle4f rot = new AxisAngle4f(angle, axis.x, axis.y, axis.z);
        Vector3f rotated = new Vector3f(hinge).rotate(new Quaternionf(rot));
        Vector3f translation = new Vector3f(hinge).sub(rotated);
        return new Transformation(translation, rot, scale, new AxisAngle4f(0f, 0f, 0f, 1f));
    }

    private void animateLid(ItemDisplay lid, boolean open, int ticks) {
        if (lid == null) return;
        lid.setInterpolationDelay(0);
        lid.setInterpolationDuration(ticks);
        lid.setTransformation(lidTransform(open));
    }

    // Normal GUI open (used by sneaking admins to refill the altar): animate the lid too.
    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        Block block = blockOf(event.getInventory());
        if (block == null) return;
        ItemDisplay lid = findLid(block);
        if (lid != null) animateLid(lid, true, 8);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getViewers().size() > 1) return;
        Block block = blockOf(event.getInventory());
        if (block == null || running.containsKey(block)) return;
        ItemDisplay lid = findLid(block);
        if (lid != null) animateLid(lid, false, 8);
    }

    private Block blockOf(Inventory inv) {
        Location loc = inv.getLocation();
        return loc == null ? null : loc.getBlock();
    }

    // ------------------------------------------------------------------ altar: right-click reveal

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        BlockState state = block.getState();
        if (!(state instanceof Container container)) return;
        ItemDisplay lid = findLid(block);
        if (lid == null) return; // not an altar

        Player player = event.getPlayer();
        // Sneaking admins open the altar normally, to refill it
        if (player.isSneaking() && player.hasPermission("swordfx.admin")) return;

        event.setCancelled(true);
        if (running.containsKey(block)) return;

        Inventory inv = container.getInventory();
        int slot = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            if (isSword(inv.getItem(i))) { slot = i; break; }
        }
        if (slot < 0) {
            String msg = getConfig().getString("altar-empty-message", "");
            if (msg != null && !msg.isBlank()) player.sendMessage(LEGACY.deserialize(msg));
            return;
        }

        ItemStack stack = inv.getItem(slot);
        ItemStack sword = stack.clone();
        sword.setAmount(1);
        if (stack.getAmount() > 1) { stack.setAmount(stack.getAmount() - 1); inv.setItem(slot, stack); }
        else inv.setItem(slot, null);

        startReveal(block, lid, sword);
    }

    private void startReveal(Block block, ItemDisplay lid, ItemStack sword) {
        World world = block.getWorld();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        Location start = center.clone().add(0, 0.45, 0);

        final ItemDisplay[] display = new ItemDisplay[1];
        final boolean[] dropped = {false};

        Runnable finish = () -> {
            if (display[0] != null && !display[0].isDead()) display[0].remove();
            if (!dropped[0]) {
                dropped[0] = true;
                Item it = world.dropItem(start.clone().add(0, 1.6, 0), sword);
                it.setVelocity(new Vector(0, 0.2, 0));
                it.setPickupDelay(20);
            }
            animateLid(lid, false, 8);
            running.remove(block);
        };
        running.put(block, finish);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                if (t == 1) {
                    animateLid(lid, true, 10);
                    world.playSound(center, "block.barrel.open", SoundCategory.BLOCKS, 1f, 0.8f);
                    world.playSound(center, "block.end_portal_frame.fill", SoundCategory.BLOCKS, 1f, 0.7f);
                }
                if (t == 12) {
                    display[0] = world.spawn(start, ItemDisplay.class, d -> {
                        d.setItemStack(sword);
                        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
                        d.setBrightness(new Display.Brightness(15, 15));
                        d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(0f, 0f, 1f, 0f),
                                new Vector3f(1.6f, 1.6f, 1.6f), new AxisAngle4f(0f, 0f, 1f, 0f)));
                    });
                }
                if (t == 13 && display[0] != null) {          // rise + spin, like an eye of ender
                    display[0].setInterpolationDelay(0);
                    display[0].setInterpolationDuration(50);
                    display[0].setTransformation(new Transformation(new Vector3f(0, 1.5f, 0),
                            new AxisAngle4f((float) (2 * Math.PI / 3), 0f, 1f, 0f),
                            new Vector3f(1.6f, 1.6f, 1.6f), new AxisAngle4f(0f, 0f, 1f, 0f)));
                    world.playSound(start, "entity.ender_eye.launch", SoundCategory.BLOCKS, 1f, 0.9f);
                }
                if (t >= 14 && t < 90 && t % 3 == 0 && display[0] != null) {
                    Location l = display[0].getLocation().add(0, 0.2, 0);
                    world.spawnParticle(Particle.END_ROD, l, 3, 0.15, 0.15, 0.15, 0.01);
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 2, 0.2, 0.2, 0.2, 0.01);
                }
                if (t == 63 && display[0] != null) {          // hover, still spinning
                    display[0].setInterpolationDelay(0);
                    display[0].setInterpolationDuration(25);
                    display[0].setTransformation(new Transformation(new Vector3f(0, 1.7f, 0),
                            new AxisAngle4f((float) (4 * Math.PI / 3), 0f, 1f, 0f),
                            new Vector3f(1.8f, 1.8f, 1.8f), new AxisAngle4f(0f, 0f, 1f, 0f)));
                }
                if (t == 89) {                                 // pop
                    Location pop = start.clone().add(0, 1.7, 0);
                    world.spawnParticle(Particle.POOF, pop, 25, 0.25, 0.25, 0.25, 0.05);
                    world.spawnParticle(Particle.END_ROD, pop, 40, 0.3, 0.3, 0.3, 0.12);
                    world.playSound(pop, "entity.firework_rocket.blast", SoundCategory.BLOCKS, 1.2f, 0.8f);
                    world.playSound(pop, "entity.ender_eye.death", SoundCategory.BLOCKS, 1f, 1f);
                    finish.run();
                    cancel();
                }
            }
        }.runTaskTimer(SwordPickupFX.this, 1L, 1L);
    }
}

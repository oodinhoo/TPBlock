package fr.tpblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Two paired lodestones. Right-click = teleport to the twin,
 * sneak + right-click with an empty hand = pairing.
 *
 * <p>A lodestone is not a {@code TileState}: it carries no
 * PersistentDataContainer. The pair therefore lives in the <b>chunk</b> PDC, under
 * a key derived from the block coordinates. Vanilla saves it with the chunk,
 * so the plugin still has no persistence layer of its own.
 */
public final class TPBlock extends JavaPlugin implements Listener {

    private static final Material TP_BLOCK = Material.LODESTONE;

    /** Marks the TextDisplays the plugin places above a lodestone. */
    private static final String LABEL_TAG = "tpblock_label";

    /** Text height above the arriving player's feet: just over their head. Tune by eye. */
    private static final double LABEL_HEIGHT = 1.9;

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    /** Prefixes of the chunk PDC keys: pair_x_y_z holds the twin, face_x_y_z the front. */
    private static final String PAIR_KEY = "pair";
    private static final String FACE_KEY = "face";

    /** First block of a pairing and the face clicked on it, written only once the pair is sealed. */
    private record Selection(Location location, BlockFace face) {
    }

    /** Pairing in progress, deliberately not persisted: disconnecting cancels the selection. */
    private final Map<UUID, Selection> pending = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    // HIGH: after the protection plugins, so a click they deny is already cancelled and ignored.
    // Not MONITOR, since this handler cancels the event itself.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        // PlayerInteractEvent fires twice per click: main hand, then off hand.
        // Without this filter, everything runs twice.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != TP_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission("tpblock.use")) {
            return;
        }

        // A lodestone has a vanilla interaction of its own: binding a compass.
        // With a compass in hand, we leave the base game alone.
        Material inHand = player.getInventory().getItemInMainHand().getType();
        if (inHand == Material.COMPASS) {
            return;
        }

        // Name tag in hand: no vanilla interaction on a block, so we take it.
        if (inHand == Material.NAME_TAG) {
            event.setCancelled(true);
            label(player, block, player.getInventory().getItemInMainHand());
            return;
        }

        if (player.isSneaking()) {
            // Empty hand required: with a block in hand, vanilla places it.
            if (!inHand.isAir()) {
                return;
            }
            event.setCancelled(true);
            link(player, block, event.getBlockFace());
        } else {
            event.setCancelled(true);
            teleport(player, block);
        }
    }

    private void link(Player player, Block block, BlockFace face) {
        Location clicked = block.getLocation();
        Selection first = pending.remove(player.getUniqueId());

        if (first == null) {
            pending.put(player.getUniqueId(), new Selection(clicked, face));
            info(player, "First block selected. Sneak and click the second one.");
            return;
        }

        if (first.location().equals(clicked)) {
            pending.put(player.getUniqueId(), first);
            deny(player, "A block cannot be paired with itself.");
            return;
        }

        // The first block may have been broken while the player crossed the map.
        if (!first.location().isWorldLoaded() || first.location().getBlock().getType() != TP_BLOCK) {
            deny(player, "The first block is gone. Selection cancelled.");
            return;
        }

        // The face clicked on each block becomes its front: we arrive in front of it if the top
        // is blocked. Written only now, so an abandoned selection changes nothing.
        Block firstBlock = first.location().getBlock();
        writePair(firstBlock, clicked);
        writeFace(firstBlock, first.face());
        writePair(block, first.location());
        writeFace(block, face);
        info(player, "Blocks paired.");
    }

    private void teleport(Player player, Block block) {
        String raw = readPair(block);
        if (raw == null) {
            deny(player, "This lodestone is not paired with any other.");
            return;
        }

        Location destination = decode(raw);

        // The twin's world is not loaded (unloaded by a multiworld plugin, for instance): the
        // link is not broken, it is kept for when the world comes back.
        if (destination != null && !destination.isWorldLoaded()) {
            deny(player, "Destination unavailable.");
            return;
        }

        // The reciprocity check alone covers a broken block, a replaced block
        // and a block re-paired elsewhere: the destination must point back here.
        // ponytail: synchronous read of the destination chunk, so it loads on the
        // main thread. Switch to World#getChunkAtAsync if cross-world jumps
        // drop the TPS.
        if (destination == null || !isPairedBackTo(destination, block)) {
            clearPair(block);
            deny(player, "Link broken.");
            return;
        }

        Block feet = arrival(destination.getBlock());
        if (feet == null) {
            deny(player, "Destination blocked.");
            return;
        }

        Location target = feet.getLocation().add(0.5, 0, 0.5);
        target.setYaw(player.getLocation().getYaw());
        target.setPitch(player.getLocation().getPitch());

        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success -> {
            if (success) {
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
            } else {
                deny(player, "Teleport failed."); // e.g. carrying a passenger, or cancelled by another plugin
            }
        });
    }

    /** Renamed name tag = places the text, blank name tag = removes it. The name tag is not consumed. */
    private void label(Player player, Block block, ItemStack tag) {
        removeLabel(block);
        Component text = tag.getItemMeta().customName();
        if (text == null) {
            info(player, "Text removed.");
            return;
        }
        block.getWorld().spawn(labelLocation(block), TextDisplay.class, display -> {
            display.addScoreboardTag(LABEL_TAG);
            display.addScoreboardTag(labelTag(block));
            display.setBillboard(Display.Billboard.CENTER);
            display.setPersistent(true);
            display.text(text);
        });
        info(player, "Text placed.");
    }

    // ponytail: only breaking by hand clears the text and the PDC entry. Explosions, pistons
    // or WorldEdit leave a floating text and an orphan entry (inert, reciprocity rejects
    // it): /kill @e[tag=tpblock_label,distance=..3] until we listen to
    // EntityExplodeEvent / BlockExplodeEvent, if it ever really happens.
    // MONITOR: only once no plugin can cancel the break any more, or a protected block would
    // survive with its pair and label already wiped.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == TP_BLOCK) {
            removeLabel(event.getBlock());
            clearPair(event.getBlock());
        }
    }

    /**
     * Looks up by the block's own tag, not by position: the arrival spot, and so the text's,
     * changes if something is built around the lodestone afterwards.
     */
    private static void removeLabel(Block block) {
        String tag = labelTag(block);
        block.getWorld().getNearbyEntitiesByType(TextDisplay.class, block.getLocation().add(0.5, 0.5, 0.5), 4,
                display -> display.getScoreboardTags().contains(tag)).forEach(Entity::remove);
    }

    private static String labelTag(Block block) {
        return LABEL_TAG + "_" + coords(block);
    }

    /** Above the arrival spot, or above the block itself if it is fully walled in. */
    private Location labelLocation(Block block) {
        Block feet = arrival(block);
        return (feet != null ? feet : block.getRelative(BlockFace.UP)).getLocation().add(0.5, LABEL_HEIGHT, 0.5);
    }

    private boolean isPairedBackTo(Location destination, Block origin) {
        Block block = destination.getBlock();
        if (block.getType() != TP_BLOCK) {
            return false;
        }
        String raw = readPair(block);
        return raw != null && raw.equals(encode(origin.getLocation()));
    }

    /**
     * Where to put the feet around a lodestone: on top, else in front of the face clicked at
     * pairing (block set into a wall), else the first free side (N/E/S/W) when that front is
     * blocked too. Null if everything is blocked.
     */
    // ponytail: no ground check under the feet: arriving in front of a face with nothing below,
    // the player falls. Check the block under the feet if players complain.
    private Block arrival(Block lodestone) {
        Block above = lodestone.getRelative(BlockFace.UP);
        if (fits(above)) {
            return above;
        }
        BlockFace front = readFace(lodestone);
        if (front != null) {
            Block feet = beside(lodestone, front);
            if (feet != null) {
                return feet;
            }
        }
        for (BlockFace face : SIDES) {
            Block feet = beside(lodestone, face);
            if (feet != null) {
                return feet;
            }
        }
        return null;
    }

    /** In front of a face: first one block lower (block at eye level), then at the same level. */
    private static Block beside(Block lodestone, BlockFace face) {
        Block side = lodestone.getRelative(face);
        if (fits(side.getRelative(BlockFace.DOWN))) {
            return side.getRelative(BlockFace.DOWN);
        }
        return fits(side) ? side : null;
    }

    /** Two free blocks: feet and head. */
    private static boolean fits(Block feet) {
        return isSafe(feet) && isSafe(feet.getRelative(BlockFace.UP));
    }

    /** Water is a valid arrival, lava is not: isPassable() says yes to both. */
    private static boolean isSafe(Block block) {
        return block.isPassable() && block.getType() != Material.LAVA;
    }

    // --- Storage: chunk PDC, one key per block ------------------------------

    /** "x_y_z", shared by the PDC keys and the label tag of a block. The '-' of negative coordinates is legal. */
    private static String coords(Block block) {
        return block.getX() + "_" + block.getY() + "_" + block.getZ();
    }

    /** PDC key of the block in its chunk. */
    private NamespacedKey keyFor(String prefix, Block block) {
        return new NamespacedKey(this, prefix + "_" + coords(block));
    }

    private static PersistentDataContainer pdc(Block block) {
        return block.getChunk().getPersistentDataContainer();
    }

    private String readPair(Block block) {
        return pdc(block).get(keyFor(PAIR_KEY, block), PersistentDataType.STRING);
    }

    private void writePair(Block block, Location target) {
        pdc(block).set(keyFor(PAIR_KEY, block), PersistentDataType.STRING, encode(target));
    }

    private void clearPair(Block block) {
        pdc(block).remove(keyFor(PAIR_KEY, block));
        pdc(block).remove(keyFor(FACE_KEY, block));
    }

    private void writeFace(Block block, BlockFace face) {
        pdc(block).set(keyFor(FACE_KEY, block), PersistentDataType.STRING, face.name());
    }

    private BlockFace readFace(Block block) {
        String raw = pdc(block).get(keyFor(FACE_KEY, block), PersistentDataType.STRING);
        try {
            return raw == null ? null : BlockFace.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            return null; // unreadable PDC: fall back to the first free side
        }
    }

    /** Stored value: "worldUuid:x:y:z". The UUID contains no ':'. */
    private static String encode(Location location) {
        return location.getWorld().getUID()
                + ":" + location.getBlockX()
                + ":" + location.getBlockY()
                + ":" + location.getBlockZ();
    }

    /** Null if unreadable. A world that is not loaded gives a location without a world. */
    private Location decode(String raw) {
        String[] parts = raw.split(":");
        if (parts.length != 4) {
            return null;
        }
        try {
            World world = getServer().getWorld(UUID.fromString(parts[0]));
            return new Location(world,
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (IllegalArgumentException exception) {
            return null; // unreadable PDC: treated as a broken link
        }
    }

    private static void info(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.AQUA));
    }

    private static void deny(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.RED));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
    }
}

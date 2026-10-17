package com.example.glock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class GlockPlugin extends JavaPlugin implements Listener {

    // ================= AJUSTES =================
    private static final double DAMAGE = 6.0;             // 6 = 3 corazones (antes de armadura)
    private static final int FIRE_INTERVAL_TICKS = 3;     // 3 ticks = ~6.7 disparos/seg (2 = 10/seg)
    private static final int HOLD_TIMEOUT_TICKS = 5;      // ticks sin click para dejar de disparar
    private static final double SPEED = 2.5;              // velocidad de la bola de fuego
    private static final int LIFETIME_TICKS = 30;         // vida maxima de la bola
    private static final double SPREAD = 0.015;           // dispersion (0 = laser perfecto)
    private static final int BULLETS_PER_CRAFT = 8;       // balas por crafteo
    // SmallFireball = bola chica (blaze). Cambia a LargeFireball.class para la carga grande de ghast.
    private static final Class<? extends Fireball> BULLET_TYPE = SmallFireball.class;
    // ===========================================

    private NamespacedKey gunKey;
    private NamespacedKey ammoKey;
    private NamespacedKey bulletKey;
    private NamespacedKey glockRecipeKey;
    private NamespacedKey ammoRecipeKey;

    private long tick = 0;
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Map<UUID, Long> nextShot = new HashMap<>();

    @Override
    public void onEnable() {
        gunKey = new NamespacedKey(this, "glock");
        ammoKey = new NamespacedKey(this, "bala");
        bulletKey = new NamespacedKey(this, "bola");
        glockRecipeKey = new NamespacedKey(this, "glock_recipe");
        ammoRecipeKey = new NamespacedKey(this, "bala_recipe");

        registerRecipes();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tickAll, 1L, 1L);
        getLogger().info("GlockPlugin activado.");
    }

    @Override
    public void onDisable() {
        for (World w : Bukkit.getWorlds()) {
            for (Fireball f : w.getEntitiesByClass(Fireball.class)) {
                if (isBullet(f)) f.remove();
            }
        }
    }

    // ---------------- Items ----------------

    private ItemStack createGlock() {
        ItemStack item = new ItemStack(Material.CROSSBOW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Glock", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Manten click derecho: fuego automatico", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Municion: Bala (pararrayos)", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE,
                ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(gunKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createAmmo(int amount) {
        ItemStack item = new ItemStack(Material.LIGHTNING_ROD, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Bala", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isGlock(ItemStack item) {
        return item != null && item.getType() == Material.CROSSBOW && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(gunKey, PersistentDataType.BYTE);
    }

    private boolean isAmmo(ItemStack item) {
        return item != null && item.getType() == Material.LIGHTNING_ROD && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(ammoKey, PersistentDataType.BYTE);
    }

    private boolean isBullet(Entity e) {
        return e != null && e.getPersistentDataContainer().has(bulletKey, PersistentDataType.BYTE);
    }

    // ---------------- Recetas ----------------

    private void registerRecipes() {
        Bukkit.removeRecipe(glockRecipeKey);
        Bukkit.removeRecipe(ammoRecipeKey);

        // Glock:
        //  Diamante  Hierro  Hierro
        //  Hierro    Oro     -
        //  Hierro    -       -
        ShapedRecipe glock = new ShapedRecipe(glockRecipeKey, createGlock());
        glock.shape("DII", "IG ", "I  ");
        glock.setIngredient('D', Material.DIAMOND);
        glock.setIngredient('I', Material.IRON_INGOT);
        glock.setIngredient('G', Material.GOLD_INGOT);
        Bukkit.addRecipe(glock);

        // Balas (columna vertical):
        //  Cobre
        //  Oro
        //  Oro
        ShapedRecipe ammo = new ShapedRecipe(ammoRecipeKey, createAmmo(BULLETS_PER_CRAFT));
        ammo.shape("C", "G", "G");
        ammo.setIngredient('C', Material.COPPER_INGOT);
        ammo.setIngredient('G', Material.GOLD_INGOT);
        Bukkit.addRecipe(ammo);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipes(List.of(glockRecipeKey, ammoRecipeKey));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastClick.remove(e.getPlayer().getUniqueId());
        nextShot.remove(e.getPlayer().getUniqueId());
    }

    // ---------------- Municion ----------------

    private boolean hasAmmo(Player p) {
        for (ItemStack stack : p.getInventory().getContents()) {
            if (isAmmo(stack)) return true;
        }
        return false;
    }

    private int countAmmo(Player p) {
        int total = 0;
        for (ItemStack stack : p.getInventory().getContents()) {
            if (isAmmo(stack)) total += stack.getAmount();
        }
        return total;
    }

    private boolean takeAmmo(Player p) {
        for (ItemStack stack : p.getInventory().getContents()) {
            if (isAmmo(stack)) {
                stack.setAmount(stack.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    // ---------------- Ballesta siempre cargada si hay balas ----------------

    /** Devuelve true si cambio el estado del item. */
    private boolean syncLoaded(ItemStack item, boolean hasAmmo) {
        CrossbowMeta cm = (CrossbowMeta) item.getItemMeta();
        boolean loaded = cm.hasChargedProjectiles();
        if (hasAmmo && !loaded) {
            cm.addChargedProjectile(new ItemStack(Material.ARROW));
            item.setItemMeta(cm);
            return true;
        } else if (!hasAmmo && loaded) {
            cm.setChargedProjectiles(new ArrayList<>());
            item.setItemMeta(cm);
            return true;
        }
        return false;
    }

    // ---------------- Loop principal (cada tick) ----------------

    private void tickAll() {
        tick++;
        boolean sync = (tick % 2 == 0);

        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();
            ItemStack main = inv.getItemInMainHand();
            ItemStack off = inv.getItemInOffHand();
            boolean mainGlock = isGlock(main);
            boolean offGlock = isGlock(off);

            if (sync && (mainGlock || offGlock)) {
                boolean ammo = hasAmmo(p);
                if (mainGlock && syncLoaded(main, ammo)) inv.setItemInMainHand(main);
                if (offGlock && syncLoaded(off, ammo)) inv.setItemInOffHand(off);
            }

            UUID id = p.getUniqueId();
            Long last = lastClick.get(id);
            if (last == null) continue;

            if (!mainGlock || tick - last > HOLD_TIMEOUT_TICKS) {
                lastClick.remove(id);
                continue;
            }

            if (tick >= nextShot.getOrDefault(id, 0L)) {
                if (!takeAmmo(p)) {
                    lastClick.remove(id);
                    continue;
                }
                shoot(p);
                nextShot.put(id, tick + FIRE_INTERVAL_TICKS);
            }
        }
    }

    // ---------------- Disparo ----------------

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (!isGlock(item)) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        // Evita que la ballesta dispare/cargue flechas de forma vanilla
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player p = e.getPlayer();
        if (!hasAmmo(p)) {
            p.playSound(p.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 0.6f, 1.6f);
            p.sendActionBar(Component.text("Sin balas", NamedTextColor.RED));
            return;
        }
        lastClick.put(p.getUniqueId(), tick);
    }

    @EventHandler
    public void onShootBow(EntityShootBowEvent e) {
        if (isGlock(e.getBow())) e.setCancelled(true);
    }

    private void shoot(Player p) {
        World world = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (SPREAD > 0) {
            dir.add(new Vector(r.nextDouble(-SPREAD, SPREAD),
                    r.nextDouble(-SPREAD, SPREAD),
                    r.nextDouble(-SPREAD, SPREAD))).normalize();
        }
        Location spawn = eye.clone().add(dir.clone().multiply(1.2));

        Fireball fb = world.spawn(spawn, BULLET_TYPE, f -> {
            f.setShooter(p);
            f.setDirection(dir);
            f.setVelocity(dir.clone().multiply(SPEED));
            f.setYield(0f);
            f.setIsIncendiary(false);
            f.setGravity(false);
            f.setInvulnerable(true); // que no se puedan desviar a golpes
            f.getPersistentDataContainer().set(bulletKey, PersistentDataType.BYTE, (byte) 1);
        });

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (fb.isValid()) fb.remove();
        }, LIFETIME_TICKS);

        world.spawnParticle(Particle.FLAME, spawn, 3, 0.05, 0.05, 0.05, 0.01);
        world.playSound(eye, Sound.ENTITY_BLAZE_SHOOT, 0.7f, 1.8f);
        p.sendActionBar(Component.text("Balas: " + countAmmo(p), NamedTextColor.YELLOW));
    }

    // ---------------- Reglas de la bola de fuego ----------------

    @EventHandler
    public void onBulletHit(ProjectileHitEvent e) {
        Projectile proj = e.getEntity();
        if (!isBullet(proj)) return;

        Entity hit = e.getHitEntity();
        if (hit == null) return; // choco con bloque: se destruye sola

        // Las balas se atraviesan entre si
        if (hit instanceof Projectile) {
            e.setCancelled(true);
            return;
        }
        // Sin invulnerabilidad entre golpes para que el fuego automatico pegue siempre
        if (hit instanceof LivingEntity le) {
            le.setNoDamageTicks(0);
        }
    }

    @EventHandler
    public void onBulletDamage(EntityDamageByEntityEvent e) {
        if (isBullet(e.getDamager())) {
            e.setDamage(DAMAGE);
        }
    }

    @EventHandler
    public void onBulletCombust(EntityCombustByEntityEvent e) {
        if (isBullet(e.getCombuster())) e.setCancelled(true); // no prende fuego al objetivo
    }

    @EventHandler
    public void onBulletIgnite(BlockIgniteEvent e) {
        if (isBullet(e.getIgnitingEntity())) e.setCancelled(true); // no prende bloques
    }

    @EventHandler
    public void onBulletExplode(ExplosionPrimeEvent e) {
        if (isBullet(e.getEntity())) e.setCancelled(true); // sin explosion
    }

    // Las balas (pararrayos) no se pueden colocar
    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (isAmmo(e.getItemInHand())) e.setCancelled(true);
    }

    // ---------------- Comando de prueba ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Solo jugadores.");
            return true;
        }
        p.getInventory().addItem(createGlock(), createAmmo(64));
        p.sendMessage(Component.text("Te di una Glock y 64 balas.", NamedTextColor.GREEN));
        return true;
    }
}

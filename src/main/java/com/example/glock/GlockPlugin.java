package com.example.glock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class GlockPlugin extends JavaPlugin implements Listener {

    // ================= AJUSTES GENERALES =================
    private static final int HOLD_TIMEOUT_TICKS = 5;   // ticks sin click para dejar de disparar
    private static final double SPEED = 3.0;           // velocidad de la bala (flecha)
    private static final int LIFETIME_TICKS = 25;      // vida maxima de la bala
    private static final int NORMAL_PER_CRAFT = 8;     // balas normales por crafteo
    private static final int AP_PER_CRAFT = 6;         // balas perforadoras por crafteo
    // =====================================================

    /**
     * AJUSTES POR ARMA.
     * id, nombre, color, cargador, cadencia (ticks entre disparos; 20 ticks = 1 seg),
     * danio normal, danio perforante (ignora armadura), dispersion,
     * recarga (ticks), desgaste de armadura por bala perforante, tono del sonido
     */
    private enum Weapon {
        GLOCK("glock", "Glock", NamedTextColor.GRAY, 22, 5, 4.0, 5.0, 0.015, 30, 4, 1.6f),
        DRACO("draco", "Draco", NamedTextColor.RED, 30, 7, 6.0, 8.0, 0.035, 55, 8, 1.1f),
        ARP("arp", "ARP", NamedTextColor.GREEN, 30, 3, 2.0, 3.0, 0.030, 45, 3, 1.4f);

        final String id;
        final String display;
        final NamedTextColor color;
        final int magSize;
        final int interval;
        final double damage;
        final double apDamage;
        final double spread;
        final int reloadTicks;
        final int armorWear;
        final float pitch;

        Weapon(String id, String display, NamedTextColor color, int magSize, int interval,
               double damage, double apDamage, double spread, int reloadTicks,
               int armorWear, float pitch) {
            this.id = id;
            this.display = display;
            this.color = color;
            this.magSize = magSize;
            this.interval = interval;
            this.damage = damage;
            this.apDamage = apDamage;
            this.spread = spread;
            this.reloadTicks = reloadTicks;
            this.armorWear = armorWear;
            this.pitch = pitch;
        }

        static Weapon byId(String id) {
            if (id == null) return null;
            for (Weapon w : values()) {
                if (w.id.equals(id)) return w;
            }
            return null;
        }
    }

    private static final class Reload {
        final long start;
        final Weapon weapon;
        final boolean wantAp;
        final int slot;

        Reload(long start, Weapon weapon, boolean wantAp, int slot) {
            this.start = start;
            this.weapon = weapon;
            this.wantAp = wantAp;
            this.slot = slot;
        }
    }

    private NamespacedKey weaponKey;     // id del arma (texto)
    private NamespacedKey legacyGunKey;  // Glock de la version anterior
    private NamespacedKey magKey;        // balas en el cargador
    private NamespacedKey apFlagKey;     // tipo de bala del cargador (1 = perforante)
    private NamespacedKey ammoKey;       // item bala normal
    private NamespacedKey apAmmoKey;     // item bala perforante
    private NamespacedKey bulletKey;     // proyectil: arma que lo disparo
    private NamespacedKey bulletApKey;   // proyectil: es perforante

    private final List<NamespacedKey> recipeKeys = new ArrayList<>();

    private long tick = 0;
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Map<UUID, Long> nextShot = new HashMap<>();
    private final Map<UUID, Reload> reloads = new HashMap<>();

    @Override
    public void onEnable() {
        weaponKey = new NamespacedKey(this, "arma");
        legacyGunKey = new NamespacedKey(this, "glock");
        magKey = new NamespacedKey(this, "cargador");
        apFlagKey = new NamespacedKey(this, "tipo_ap");
        ammoKey = new NamespacedKey(this, "bala");
        apAmmoKey = new NamespacedKey(this, "bala_ap");
        bulletKey = new NamespacedKey(this, "proyectil");
        bulletApKey = new NamespacedKey(this, "proyectil_ap");

        registerRecipes();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tickAll, 1L, 1L);
        getLogger().info("GlockPlugin activado.");
    }

    @Override
    public void onDisable() {
        for (World w : Bukkit.getWorlds()) {
            for (AbstractArrow a : w.getEntitiesByClass(AbstractArrow.class)) {
                if (isBullet(a)) a.remove();
            }
        }
    }

    // ---------------- Items ----------------

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack createWeapon(Weapon w) {
        ItemStack item = new ItemStack(Material.CROSSBOW);
        CrossbowMeta meta = (CrossbowMeta) item.getItemMeta();
        meta.displayName(line(w.display, w.color));
        meta.lore(List.of(
                line("Manten click derecho: fuego automatico", NamedTextColor.DARK_GRAY),
                line("Cargador: " + w.magSize + " balas", NamedTextColor.DARK_GRAY),
                line("F o click izquierdo: recargar | Shift: cambiar tipo de bala", NamedTextColor.DARK_GRAY)));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE,
                ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(weaponKey, PersistentDataType.STRING, w.id);
        pdc.set(magKey, PersistentDataType.INTEGER, 0);
        pdc.set(apFlagKey, PersistentDataType.BYTE, (byte) 0);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createAmmo(boolean ap, int amount) {
        ItemStack item = new ItemStack(Material.LIGHTNING_ROD, amount);
        ItemMeta meta = item.getItemMeta();
        if (ap) {
            meta.displayName(line("Bala perforadora", NamedTextColor.AQUA));
            meta.lore(List.of(line("Ignora la armadura y la danya", NamedTextColor.DARK_GRAY)));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(apAmmoKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            meta.displayName(line("Bala", NamedTextColor.GOLD));
            meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.BYTE, (byte) 1);
        }
        item.setItemMeta(meta);
        return item;
    }

    private Weapon weaponOf(ItemStack item) {
        if (item == null || item.getType() != Material.CROSSBOW || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(weaponKey, PersistentDataType.STRING);
        if (id != null) return Weapon.byId(id);
        if (pdc.has(legacyGunKey, PersistentDataType.BYTE)) return Weapon.GLOCK;
        return null;
    }

    private boolean isAmmo(ItemStack item, boolean ap) {
        return item != null && item.getType() == Material.LIGHTNING_ROD && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer()
                .has(ap ? apAmmoKey : ammoKey, PersistentDataType.BYTE);
    }

    private boolean isAnyAmmo(ItemStack item) {
        return isAmmo(item, false) || isAmmo(item, true);
    }

    private boolean isBullet(Entity e) {
        return e != null && e.getPersistentDataContainer().has(bulletKey, PersistentDataType.STRING);
    }

    // ---------------- Estado del arma (cargador) ----------------

    private int getMag(ItemStack item) {
        Integer v = item.getItemMeta().getPersistentDataContainer().get(magKey, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    private boolean getMagAp(ItemStack item) {
        Byte v = item.getItemMeta().getPersistentDataContainer().get(apFlagKey, PersistentDataType.BYTE);
        return v != null && v == 1;
    }

    /** Guarda balas/tipo y deja la ballesta visualmente cargada o descargada. */
    private void writeState(ItemStack item, int mag, boolean ap, boolean loadedVisual) {
        CrossbowMeta cm = (CrossbowMeta) item.getItemMeta();
        PersistentDataContainer pdc = cm.getPersistentDataContainer();
        pdc.set(magKey, PersistentDataType.INTEGER, mag);
        pdc.set(apFlagKey, PersistentDataType.BYTE, (byte) (ap ? 1 : 0));
        boolean loaded = cm.hasChargedProjectiles();
        if (loadedVisual && !loaded) {
            cm.addChargedProjectile(new ItemStack(Material.ARROW));
        } else if (!loadedVisual && loaded) {
            cm.setChargedProjectiles(new ArrayList<>());
        }
        item.setItemMeta(cm);
    }

    /** Solo cambia lo visual. Devuelve true si cambio. */
    private boolean syncVisual(ItemStack item, boolean loadedVisual) {
        CrossbowMeta cm = (CrossbowMeta) item.getItemMeta();
        boolean loaded = cm.hasChargedProjectiles();
        if (loadedVisual && !loaded) {
            cm.addChargedProjectile(new ItemStack(Material.ARROW));
            item.setItemMeta(cm);
            return true;
        } else if (!loadedVisual && loaded) {
            cm.setChargedProjectiles(new ArrayList<>());
            item.setItemMeta(cm);
            return true;
        }
        return false;
    }

    // ---------------- Recetas ----------------

    private void addRecipe(String key, ItemStack result, String[] shape, Object... ingredients) {
        NamespacedKey nk = new NamespacedKey(this, key);
        Bukkit.removeRecipe(nk);
        ShapedRecipe r = new ShapedRecipe(nk, result);
        r.shape(shape);
        for (int i = 0; i < ingredients.length; i += 2) {
            r.setIngredient((Character) ingredients[i], (Material) ingredients[i + 1]);
        }
        Bukkit.addRecipe(r);
        recipeKeys.add(nk);
    }

    private void registerRecipes() {
        recipeKeys.clear();

        // Glock:  D I I / I G _ / I _ _
        addRecipe("glock_recipe", createWeapon(Weapon.GLOCK),
                new String[]{"DII", "IG ", "I  "},
                'D', Material.DIAMOND, 'I', Material.IRON_INGOT, 'G', Material.GOLD_INGOT);

        // Draco:  D I D / I G I / _ I _
        addRecipe("draco_recipe", createWeapon(Weapon.DRACO),
                new String[]{"DID", "IGI", " I "},
                'D', Material.DIAMOND, 'I', Material.IRON_INGOT, 'G', Material.GOLD_INGOT);

        // ARP:  I R D / I G R / I _ _
        addRecipe("arp_recipe", createWeapon(Weapon.ARP),
                new String[]{"IRD", "IGR", "I  "},
                'I', Material.IRON_INGOT, 'R', Material.REDSTONE,
                'D', Material.DIAMOND, 'G', Material.GOLD_INGOT);

        // Bala normal (columna): Cobre / Oro / Oro
        addRecipe("bala_recipe", createAmmo(false, NORMAL_PER_CRAFT),
                new String[]{"C", "G", "G"},
                'C', Material.COPPER_INGOT, 'G', Material.GOLD_INGOT);

        // Bala perforadora (columna): Diamante (punta) / Cobre / Cobre
        addRecipe("bala_ap_recipe", createAmmo(true, AP_PER_CRAFT),
                new String[]{"D", "C", "C"},
                'D', Material.DIAMOND, 'C', Material.COPPER_INGOT);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipes(recipeKeys);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastClick.remove(id);
        nextShot.remove(id);
        reloads.remove(id);
    }

    // ---------------- Municion en el inventario ----------------

    private int countAmmo(Player p, boolean ap) {
        int total = 0;
        for (ItemStack stack : p.getInventory().getContents()) {
            if (isAmmo(stack, ap)) total += stack.getAmount();
        }
        return total;
    }

    /** Quita hasta 'n' balas del inventario. Devuelve cuantas quito. */
    private int takeAmmo(Player p, boolean ap, int n) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        int remaining = n;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (!isAmmo(stack, ap)) continue;
            int take = Math.min(remaining, stack.getAmount());
            remaining -= take;
            if (stack.getAmount() - take <= 0) {
                inv.setItem(i, null);
            } else {
                stack.setAmount(stack.getAmount() - take);
                inv.setItem(i, stack);
            }
        }
        return n - remaining;
    }

    private void giveBack(Player p, boolean ap, int n) {
        if (n <= 0) return;
        Map<Integer, ItemStack> left = p.getInventory().addItem(createAmmo(ap, n));
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    // ---------------- Recarga ----------------

    private void startReload(Player p, Weapon w, boolean swapType) {
        UUID id = p.getUniqueId();
        PlayerInventory inv = p.getInventory();
        ItemStack item = inv.getItemInMainHand();
        int mag = getMag(item);
        boolean flag = getMagAp(item);

        if (mag >= w.magSize) {
            p.sendActionBar(line("Cargador lleno", NamedTextColor.YELLOW));
            return;
        }
        if (countAmmo(p, false) + countAmmo(p, true) == 0) {
            p.playSound(p.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 0.6f, 1.6f);
            p.sendActionBar(line("Sin balas de reserva", NamedTextColor.RED));
            return;
        }

        boolean want = swapType ? !flag : flag;
        reloads.put(id, new Reload(tick, w, want, inv.getHeldItemSlot()));
        lastClick.remove(id);

        writeState(item, mag, flag, false); // ballesta se ve descargada
        inv.setItemInMainHand(item);
        p.setCooldown(Material.CROSSBOW, w.reloadTicks);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_START, 0.8f, 1.0f);
    }

    private void cancelReload(Player p) {
        reloads.remove(p.getUniqueId());
        p.setCooldown(Material.CROSSBOW, 0);
        p.sendActionBar(line("Recarga cancelada", NamedTextColor.RED));
    }

    private void finishReload(Player p, Reload r) {
        UUID id = p.getUniqueId();
        PlayerInventory inv = p.getInventory();
        ItemStack item = inv.getItemInMainHand();
        reloads.remove(id);

        int mag = getMag(item);
        boolean flag = getMagAp(item);
        boolean want = r.wantAp;

        if (mag > 0 && flag != want) {
            if (countAmmo(p, want) > 0) {
                giveBack(p, flag, mag); // descarga el tipo anterior
                mag = 0;
            } else {
                want = flag; // no hay del tipo pedido: completa con el actual
            }
        }
        if (mag == 0 && countAmmo(p, want) == 0) want = !want;

        int taken = takeAmmo(p, want, r.weapon.magSize - mag);
        mag += taken;
        boolean ap = (mag > 0) ? want : flag;

        writeState(item, mag, ap, mag > 0);
        inv.setItemInMainHand(item);
        p.setCooldown(Material.CROSSBOW, 0);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 0.9f, 1.0f);
        p.sendActionBar(line("Cargador: " + mag + "/" + r.weapon.magSize
                + (ap ? "  [PERFORANTE]" : "  [NORMAL]"), NamedTextColor.GREEN));
    }

    private void reloadProgress(Player p, Reload r) {
        long elapsed = tick - r.start;
        int total = r.weapon.reloadTicks;
        if (elapsed == (long) (total * 0.4)) {
            p.getWorld().playSound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 0.8f, 1.0f);
        }
        if (tick % 2 == 0) {
            int bars = 10;
            int filled = (int) Math.min(bars, (elapsed * bars) / total);
            StringBuilder sb = new StringBuilder("Recargando [");
            for (int i = 0; i < bars; i++) sb.append(i < filled ? '#' : '-');
            sb.append(']');
            p.sendActionBar(line(sb.toString(), NamedTextColor.YELLOW));
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        Weapon w = weaponOf(p.getInventory().getItemInMainHand());
        if (w == null) return;
        e.setCancelled(true); // F = recargar
        if (reloads.containsKey(p.getUniqueId())) return;
        startReload(p, w, p.isSneaking());
    }

    // ---------------- Loop principal (cada tick) ----------------

    private void tickAll() {
        tick++;
        boolean sync = (tick % 2 == 0);

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            PlayerInventory inv = p.getInventory();
            ItemStack main = inv.getItemInMainHand();
            ItemStack off = inv.getItemInOffHand();
            Weapon w = weaponOf(main);
            Weapon ow = weaponOf(off);

            Reload rel = reloads.get(id);
            if (rel != null) {
                if (w != rel.weapon || inv.getHeldItemSlot() != rel.slot) {
                    cancelReload(p);
                    rel = null;
                } else if (tick - rel.start >= rel.weapon.reloadTicks) {
                    finishReload(p, rel);
                    rel = null;
                    main = inv.getItemInMainHand();
                } else {
                    reloadProgress(p, rel);
                }
            }

            if (sync) {
                if (w != null) {
                    boolean desired = getMag(main) > 0 && rel == null;
                    if (syncVisual(main, desired)) inv.setItemInMainHand(main);
                }
                if (ow != null) {
                    if (syncVisual(off, getMag(off) > 0)) inv.setItemInOffHand(off);
                }
            }

            Long last = lastClick.get(id);
            if (last == null) continue;

            if (w == null || rel != null || tick - last > HOLD_TIMEOUT_TICKS) {
                lastClick.remove(id);
                continue;
            }

            if (tick >= nextShot.getOrDefault(id, 0L)) {
                int mag = getMag(main);
                if (mag <= 0) {
                    lastClick.remove(id);
                    startReload(p, w, false); // recarga automatica
                    continue;
                }
                boolean ap = getMagAp(main);
                shoot(p, w, ap);
                mag--;
                writeState(main, mag, ap, mag > 0);
                inv.setItemInMainHand(main);
                nextShot.put(id, tick + w.interval);

                if (mag == 0) {
                    p.sendActionBar(line(w.display + "  0/" + w.magSize + "  - Recargando...",
                            NamedTextColor.RED));
                } else {
                    p.sendActionBar(line(w.display + "  " + mag + "/" + w.magSize
                            + "  |  Reserva: " + countAmmo(p, ap)
                            + (ap ? "  [PERFORANTE]" : ""), NamedTextColor.YELLOW));
                }
            }
        }
    }

    // ---------------- Disparo ----------------

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        Weapon w = weaponOf(item);
        if (w == null) return;

        Action a = e.getAction();

        // Click izquierdo al aire = recargar (para Bedrock/movil, donde no hay tecla F)
        if (a == Action.LEFT_CLICK_AIR) {
            if (e.getHand() != EquipmentSlot.HAND) return;
            Player lp = e.getPlayer();
            if (!reloads.containsKey(lp.getUniqueId())) startReload(lp, w, lp.isSneaking());
            return;
        }

        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        // Evita que la ballesta dispare/cargue flechas de forma vanilla
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (reloads.containsKey(id)) return;

        if (getMag(item) <= 0) {
            startReload(p, w, false); // sin balas en el cargador: recarga sola
            return;
        }
        lastClick.put(id, tick);
    }

    @EventHandler
    public void onShootBow(EntityShootBowEvent e) {
        if (weaponOf(e.getBow()) != null) e.setCancelled(true);
    }

    private void shoot(Player p, Weapon w, boolean ap) {
        World world = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (w.spread > 0) {
            dir.add(new Vector(r.nextDouble(-w.spread, w.spread),
                    r.nextDouble(-w.spread, w.spread),
                    r.nextDouble(-w.spread, w.spread))).normalize();
        }
        Location spawn = eye.clone().add(dir.clone().multiply(1.0));

        Arrow arrow = world.spawn(spawn, Arrow.class, a -> {
            a.setShooter(p);
            a.setVelocity(dir.clone().multiply(SPEED));
            a.setGravity(false);
            a.setSilent(true);
            a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            a.getPersistentDataContainer().set(bulletKey, PersistentDataType.STRING, w.id);
            if (ap) {
                a.getPersistentDataContainer().set(bulletApKey, PersistentDataType.BYTE, (byte) 1);
            }
        });

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (arrow.isValid()) arrow.remove();
        }, LIFETIME_TICKS);

        world.spawnParticle(Particle.SMOKE, spawn, 1, 0.02, 0.02, 0.02, 0.0);
        world.playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 0.8f, w.pitch);
    }

    // ---------------- Impacto y danio ----------------

    @EventHandler
    public void onBulletHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow)) return;
        AbstractArrow arrow = (AbstractArrow) e.getEntity();
        if (!isBullet(arrow)) return;

        Entity hit = e.getHitEntity();
        if (hit == null) { // choco con un bloque
            arrow.remove();
            return;
        }

        ProjectileSource src = arrow.getShooter();
        // Atraviesa proyectiles, al propio tirador y cosas que no son seres vivos
        if (hit instanceof Projectile || hit.equals(src) || !(hit instanceof LivingEntity)) {
            e.setCancelled(true);
            return;
        }

        LivingEntity target = (LivingEntity) hit;
        e.setCancelled(true); // el danio lo aplicamos nosotros
        arrow.remove();

        Weapon w = Weapon.byId(arrow.getPersistentDataContainer().get(bulletKey, PersistentDataType.STRING));
        if (w == null) return;
        boolean ap = arrow.getPersistentDataContainer().has(bulletApKey, PersistentDataType.BYTE);
        Entity shooter = (src instanceof Entity) ? (Entity) src : null;

        // Sin invulnerabilidad entre golpes para que el fuego automatico pegue siempre
        target.setNoDamageTicks(0);

        if (ap) {
            wearArmor(target, w.armorWear);
            DamageSource.Builder builder = DamageSource.builder(DamageType.INDIRECT_MAGIC);
            if (shooter != null) {
                builder.withCausingEntity(shooter).withDirectEntity(shooter);
            }
            target.damage(w.apDamage, builder.build()); // este tipo de danio ignora armadura
        } else if (shooter != null) {
            target.damage(w.damage, shooter);
        } else {
            target.damage(w.damage);
        }

        if (shooter instanceof Player) {
            Player sp = (Player) shooter;
            sp.playSound(sp.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 0.6f, ap ? 1.8f : 1.2f);
        }
    }

    /** Desgasta cada pieza de armadura del objetivo. */
    private void wearArmor(LivingEntity target, int amount) {
        EntityEquipment eq = target.getEquipment();
        if (eq == null) return;
        ItemStack[] armor = eq.getArmorContents();
        boolean changed = false;
        boolean broke = false;
        for (int i = 0; i < armor.length; i++) {
            ItemStack piece = armor[i];
            if (piece == null || piece.getType().isAir()) continue;
            int max = piece.getType().getMaxDurability();
            if (max <= 0) continue;
            ItemMeta meta = piece.getItemMeta();
            if (meta == null || meta.isUnbreakable()) continue;
            if (!(meta instanceof org.bukkit.inventory.meta.Damageable)) continue;
            org.bukkit.inventory.meta.Damageable dm = (org.bukkit.inventory.meta.Damageable) meta;
            int newDamage = dm.getDamage() + amount;
            if (newDamage >= max) {
                armor[i] = null;
                broke = true;
            } else {
                dm.setDamage(newDamage);
                piece.setItemMeta(meta);
            }
            changed = true;
        }
        if (changed) eq.setArmorContents(armor);
        if (broke) {
            target.getWorld().playSound(target.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0f, 1.0f);
        }
    }

    // Las balas (pararrayos) no se pueden colocar
    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (isAnyAmmo(e.getItemInHand())) e.setCancelled(true);
    }

    // ---------------- Comando de prueba ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Solo jugadores.");
            return true;
        }
        Player p = (Player) sender;
        p.getInventory().addItem(createWeapon(Weapon.GLOCK), createWeapon(Weapon.DRACO),
                createWeapon(Weapon.ARP), createAmmo(false, 64), createAmmo(true, 64));
        p.sendMessage(Component.text("Te di Glock, Draco, ARP y balas (normales y perforadoras).",
                NamedTextColor.GREEN));
        return true;
    }
}
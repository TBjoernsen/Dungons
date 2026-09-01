package nl.riddernix.dungeonplugin.classes

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent
import io.papermc.paper.event.player.PlayerArmSwingEvent
import net.kyori.adventure.text.Component
import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Weapon locks, armour stripping and the deferred ranged-cast input path. */
class CoreListener(private val plugin: DungeonPlugin) : Listener {

    private val permittedProjectiles = HashSet<UUID>()
    private val pendingRangedCasts = HashSet<UUID>()
    private val interactionBlockedRangedCasts = HashSet<UUID>()

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        stripArmor(event.player)
        plugin.refreshClassPlayer(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.classes.save()
        plugin.classAbilities.remove(event.player)
        plugin.classFeedback.remove(event.player)
        pendingRangedCasts.remove(event.player.uniqueId)
        interactionBlockedRangedCasts.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            stripArmor(event.player)
            plugin.refreshClassPlayer(event.player)
        })
    }

    @EventHandler(ignoreCancelled = true)
    fun onArmorInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item
        if (event.action.isRightClick && plugin.classItems.isSkillShard(item)) {
            event.isCancelled = true
            // Skill Shards are intentionally inert when right-clicked; they
            // pay for tree resets through the command instead.
            return
        }
        if (event.action.isRightClick && plugin.classItems.isSoulShard(item)) {
            event.isCancelled = true
            plugin.classPicker.open(player)
            player.sendMessage("§dChoose a class to use a Soul Shard for a locked class change.")
            return
        }
        if (isArmor(item)) {
            event.isCancelled = true
            player.sendMessage("§cArmor gear is disabled; Armor comes from your skill tree.")
            return
        }
        val classType = plugin.classes.activeClass(player.uniqueId)
        if (plugin.queries.isInDungeon(player) && plugin.classItems.isRestrictedWeapon(item) &&
            (classType == null || !plugin.classItems.isAllowedWeapon(classType, item))) {
            event.isCancelled = true
            player.sendMessage("§cThat weapon is locked to another class.")
        }
    }

    /**
     * PlayerArmSwingEvent is also delivered for left-clicking a block. Record
     * all genuine block interactions before the deferred ranged cast is
     * evaluated so mining, buttons, containers, and similar interactions
     * never turn into a spell or Focus Shot.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun blockRangedCastForBlockInteraction(event: PlayerInteractEvent) {
        if (event.action != Action.LEFT_CLICK_AIR && event.action != Action.RIGHT_CLICK_AIR) {
            blockRangedCast(event.player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun blockRangedCastForEntityInteraction(event: PlayerInteractEntityEvent) {
        blockRangedCast(event.player)
    }

    /**
     * An arm swing is the actual left-mouse action. It fires in open air and
     * while aiming at an entity, so casting is not dependent on a block
     * interaction or melee reach.
     */
    @EventHandler(ignoreCancelled = true)
    fun onMageArmSwing(event: PlayerArmSwingEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (!plugin.queries.isInDungeon(player)) return
        val item = player.inventory.itemInMainHand
        if (!plugin.classItems.isStaff(item) && !plugin.classItems.isAllowedWeapon(ClassType.ARCHER, item)) return
        if (!pendingRangedCasts.add(player.uniqueId)) return

        // PlayerInteractEvent and PlayerArmSwingEvent do not have a fixed
        // delivery order. Waiting one tick lets the interaction handlers
        // above cancel this cast reliably.
        plugin.server.scheduler.runTask(plugin, Runnable {
            pendingRangedCasts.remove(player.uniqueId)
            if (!player.isOnline || !plugin.queries.isInDungeon(player) || isRangedCastBlocked(player)) return@Runnable
            castRangedAttack(player)
        })
    }

    private fun castRangedAttack(player: Player) {
        when {
            plugin.classItems.isStaff(player.inventory.itemInMainHand) -> when (plugin.classPassives.castArcaneBolt(player)) {
                ArcaneCastResult.COOLDOWN -> Unit
                ArcaneCastResult.INSUFFICIENT_MANA -> player.sendActionBar(Component.text("§cNot enough Mana."))
                ArcaneCastResult.MANA_LOCKED -> player.sendActionBar(Component.text("§cOnly Mages can cast Arcane Bolt."))
                else -> Unit
            }
            plugin.classItems.isAllowedWeapon(ClassType.ARCHER, player.inventory.itemInMainHand) ->
                plugin.classPassives.castFocusShot(player)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPaladinSneak(event: PlayerToggleSneakEvent) {
        if (!event.isSneaking) return
        if (!plugin.queries.isInDungeon(event.player)) return
        when (plugin.classPassives.activateTaunt(event.player)) {
            TauntActivationResult.NOT_READY -> {
                event.player.sendActionBar(Component.text("§7Taunt is not charged yet."))
            }
            else -> Unit
        }
    }

    @EventHandler
    fun onArmorChange(event: PlayerArmorChangeEvent) {
        if (!isArmor(event.newItem)) return
        // Paper exposes this as a notification event rather than a
        // cancellable one. Remove the item on the next tick; interact and
        // inventory paths are blocked proactively.
        plugin.server.scheduler.runTask(plugin, Runnable { stripArmor(event.player) })
    }

    @EventHandler(ignoreCancelled = true)
    fun onHeldItem(event: PlayerItemHeldEvent) {
        val player = event.player
        val item = player.inventory.getItem(event.newSlot)
        val classType = plugin.classes.activeClass(player.uniqueId)
        if (plugin.queries.isInDungeon(player) && plugin.classItems.isRestrictedWeapon(item) &&
            (classType == null || !plugin.classItems.isAllowedWeapon(classType, item))) {
            event.isCancelled = true
            player.sendMessage("§cThat weapon is locked to another class.")
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onArmorInventory(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val cursorArmor = isArmor(event.cursor)
        val currentArmor = isArmor(event.currentItem)
        if (cursorArmor && event.slotType == InventoryType.SlotType.ARMOR ||
            currentArmor && (event.isShiftClick || event.isRightClick)) {
            event.isCancelled = true
            player.sendMessage("§cArmor gear is disabled; Armor comes from your skill tree.")
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onMeleeDamage(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        if (!plugin.queries.isInDungeon(player)) return
        val classType = plugin.classes.activeClass(player.uniqueId)
        if (classType == null) {
            event.isCancelled = true
            player.sendMessage("§cChoose a class with /class before attacking.")
            return
        }
        if (!plugin.classItems.isAllowedWeapon(classType, player.inventory.itemInMainHand)) {
            event.isCancelled = true
            player.sendMessage("§cYou must attack with your ${classType.weaponDescription}.")
            return
        }
        plugin.classPassives.handleDamage(event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBowShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        if (!plugin.queries.isInDungeon(player)) return
        val classType = plugin.classes.activeClass(player.uniqueId)
        if (classType != ClassType.ARCHER || !plugin.classItems.isAllowedWeapon(ClassType.ARCHER, event.bow)) {
            event.isCancelled = true
            player.sendMessage("§cOnly Archers can fire bows.")
            return
        }
        permittedProjectiles += event.projectile.uniqueId
        plugin.classPassives.handleBowShoot(event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onProjectileDamage(event: EntityDamageByEntityEvent) {
        val projectile = event.damager as? Projectile ?: return
        val player = projectile.shooter as? Player ?: return
        if (!plugin.queries.isInDungeon(player)) return
        if (plugin.classItems.isArcaneBolt(projectile)) {
            plugin.classPassives.handleArcaneBoltDamage(event, player, projectile)
            return
        }
        if (plugin.classItems.isFocusShot(projectile)) {
            plugin.classPassives.handleFocusShotDamage(event, player, projectile)
            return
        }
        if (projectile.uniqueId !in permittedProjectiles) {
            event.isCancelled = true
            return
        }
        plugin.classPassives.handleProjectileDamage(event, player)
    }

    @EventHandler
    fun onProjectileHit(event: ProjectileHitEvent) {
        val projectile = event.entity
        if (plugin.classItems.isArcaneBolt(projectile)) {
            plugin.classPassives.handleArcaneBoltHit(event)
            return
        }
        if (plugin.classItems.isFocusShot(projectile)) return
        if (projectile.uniqueId !in permittedProjectiles) return
        val player = projectile.shooter as? Player ?: return
        plugin.classPassives.handleProjectileMiss(event, player)
        // Paper can fire ProjectileHitEvent before the corresponding arrow
        // damage event. Keep this one-tick authorization window so a valid
        // Archer shot is not cancelled.
        plugin.server.scheduler.runTask(plugin, Runnable {
            permittedProjectiles.remove(projectile.uniqueId)
        })
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onIncomingDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!plugin.queries.isInDungeon(player)) return
        plugin.classPassives.handleIncomingDamage(event)
    }

    /** Bosses are dungeon mobs too, so this removes i-frames from all dungeon enemies. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun removeDungeonMobDamageIFrames(event: EntityDamageEvent) {
        val mob = event.entity as? LivingEntity ?: return
        if (!plugin.queries.isDungeonMob(mob)) return
        mob.maximumNoDamageTicks = 0
        mob.noDamageTicks = 0
    }

    fun stripArmor(player: Player) {
        val equipped = player.inventory.armorContents.filterNotNull().filter { !it.type.isAir }
        if (equipped.isEmpty()) return
        player.inventory.armorContents = arrayOfNulls(4)
        equipped.forEach { armor -> plugin.classItems.give(player, armor) }
        player.sendMessage("§cArmor gear was removed; Armor is provided by your skill tree.")
    }

    private fun blockRangedCast(player: Player) {
        val playerId = player.uniqueId
        interactionBlockedRangedCasts += playerId
        // The cast itself is deferred by one server tick because Paper does
        // not guarantee the relative delivery order of PlayerInteractEvent
        // and PlayerArmSwingEvent. Keep this lock through that tick instead
        // of relying on wall-clock time: a laggy tick must not turn a button
        // press or a mined block into a spell cast.
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            interactionBlockedRangedCasts.remove(playerId)
        }, 2L)
    }

    private fun isRangedCastBlocked(player: Player): Boolean =
        player.uniqueId in interactionBlockedRangedCasts

    private fun isArmor(item: ItemStack?): Boolean {
        val type = item?.type ?: return false
        return type.name.endsWith("_HELMET") || type.name.endsWith("_CHESTPLATE") ||
            type.name.endsWith("_LEGGINGS") || type.name.endsWith("_BOOTS") || type == Material.TURTLE_HELMET
    }
}

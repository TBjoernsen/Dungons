package dev.thorb.classskills.ui

import dev.thorb.classskills.ClassSkillsPlugin
import dev.thorb.classskills.model.ClassType
import dev.thorb.classskills.model.NodeKind
import dev.thorb.classskills.model.SkillNode
import dev.thorb.classskills.model.StatType
import dev.thorb.classskills.service.PurchaseResult
import dev.thorb.classskills.service.RefundResult
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

private abstract class SkillMenuHolder : InventoryHolder {
    private lateinit var menuInventory: Inventory
    fun attach(inventory: Inventory) { menuInventory = inventory }
    override fun getInventory(): Inventory = menuInventory
}

private class ClassMenuHolder : SkillMenuHolder()
private class BranchMenuHolder(val nodesBySlot: Map<Int, BranchNode>) : SkillMenuHolder()

private enum class Branch { ATTACK, VITALITY, ARMOR, SIGNATURE }
private enum class NodeState { LOCKED, AVAILABLE, PARTIAL, COMPLETE }
private data class BranchNode(val branch: Branch, val nodes: List<SkillNode>, val title: String)

class SkillMenus(private val plugin: ClassSkillsPlugin) : Listener {
    private val oraxen = OraxenItemBridge()

    fun openClassMenu(player: Player) {
        val holder = ClassMenuHolder()
        val inventory = Bukkit.createInventory(holder, 54, "§8Choose your class")
        holder.attach(inventory)
        for (slot in 0 until inventory.size) {
            inventory.setItem(slot, menuItem(Material.BLACK_STAINED_GLASS_PANE, "§8 ", emptyList()))
        }
        inventory.setItem(4, menuItem(Material.NETHER_STAR, "§6§lCHOOSE YOUR CLASS", listOf(
            "§7Each class has its own weapon, passive, and skill tree.",
            "§8Hover a class card to compare its playstyle."
        )))
        ClassType.entries.forEachIndexed { index, classType ->
            val locked = plugin.store.get(player.uniqueId).classType != null &&
                plugin.store.get(player.uniqueId).unlockedDifficulty >= 3
            inventory.setItem(listOf(11, 13, 15, 17)[index], menuItem(
                classType.icon, classColour(classType) + "§l${classType.displayName}", listOf(
                    "§8━━━━━━━━━━━━━━━━━━",
                    "§7${classRole(classType)}",
                    " ",
                    "§7Weapon: §f${classType.weaponDescription}",
                    "§7Signature: §f${classType.passiveName}",
                    " ",
                    if (locked) "§dRequires 1 Soul Shard and resets to Level 1." else "§aFree until Difficulty 3.",
                    "§eClick to choose ${classType.displayName}"
                )
            ))
        }
        inventory.setItem(49, menuItem(Material.KNOWLEDGE_BOOK, "§eClass guide", listOf(
            "§7Warrior: melee damage and Rage.",
            "§7Archer: precision and Focus.",
            "§7Paladin: tanking and Taunt.",
            "§7Mage: mana and Arcane Bolt."
        )))
        player.openInventory(inventory)
    }

    /** Oraxen-ready 54-slot canvas. The pack background supplies headings and connector lines. */
    fun openTierMenu(player: Player) {
        val classType = plugin.store.get(player.uniqueId).classType ?: run {
            openClassMenu(player)
            return
        }
        val holder = BranchMenuHolder(branchNodes(classType))
        val inventory = Bukkit.createInventory(holder, 54, "§0Skill Tree • ${classType.displayName}")
        holder.attach(inventory)
        val data = plugin.store.get(player.uniqueId)
        holder.nodesBySlot.forEach { (slot, visual) ->
            val rank = visual.nodes.count { it.id in data.derivedNodeIds }
            val current = visual.nodes.firstOrNull { it.id !in data.derivedNodeIds }
            val state = nodeState(data, current, rank, visual.nodes.size)
            inventory.setItem(slot, nodeItem(visual, current, rank, state))
        }
        inventory.setItem(52, menuItem(Material.NETHER_STAR, "§e${data.availablePoints} points available", listOf(
            "§7Level ${data.level}/99 • Difficulty ${data.unlockedDifficulty}/9",
            "§7Left-click to invest • Right-click to revoke.",
            if (oraxen.available) "§8Oraxen node textures active." else "§8Oraxen not found: vanilla fallback icons active."
        )))
        inventory.setItem(53, menuItem(Material.BARRIER, "§cClose", listOf("§7Close the skill tree.")))
        player.openInventory(inventory)
    }

    @EventHandler
    fun onMenuClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? SkillMenuHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.rawSlot !in 0 until event.view.topInventory.size) return
        when (holder) {
            is ClassMenuHolder -> selectClass(player, event.rawSlot)
            is BranchMenuHolder -> {
                if (event.rawSlot == 53) {
                    player.closeInventory()
                    return
                }
                val visual = holder.nodesBySlot[event.rawSlot] ?: return
                val data = plugin.store.get(player.uniqueId)
                val purchased = visual.nodes.filter { it.id in data.derivedNodeIds }
                if (event.isRightClick && purchased.isNotEmpty()) {
                    val highestPurchased = purchased.last()
                    when (plugin.progression.refundOne(player, highestPurchased.id)) {
                        RefundResult.SUCCESS -> player.sendMessage("§aRevoked ${visual.title}; points refunded.")
                        RefundResult.HAS_DEPENDENTS -> player.sendMessage("§cRevoke connected lower nodes first.")
                        RefundResult.NEEDS_SKILL_SHARD -> player.sendMessage("§cYou need one Skill Shard to revoke a node.")
                        else -> player.sendMessage("§cThat node could not be revoked.")
                    }
                    openTierMenu(player)
                    return
                }
                val next = visual.nodes.firstOrNull { it.id !in data.derivedNodeIds } ?: return
                when (plugin.progression.purchase(player, next.id)) {
                    PurchaseResult.SUCCESS -> player.sendMessage("§aInvested in ${visual.title}.")
                    PurchaseResult.DIFFICULTY_LOCKED -> player.sendMessage("§cUnlock Dungeon Difficulty ${next.tier} first.")
                    PurchaseResult.PREREQUISITE_LOCKED -> player.sendMessage("§cUnlock the connected node above first.")
                    PurchaseResult.INSUFFICIENT_POINTS -> player.sendMessage("§cYou need ${next.cost} Skill Points.")
                    else -> player.sendMessage("§cThat node cannot be unlocked.")
                }
                openTierMenu(player)
            }
        }
    }

    private fun selectClass(player: Player, rawSlot: Int) {
        val indexes = listOf(11, 13, 15, 17)
        val index = indexes.indexOf(rawSlot)
        if (index < 0) return
        val result = plugin.progression.selectClass(player, ClassType.entries[index])
        when (result.name) {
            "ALREADY_SELECTED" -> player.sendMessage("§eYou already have that class.")
            "NEEDS_SOUL_SHARD" -> player.sendMessage("§cA locked class needs one Soul Shard to change.")
            else -> Unit
        }
        if (result.name == "SUCCESS") openTierMenu(player)
    }

    private fun classColour(classType: ClassType): String = when (classType) {
        ClassType.WARRIOR -> "§c"
        ClassType.ARCHER -> "§a"
        ClassType.PALADIN -> "§6"
        ClassType.MAGE -> "§5"
    }

    private fun classRole(classType: ClassType): String = when (classType) {
        ClassType.WARRIOR -> "§cFrontline damage dealer"
        ClassType.ARCHER -> "§aLong-range precision striker"
        ClassType.PALADIN -> "§6Defensive frontline tank"
        ClassType.MAGE -> "§5Mana-based ranged caster"
    }

    private fun branchNodes(classType: ClassType): Map<Int, BranchNode> {
        val slots = mapOf(
            Branch.ATTACK to listOf(0, 9, 18, 27, 36, 45, 46, 47, 48),
            Branch.VITALITY to listOf(1, 10, 19, 28, 37, 38, 39, 40, 41),
            Branch.ARMOR to listOf(2, 11, 20, 29, 30, 31, 32, 33, 34),
            Branch.SIGNATURE to listOf(3, 12, 21, 22, 23)
        )
        val stats = mapOf(Branch.ATTACK to StatType.ATTACK, Branch.VITALITY to StatType.HEALTH, Branch.ARMOR to StatType.ARMOR)
        return buildMap {
            stats.forEach { (branch, stat) ->
                (1..9).forEach { tier ->
                    val nodes = plugin.catalog.forClassAndTier(classType, tier).filter { it.statType == stat }
                    put(slots.getValue(branch)[tier - 1], BranchNode(branch, nodes, "${stat.displayName} • Tier $tier"))
                }
            }
            plugin.catalog.nodes.filter { it.classType == classType && it.kind == NodeKind.SIGNATURE_UPGRADE }
                .forEachIndexed { index, node ->
                    put(slots.getValue(Branch.SIGNATURE)[index], BranchNode(Branch.SIGNATURE, listOf(node), "${classType.passiveName} ${node.passiveRank}"))
                }
        }
    }

    private fun nodeState(data: dev.thorb.classskills.model.PlayerSkillData, next: SkillNode?, rank: Int, maxRank: Int): NodeState = when {
        rank == maxRank -> NodeState.COMPLETE
        rank > 0 -> NodeState.PARTIAL
        next == null || next.tier > data.unlockedDifficulty || !plugin.catalog.hasPrerequisites(data, next) -> NodeState.LOCKED
        else -> NodeState.AVAILABLE
    }

    private fun nodeItem(visual: BranchNode, next: SkillNode?, rank: Int, state: NodeState): ItemStack {
        val item = oraxen.item("classskills_node_${state.name.lowercase()}") ?: ItemStack(fallbackMaterial(visual.branch, state))
        item.itemMeta = item.itemMeta.apply {
            setDisplayName(nodeColor(visual.branch, state) + visual.title)
            lore = buildList {
                add("§7Rank: §f$rank/${visual.nodes.size}")
                val effect = next ?: visual.nodes.last()
                add("§7${effectText(effect)}")
                add("§7Cost: §e${next?.cost ?: 0} point${if ((next?.cost ?: 0) == 1) "" else "s"}")
                add(when (state) {
                    NodeState.LOCKED -> "§8Locked: unlock its connected prerequisite or tier."
                    NodeState.AVAILABLE -> "§eLeft-click to invest."
                    NodeState.PARTIAL -> "§6In progress • Left-click for the next rank."
                    NodeState.COMPLETE -> "§aComplete • Right-click to revoke."
                })
            }
        }
        return item
    }

    private fun effectText(node: SkillNode): String = when (node.statType) {
        StatType.ATTACK -> "+${node.value.toInt()} Attack per rank"
        StatType.HEALTH -> "+${node.value / 2.0} hearts per rank"
        StatType.ARMOR -> "+${node.value.toInt()} Armor per rank"
        null -> "${node.classType.passiveName} upgrade"
    }

    private fun fallbackMaterial(branch: Branch, state: NodeState): Material = when (state) {
        NodeState.LOCKED -> Material.GRAY_DYE
        NodeState.AVAILABLE -> when (branch) {
            Branch.ATTACK -> Material.RED_DYE
            Branch.VITALITY -> Material.YELLOW_DYE
            Branch.ARMOR -> Material.BLUE_DYE
            Branch.SIGNATURE -> Material.PURPLE_DYE
        }
        NodeState.PARTIAL -> Material.ORANGE_DYE
        NodeState.COMPLETE -> Material.LIME_DYE
    }

    private fun nodeColor(branch: Branch, state: NodeState): String = when (state) {
        NodeState.LOCKED -> "§8"
        NodeState.PARTIAL -> "§6"
        NodeState.COMPLETE -> "§a"
        NodeState.AVAILABLE -> when (branch) {
            Branch.ATTACK -> "§c"
            Branch.VITALITY -> "§e"
            Branch.ARMOR -> "§9"
            Branch.SIGNATURE -> "§5"
        }
    }

    private fun menuItem(material: Material, name: String, lore: List<String>): ItemStack = ItemStack(material).also { item ->
        item.itemMeta = item.itemMeta.apply {
            setDisplayName(name)
            this.lore = lore
        }
    }
}

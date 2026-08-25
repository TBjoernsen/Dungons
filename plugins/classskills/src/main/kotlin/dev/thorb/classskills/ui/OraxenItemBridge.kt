package dev.thorb.classskills.ui

import org.bukkit.inventory.ItemStack

/** Optional reflection bridge so Oraxen remains a soft dependency. */
class OraxenItemBridge {
    private val getItemById = runCatching {
        Class.forName("io.th0rgal.oraxen.api.OraxenItems").getMethod("getItemById", String::class.java)
    }.getOrNull()

    fun item(id: String): ItemStack? = runCatching {
        val builder = getItemById?.invoke(null, id) ?: return null
        builder.javaClass.getMethod("build").invoke(builder) as? ItemStack
    }.getOrNull()

    val available: Boolean get() = getItemById != null
}

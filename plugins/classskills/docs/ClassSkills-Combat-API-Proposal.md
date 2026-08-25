# ClassSkills Combat API — proposal for DungeonForge

## Purpose and status

This is the proposed **read-only combat integration surface** for ClassSkills.
It is intentionally separate from the existing progression API:

- DungeonForge owns dungeon structure, dungeon progression, and its skill-panel
  presentation.
- ClassSkills owns player combat calculations, class resources, custom items,
  temporary class buffs, and class-specific ability state.

The package will be additive from API version 1 onward. The planned public
packages are:

```text
dev.thorb.classskills.combat.api
dev.thorb.classskills.combat.api.event
```

No ClassSkills internal service, storage type, or mutable player model will be
part of this API.

## Ownership and write access

**Recommendation: DungeonForge should not receive general write access to
ClassSkills.**

DungeonForge needs to query combat state and react to changes. It does not need
to alter a player's class, skill points, unlocked nodes, Mana, Focus, Rage, or
attributes directly. Allowing broad writes would create ambiguous ownership,
make reset behavior fragile, and make it much harder to prevent exploits.

If a real cross-plugin write need appears later, add one narrow, explicit method
for that use case. For example, a future `applyTemporaryExternalModifier(...)`
could use an owner key, a duration, and a documented stacking rule. Do not add
generic setters such as `setMana`, `setAttack`, or `setClass`.

## Service lookup

ClassSkills will register one Bukkit service:

```java
public interface ClassSkillsCombatApi {
    int API_VERSION = 1;
    int getApiVersion();
    CombatStats getCombatStats(Player player);
    CombatState getCombatState(Player player);
    int getAvailableSkillPoints(Player player);
    int getSpentSkillPoints(Player player);
    int getTotalSkillPointBudget(Player player);
    CustomItemInfo getCustomItem(ItemStack item);
    RequirementResult checkRequirement(Player player, CombatRequirement requirement);
}
```

Consumers obtain it through Bukkit's service manager:

```java
ClassSkillsCombatApi api = Bukkit.getServicesManager()
    .load(ClassSkillsCombatApi.class);
if (api == null) {
    return; // ClassSkills is absent or has not enabled yet.
}
```

`getApiVersion()` must be checked before using future additions. API version 1
will remain source and binary compatible as new methods, types, and events are
added in later versions.

## Hot combat queries

The following methods are expected to be safe on a hit/projectile hot path:

```java
CombatStats getCombatStats(Player player);
CombatState getCombatState(Player player);
CustomItemInfo getCustomItem(ItemStack item);
```

### `CombatStats`

`CombatStats` represents the player's **final currently-applicable combat
attributes**, after ClassSkills skills and temporary effects have been applied.
The initial version should contain:

```java
public record CombatStats(
    boolean available,
    double attackDamage,
    double armor,
    double maxHealth,
    double movementSpeed,
    double armorToughness
) {
    public static final CombatStats UNAVAILABLE =
        new CombatStats(false, 0.0, 0.0, 0.0, 0.0, 0.0);
}
```

`available == false` is the clear answer for a player whose combat state has
not been initialized yet. It is never an exception and never `null`.

The values should match Bukkit's live attributes when applicable. For example,
`attackDamage` is the current `Attribute.ATTACK_DAMAGE` value, including the
ClassSkills tree and active buffs. It does not attempt to claim ownership of
modifiers applied by unrelated plugins.

## Skill-point queries

The combat API should also expose the active profile's point state explicitly:

```java
int getAvailableSkillPoints(Player player);
int getSpentSkillPoints(Player player);
int getTotalSkillPointBudget(Player player);
```

These return `0` when the player has no initialized ClassSkills profile. The
total budget is the level-based budget for the current class profile (201 at
Level 100), while `spent + available` represents its current allocated budget.
They are constant-time queries and safe for ordinary polling, although panel
rendering rather than per-hit use is their intended purpose.

### `CombatState`

`CombatState` is the lightweight class-resource/status snapshot useful for
combat UI, encounters, and logs:

```java
public record CombatState(
    boolean available,
    String classId,                 // "warrior", "archer", "paladin", "mage", or ""
    int signatureRank,
    boolean rageActive,
    int focus,
    int focusRequired,
    boolean tauntReady,
    boolean tauntActive,
    double mana,
    double maxMana
) {
    public static final CombatState UNAVAILABLE =
        new CombatState(false, "", 0, false, 0, 0, false, false, 0.0, 0.0);
}
```

Fields not relevant to the current class return their neutral value. For
example, a Warrior has `mana == 0`, and a Mage has `focus == 0`.

### Hot-path implementation requirement

ClassSkills should maintain immutable `CombatStats` and `CombatState` snapshots
per online player. It refreshes those snapshots whenever it already recalculates
attributes, changes a resource, activates/expires a buff, changes class, or
loads/resets a player.

The three hot methods above are then constant-time map/PersistentDataContainer
lookups. They must not rebuild node lists, scan inventories, traverse a skill
tree, allocate a scoreboard, or touch disk. Bukkit main-thread rules still
apply unless a later API explicitly states otherwise.

## Custom items

ClassSkills currently owns the Mage Staff, Skill Shard, and Soul Shard. The
planned item query returns a value object instead of exposing NBT/PDC internals:

```java
public enum CustomItemType {
    NONE, MAGE_STAFF, SKILL_SHARD, SOUL_SHARD
}

public record CustomItemInfo(CustomItemType type, boolean isClassWeapon) {
    public static final CustomItemInfo NONE = new CustomItemInfo(CustomItemType.NONE, false);
}
```

`getCustomItem(item)` returns `CustomItemInfo.NONE` for `null`, air, vanilla
items, copied lookalikes, and unrecognized data. It never throws.

## Panel and encounter requirements

DungeonForge can ask ClassSkills whether the player meets an additional,
ClassSkills-owned condition before allowing an action or visually enabling a
node:

```java
public record CombatRequirement(
    String requiredClassId,         // empty means any class
    int minimumLevel,               // 0 means no level requirement
    int minimumDifficulty,
    int minimumSignatureRank,
    String requiredCustomItemType   // empty means no item requirement
) {}

public record RequirementResult(boolean allowed, RequirementFailure failure) {}

public enum RequirementFailure {
    NONE,
    PLAYER_UNAVAILABLE,
    CLASS_MISMATCH,
    LEVEL_TOO_LOW,
    DIFFICULTY_LOCKED,
    SIGNATURE_RANK_TOO_LOW,
    REQUIRED_ITEM_MISSING
}
```

`checkRequirement` is not intended for every damage event. It is intended for
panels, interaction prompts, reward claims, and other low-frequency checks.
The structured failure gives DungeonForge enough information to gray out an
option and present its own localized message.

DungeonForge's own node prerequisites and costs remain DungeonForge-owned; this
method only evaluates additional requirements owned by ClassSkills.

## Dungeon mobs

ClassSkills does **not currently own or modify dungeon-mob attributes**. It
therefore should not expose a fake final-mob-stat API in version 1.

If ClassSkills later applies a documented mob modifier, add this as a future
additive API:

```java
MobCombatStats getMobCombatStats(LivingEntity entity);
```

It must report only ClassSkills-owned adjustments and include
`available == false` when ClassSkills has not tracked that entity. DungeonForge
remains the authority for the mob's final dungeon stats.

## Events

Events are notifications, emitted only when a value actually changes. They are
not emitted by every hot-path poll.

| Event | When it fires | Cancellable? | Meaning of cancellation |
|---|---|---:|---|
| `ClassSkillsCombatStatsRecalculatedEvent` | A player's cached `CombatStats` changed. | No | Informational; state is already applied. |
| `ClassSkillsCombatStateChangedEvent` | Class resource/buff state changed, e.g. Focus, Mana, Rage, or Taunt. | No | Informational; state is already applied. |
| `ClassSkillsCustomItemEquippedEvent` | A tracked ClassSkills item becomes equipped/held. | No | Informational; the item change has already occurred. |
| `ClassSkillsCustomItemUnequippedEvent` | A tracked ClassSkills item stops being equipped/held. | No | Informational; the item change has already occurred. |
| `ClassSkillsAbilityPreActivateEvent` *(future, only if needed)* | Before a ClassSkills active ability spends Mana or starts cooldown. | Yes | Cancelling prevents that ability activation; it spends no resource and starts no cooldown. |

Version 1 should keep all shipped events non-cancellable. A cancellable event is
only justified before a ClassSkills action with a clear, safe rollback boundary.
It should never be used to cancel a stat recalculation or resource sync.

## Optional-plugin load order

The two plugins must not require each other at load time.

- Both compile against the other API as `compileOnly` only; neither bundles the
  other plugin nor copies its API classes into its own JAR.
- Both treat the service as optional and tolerate it being absent.
- A consumer loads the service in `onEnable`. If it is absent, listen for
  `ServiceRegisterEvent`, check whether the registered service is
  `ClassSkillsCombatApi`, and attach then.
- On `ServiceUnregisterEvent`, discard the cached API reference.

ClassSkills currently soft-depends on DungeonForge to connect to DungeonForge's
optional service. DungeonForge should **not** add a reciprocal `softdepend` on
ClassSkills, because that creates a circular ordering relationship. Service
discovery handles late enablement safely instead.

Example late-service listener:

```java
@EventHandler
public void onServiceRegister(ServiceRegisterEvent event) {
    if (event.getProvider().getService() == ClassSkillsCombatApi.class) {
        combatApi = Bukkit.getServicesManager().load(ClassSkillsCombatApi.class);
    }
}

@EventHandler
public void onServiceUnregister(ServiceUnregisterEvent event) {
    if (event.getProvider().getService() == ClassSkillsCombatApi.class) {
        combatApi = null;
    }
}
```

## Worked examples

### Display current ClassSkills stats in a DungeonForge panel

```java
CombatStats stats = combatApi.getCombatStats(player);
if (!stats.available()) {
    panel.showText("Class combat data is loading...");
    return;
}

panel.showText("Attack: " + stats.attackDamage());
panel.showText("Armor: " + stats.armor());
panel.showText("Health: " + stats.maxHealth() / 2.0 + " hearts");
```

### Gray out a ClassSkills-gated encounter option

```java
CombatRequirement requirement = new CombatRequirement(
    "paladin", 30, 4, 2, ""
);
RequirementResult result = combatApi.checkRequirement(player, requirement);

button.setEnabled(result.allowed());
if (!result.allowed()) {
    button.setLore(List.of("Requires: " + result.failure()));
}
```

### Recognize the Mage Staff without knowing its item data

```java
CustomItemInfo item = combatApi.getCustomItem(player.getInventory().getItemInMainHand());
if (item.type() == CustomItemType.MAGE_STAFF) {
    // The held item is a real ClassSkills staff, not merely a blaze rod.
}
```

### React to a buff/state change

```java
@EventHandler
public void onCombatState(ClassSkillsCombatStateChangedEvent event) {
    CombatState state = event.getCurrent();
    if (state.rageActive()) {
        // Update encounter UI, play a boss reaction, or record analytics.
    }
}
```

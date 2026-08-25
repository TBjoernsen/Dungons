# ClassSkills API

ClassSkills exposes a read-only Bukkit service so other plugins can inspect a
player's active class profile, skill points, unlocked difficulties, purchased
nodes, signature rank, and skill-tree stat bonuses. ClassSkills owns the class,
level, and point budget; DungeonForge owns purchased nodes.

## Compatibility

- Plugin: ClassSkills `0.14.21` or newer
- Public package: `dev.thorb.classskills.api`
- Service: `ClassSkillsApi`
- API is read-only. Consumers cannot edit classes, levels, points, nodes, or
  player attributes through this service.

## Dependency setup

Add ClassSkills as a `compileOnly` dependency. Do **not** shade or bundle the
ClassSkills JAR into your own plugin.

Your plugin can run without ClassSkills installed. Retrieve the service through
Bukkit's `ServicesManager` and handle a missing result.

```java
import dev.thorb.classskills.api.ClassSkillsApi;
import org.bukkit.Bukkit;

ClassSkillsApi classSkills = Bukkit.getServicesManager()
    .load(ClassSkillsApi.class);

if (classSkills == null) {
    // ClassSkills is not installed or has not enabled yet.
    return;
}
```

## Available methods

```java
ClassSkillsPlayerProfile getPlayerProfile(Player player);

int getAvailableSkillPoints(Player player);
int getSpentSkillPoints(Player player);
int getTotalSkillPointBudget(Player player);

boolean hasUnlockedDifficulty(Player player, int difficulty);
boolean hasPurchasedNode(Player player, String nodeId);
```

### Player profile snapshot

`getPlayerProfile(player)` returns an immutable `ClassSkillsPlayerProfile`:

```java
String classId();                 // warrior, archer, paladin, mage, or null
int level();
int maxLevel();                   // currently 100
int experience();
int experienceToNextLevel();      // 0 at maximum level
int availableSkillPoints();
int unlockedDifficulty();         // 1 through 9
int signatureRank();              // 0 when unavailable; otherwise 1 through 6
double attackBonus();
double maxHealthBonus();
double armorBonus();
Set<String> purchasedNodeIds();
```

The snapshot does not update itself after it has been returned. Query again
when current data is needed.

### Skill points

- `getAvailableSkillPoints(player)` returns unspent points for the active class.
- `getSpentSkillPoints(player)` returns points committed to purchased nodes in
  the active class.
- `getTotalSkillPointBudget(player)` returns the level-derived point budget for
  the active class. At Level 100 this is 201.

When DungeonForge is present, available points use DungeonForge's live skill
point balance. Purchased-node reads, spent points, passive rank, and stat
bonuses are queried from DungeonForge API v5's active-class node state. If the
v5 API is unavailable, those node-derived values are empty or zero rather than
falling back to a ClassSkills-owned copy.

### Difficulty and nodes

`hasUnlockedDifficulty(player, difficulty)` returns `false` for difficulties
outside `1..9`.

`hasPurchasedNode(player, nodeId)` checks DungeonForge's active class only.
Node IDs use the following format:

```text
warrior_weapon_01
archer_ability_05
paladin_survival_14
mage_weapon_11
warrior_ability_00    // free Passive Rank I root (DungeonForge a0)
```

## Worked examples

### Check whether a player may enter Difficulty 5

```java
if (!classSkills.hasUnlockedDifficulty(player, 5)) {
    player.sendMessage("You need to unlock Difficulty 5 first.");
    return;
}
```

### Read point state for a panel

```java
int available = classSkills.getAvailableSkillPoints(player);
int spent = classSkills.getSpentSkillPoints(player);
int budget = classSkills.getTotalSkillPointBudget(player);

panel.setText("Points: " + available + " available / " + budget + " total");
panel.setText("Spent: " + spent);
```

### Check a specific ClassSkills node

```java
if (classSkills.hasPurchasedNode(player, "warrior_ability_03")) {
    // Player has Warrior Rage IV's corresponding node.
}
```

### Use the profile for display or logging

```java
ClassSkillsPlayerProfile profile = classSkills.getPlayerProfile(player);

String className = profile.classId() == null ? "Unchosen" : profile.classId();
logger.info(player.getName() + " is a " + className
    + " at level " + profile.level()
    + " with " + profile.availableSkillPoints() + " unspent points.");
```

## Late service registration

Do not create a hard dependency or a reciprocal `softdepend` only to access
this API. If ClassSkills may enable after your plugin, listen for
`ServiceRegisterEvent` and load the service then.

```java
@EventHandler
public void onServiceRegister(ServiceRegisterEvent event) {
    if (event.getProvider().getService() == ClassSkillsApi.class) {
        classSkills = Bukkit.getServicesManager().load(ClassSkillsApi.class);
    }
}

@EventHandler
public void onServiceUnregister(ServiceUnregisterEvent event) {
    if (event.getProvider().getService() == ClassSkillsApi.class) {
        classSkills = null;
    }
}
```

Always check whether the cached `classSkills` reference is non-null before
using it.

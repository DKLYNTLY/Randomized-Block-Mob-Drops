# 🎲 Randomized Block & Mob Drops (RBMD)

Every block and mob drop in Minecraft is randomized — vanilla and modded. Just install and play.

***

## 📖 What It Does

*   Every block and mob has its drops shuffled to something completely different
*   Drops are consistent — the same block always drops the same thing in your world
*   Each world gets its own randomization, saved so it never changes between sessions
*   Works with modded blocks, mobs, and items automatically
*   Only items that can naturally drop are in the shuffle pool — nothing becomes unobtainable
*   If you add, remove, or update a mod, the datapack rebuilds automatically on next world load

***

## ⚙️ How It Works

Load a world. RBMD automatically:

1.  Scans all loot tables to find every naturally obtainable item
2.  Shuffles them using your world seed
3.  Writes a datapack and reloads — drops are active immediately

If you change your config or mod list, RBMD detects the change and rebuilds the datapack using the same seed on the next world load. No manual steps needed.

> 🔁 Want completely new drops? Run `/rbmdreshuffle` at any time.

***

## 📜 Commands

| Command               |Description                       |
| --------------------- |--------------------------------- |
| <code>/rbmdhelp</code> |Show help in-game                 |
| <code>/rbmdreshuffle</code> |Re-randomize with a new seed      |
| <code>/rbmdreshuffle &lt;seed&gt;</code> |Re-randomize with a specific seed |
| <code>/rbmdfind &lt;item&gt;</code> |What block or mob drops this item |
| <code>/rbmdmob &lt;mob&gt;</code> |What this mob drops now           |

Both `/rbmdfind` and `/rbmdmob` support **tab completion** — start typing `minecraft:` or `mymod:` and it will suggest matching items and mobs.

**Debug commands (OP only)**

| Command    |Description                                                         |
| ---------- |------------------------------------------------------------------- |
| <code>/rbmddebug</code> |Force rebuild the datapack if drops seem wrong                      |
| <code>/rbmdcheck</code> |Verify blacklists are working — shows pool size and flags issues    |
| <code>/rbmddump</code> |Export every drop change to <code>datapacks/rbmd_randomizer/rbmd_dump.txt</code> |

> 💡 Use `/rbmddump` to review all changes and copy IDs straight into the blacklist config.

***

## ⚙️ Config

File: `config/rbmd-common.toml`

Changes are detected automatically on next world load — no need to run `/rbmdreshuffle` unless you want new drops entirely.

| Option                |Default     |Description                                         |
| --------------------- |----------- |--------------------------------------------------- |
| <code>randomize_blocks</code> |<code>true</code> |Randomize block drops                               |
| <code>randomize_mobs</code> |<code>true</code> |Randomize mob drops                                 |
| <code>randomize_silk_touch</code> |<code>true</code> |Randomize Silk Touch drops                          |
| <code>item_blacklist</code> |<em>(see below)</em> |Items excluded from the pool entirely               |
| <code>mob_blacklist</code> |<em>(empty)</em> |Mobs whose drops are never randomized               |
| <code>mod_blacklist</code> |<em>(empty)</em> |Exclude all items and mobs from a mod by its mod ID |
| <code>chaos_mode</code> |<code>false</code> |Skip all filters — see below                        |
| <code>reset_welcome_message</code> |<code>false</code> |Show the welcome message again on next join         |

**Item blacklist defaults** — excluded because they can't be obtained naturally: `bedrock`, `barrier`, `command_block`, `chain_command_block`, `repeating_command_block`, `structure_block`, `structure_void`, `jigsaw`, `light`, `debug_stick`, `knowledge_book`, `bundle`. Remove any entry to put it back in the pool.

**Blacklist format** — use full registry IDs:

```
item_blacklist = ["minecraft:diamond", "mymod:special_item"]
mob_blacklist  = ["minecraft:zombie", "mymod:boss"]
mod_blacklist  = ["mymod", "anothermod"]
```

### 🔴 Chaos Mode

Setting `chaos_mode = true` skips everything — blacklists, spawn egg filter, and the natural drop filter. Every registered item enters the pool. A warning appears in chat when you join with chaos mode on.

> ⚠️ Some items may become unobtainable in chaos mode. Use at your own risk.

***

## 🔧 Notes

*   **Wrong tool = no drop** — same as vanilla. A block that needs a pickaxe drops nothing without one.
*   **Same seed = same drops** — two worlds sharing a seed will have identical randomization. Use `/rbmdreshuffle` to override.
*   **Each world is independent** — worlds store their own seed and config hash in `datapacks/rbmd_randomizer/`. Switching between worlds never affects each other.
*   **Adding or removing mods** — detected automatically. Drops will shift slightly since the pool size changes, but the same seed is reused so existing items stay as consistent as possible.
*   **Silk Touch** — randomized by default. Set `randomize_silk_touch = false` if you want Silk Touch to always give the original block.
*   **Mods with hardcoded drops** — mobs that skip the loot table system are still covered by an event hook on every mob death.
*   **Debugging** — all output tagged `[RBMD]` in `latest.log`. Use `/rbmddump` to export a full human-readable list of every drop change.

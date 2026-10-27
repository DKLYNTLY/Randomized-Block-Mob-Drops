<h1 align="center">Randomized Block & Mob Drops</h1>

Randomized Block & Mob Drops shuffles block and mob loot, including compatible modded content. This branch targets **Minecraft 1.20.1 with Forge**.

---

<p align="center"><a href="https://www.curseforge.com/minecraft/mc-mods/rbmd">Download on CurseForge</a> · <a href="https://discord.com/invite/mUGHmQxHG8">Discord</a> · <a href="https://github.com/DKLYNTLY/Randomized-Block-Mob-Drops">GitHub source</a></p>

## What you can do

* Every block and mob has its drops shuffled to something completely different
* Drops are consistent — the same block always drops the same thing in your world
* Each world gets its own randomization, saved so it never changes between sessions
* Works with modded blocks, mobs, and items automatically
* Only items that can naturally drop are in the shuffle pool — nothing becomes unobtainable
* If you add, remove, or update a mod, the datapack rebuilds automatically on next world load

<details>
<summary><strong>Play: controls, commands, and progression</strong></summary>

### How It Works

Load a world. RBMD automatically:

1. Scans all loot tables to find every naturally obtainable item
2. Shuffles them using your world seed
3. Writes a datapack and reloads — drops are active immediately

If you change your config or mod list, RBMD detects the change and rebuilds the datapack using the same seed on the next world load. No manual steps needed.

> 🔁 Want completely new drops? Run `/rbmdreshuffle` at any time.

---

### Commands

| Command | Description |
| --- | --- |
| `/rbmdhelp` | Show help in-game |
| `/rbmdreshuffle` | Re-randomize with a new seed |
| `/rbmdreshuffle <seed>` | Re-randomize with a specific seed |
| `/rbmdfind <item>` | What block or mob drops this item |
| `/rbmdmob <mob>` | What this mob drops now |

Both `/rbmdfind` and `/rbmdmob` support **tab completion** — start typing `minecraft:` or `mymod:` and it will suggest matching items and mobs.

**Debug commands (OP only)**

| Command | Description |
| --- | --- |
| `/rbmddebug` | Force rebuild the datapack if drops seem wrong |
| `/rbmdcheck` | Verify blacklists are working — shows pool size and flags issues |
| `/rbmddump` | Export every drop change to `datapacks/rbmd_randomizer/rbmd_dump.txt` |

> 💡 Use `/rbmddump` to review all changes and copy IDs straight into the blacklist config.

---

### Notes

* **Wrong tool = no drop** — same as vanilla. A block that needs a pickaxe drops nothing without one.
* **Same seed = same drops** — two worlds sharing a seed will have identical randomization. Use `/rbmdreshuffle` to override.
* **Each world is independent** — worlds store their own seed and config hash in `datapacks/rbmd_randomizer/`. Switching between worlds never affects each other.
* **Adding or removing mods** — detected automatically. Drops will shift slightly since the pool size changes, but the same seed is reused so existing items stay as consistent as possible.
* **Silk Touch** — randomized by default. Set `randomize_silk_touch = false` if you want Silk Touch to always give the original block.
* **Mods with hardcoded drops** — mobs that skip the loot table system are still covered by an event hook on every mob death.
* **Debugging** — all output tagged `[RBMD]` in `latest.log`. Use `/rbmddump` to export a full human-readable list of every drop change.

</details>

<details>
<summary><strong>Configure and customize</strong></summary>

### Config

File: `config/rbmd-common.toml`

Changes are detected automatically on next world load — no need to run `/rbmdreshuffle` unless you want new drops entirely.

| Option | Default | Description |
| --- | --- | --- |
| `randomize_blocks` | `true` | Randomize block drops |
| `randomize_mobs` | `true` | Randomize mob drops |
| `randomize_silk_touch` | `true` | Randomize Silk Touch drops |
| `item_blacklist` | *(see below)* | Items excluded from the pool entirely |
| `mob_blacklist` | *(empty)* | Mobs whose drops are never randomized |
| `mod_blacklist` | *(empty)* | Exclude all items and mobs from a mod by its mod ID |
| `chaos_mode` | `false` | Skip all filters — see below |
| `reset_welcome_message` | `false` | Show the welcome message again on next join |

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

</details>

<details>
<summary><strong>Backups and recovery</strong></summary>

### Before updating

*Back up your world and configuration files before updating or changing settings. Keep a working copy of the old instance until you have checked the new setup.*

</details>

<details>
<summary><strong>About this project</strong></summary>

### Author and links

I create and maintain Randomized Block & Mob Drops as **Dnydd (formerly known as dklyntly)**.

[Official CurseForge page](https://www.curseforge.com/minecraft/mc-mods/rbmd) · [Discord](https://discord.com/invite/mUGHmQxHG8)

### License

Read [LICENSE](LICENSE) for this branch’s terms. Permissions granted with earlier releases remain in effect.

</details>

## Support my work

If you enjoy my projects, you can support my work through Ko-fi or PayPal. Support is always optional.

[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/A3G129JCLF) [![Support via PayPal](https://img.shields.io/badge/Support%20via-PayPal-0070BA?style=for-the-badge&logo=paypal&logoColor=white)](https://www.paypal.com/ncp/payment/7ZKG8YG6GCERC)

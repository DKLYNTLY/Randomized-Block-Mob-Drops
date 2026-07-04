package com.dklyntly.rbmd;

import com.google.gson.*;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Mod(RBMDMod.MODID)
public class RBMDMod {

    public static final String MODID = "rbmd";
    // Proper logger — visible in both latest.log and debug.log
    static final Logger LOGGER = LoggerFactory.getLogger("RBMD");

    public RBMDMod(IEventBus ignored) {
        NeoForge.EVENT_BUS.register(this);
        Config.register();
    }

    // ── SERVER START: generate datapack, reload, or reuse existing ──────────────

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        Path dpRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("rbmd_randomizer");
        Path seedFile = dpRoot.resolve("rbmd_seed.txt");

        LOGGER.info("Config — chaos_mode={} randomize_blocks={} randomize_mobs={} randomize_silk_touch={} item_blacklist_size={} mob_blacklist_size={} mod_blacklist_size={}",
                Config.chaosMode(), Config.randomizeBlocks(), Config.randomizeMobs(), Config.randomizeSilkTouch(),
                Config.itemBlacklist().size(), Config.mobBlacklist().size(), Config.modBlacklist().size());
        if (Config.chaosMode()) LOGGER.warn("CHAOS MODE is enabled — all blacklists and filters are skipped!");

        Path configHashFile = dpRoot.resolve("rbmd_config.txt");

        if (Files.exists(seedFile)) {
            try {
                long storedSeed = Long.parseLong(Files.readString(seedFile).trim());

                // Check if blacklist config changed since datapack was generated
                String currentConfigHash = buildConfigHash();
                String storedConfigHash = Files.exists(configHashFile)
                        ? Files.readString(configHashFile).trim() : "";

                if (!currentConfigHash.equals(storedConfigHash)) {
                    LOGGER.info("Config or mod list changed since last generation — rebuilding datapack.");
                    Permutation.clearAndSetSeed(storedSeed);
                    Permutation.ensureBuilt(server);
                    if (Config.randomizeBlocks() || Config.randomizeMobs()) {
                        int written = makeDatapack(server, server.getResourceManager());
                        if (written > 0) {
                            Files.writeString(seedFile, String.valueOf(storedSeed));
                            Files.writeString(configHashFile, currentConfigHash);
                            LOGGER.info("Rebuilt {} loot tables with updated config.", written);
                            server.execute(() -> triggerReload(server, "auto-start (config changed)"));
                        }
                    }
                    return;
                }

                LOGGER.info("Found existing datapack for this world (seed: {}) — reusing.", storedSeed);
                Permutation.clearAndSetSeed(storedSeed);
                Permutation.ensureBuilt(server);
                populateCoveredTablesFromDisk(dpRoot);
                if (Config.randomizeBlocks() || Config.randomizeMobs()) {
                    server.execute(() -> triggerReload(server, "auto-start (existing)"));
                } else {
                    LOGGER.info("Both randomize_blocks and randomize_mobs are false — skipping reload.");
                }
                return;
            } catch (Exception e) {
                LOGGER.warn("Could not read rbmd_seed.txt, regenerating. Reason: {}", e.getMessage());
            }
        }

        Permutation.clear();
        Permutation.ensureBuiltForSeed(server);

        if (Config.randomizeBlocks() || Config.randomizeMobs()) {
            LOGGER.info("Auto-generating randomized loot table datapack...");
            int written = makeDatapack(server, server.getResourceManager());

            if (written > 0) {
                try {
                    Files.writeString(seedFile, String.valueOf(Permutation.getLastBuiltSeed()));
                    Files.writeString(configHashFile, buildConfigHash());
                    LOGGER.info("Saved seed {} to disk.", Permutation.getLastBuiltSeed());
                } catch (Exception e) {
                    LOGGER.warn("Could not save rbmd_seed.txt: {}", e.getMessage());
                }
                LOGGER.info("Auto-generated {} loot tables. Scheduling reload...", written);
                server.execute(() -> triggerReload(server, "auto-start"));
            } else {
                LOGGER.error("0 loot tables were written! Check the log lines above for details.");
            }
        } else {
            LOGGER.info("Both randomize_blocks and randomize_mobs are false — skipping datapack generation.");
        }
    }

    // ── MOB DROPS: catch hardcoded drops that bypass loot tables ────────────────

    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        if (!Config.randomizeMobs()) return;

        MinecraftServer server = event.getEntity().level().getServer();
        if (server == null) return; // client side — skip

        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType());
        if (Config.mobBlacklist().contains(entityId)) return;
        if (entityId != null && Config.modBlacklist().contains(entityId.getNamespace())) return;

        ResourceLocation lootTableId = event.getEntity().getLootTable().location();
        ResourceLocation withJson = ResourceLocation.fromNamespaceAndPath(
                lootTableId.getNamespace(), lootTableId.getPath() + ".json");
        if (COVERED_TABLES.contains(withJson)) return;
        ResourceLocation withPrefix = ResourceLocation.fromNamespaceAndPath(
                lootTableId.getNamespace(), "loot_table/" + lootTableId.getPath() + ".json");
        if (COVERED_TABLES.contains(withPrefix)) return;

        for (ItemEntity itemEntity : event.getDrops()) {
            ItemStack stack = itemEntity.getItem();
            Item original = stack.getItem();
            Item remapped = Permutation.map(original, server);
            if (remapped != original) {
                itemEntity.setItem(new ItemStack(remapped, stack.getCount()));
            }
        }
    }

    // ── WELCOME MESSAGE: shown once per player per world ────────────────────────

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MinecraftServer server = player.getServer();
        if (server == null) return;

        Path welcomeFile = server.getWorldPath(LevelResource.DATAPACK_DIR)
                .resolve("rbmd_randomizer")
                .resolve("rbmd_welcomed_" + player.getUUID() + ".txt");

        boolean shouldShow = !Files.exists(welcomeFile) || Config.resetWelcome();

        if (!shouldShow) return;

        // Reset the config flag back to false if it was set
        if (Config.resetWelcome()) Config.clearResetWelcome();

        // Write the marker so it doesn't show again
        try {
            Files.createDirectories(welcomeFile.getParent());
            Files.writeString(welcomeFile, "welcomed", StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Could not write welcome marker: {}", e.getMessage());
        }

        server.execute(() -> {
            player.sendSystemMessage(Component.literal("§8§m                                        "));
            player.sendSystemMessage(Component.literal(" §6§lRBMD §8— §7Randomized Block & Mob Drops"));
            player.sendSystemMessage(Component.literal("§8§m                                        "));
            player.sendSystemMessage(Component.literal(" §7All block and mob drops have been randomized!"));
            if (Config.chaosMode())
                player.sendSystemMessage(Component.literal(" §c§l⚠ CHAOS MODE is ON — all filters skipped!"));
            player.sendSystemMessage(Component.literal(" §7Run §f/rbmdhelp §7for commands and info."));
            player.sendSystemMessage(Component.literal("§8§m                                        "));
        });
    }

    // ── COMMANDS ────────────────────────────────────────────────────────────────

    // Tab-complete all registered item IDs (e.g. minecraft:diamond, mymod:silver_ingot)
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_ITEMS =
            (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase();
                for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                    String full = id.toString();
                    if (full.contains(remaining)) builder.suggest(full);
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_MOBS =
            (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase();
                for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
                    String full = id.toString();
                    if (full.contains(remaining)) builder.suggest(full);
                }
                return builder.buildFuture();
            };

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        registerHelp(d);
        registerMakePack(d);
        registerReshuffle(d);
        registerFind(d);
        registerMob(d);
        registerBlacklistCheck(d);
        registerDump(d);
    }

    // /rbmdhelp
    private static void registerHelp(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rbmdhelp")
                .requires(src -> true)
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    send(src, "§8§m                                    ");
                    send(src, " §6§lRBMD §8— §7Randomized Block & Mob Drops");
                    send(src, "§8§m                                    ");
                    send(src, " §f/rbmdreshuffle         §8» §7Re-randomize this world");
                    send(src, " §f/rbmdreshuffle §3<seed>  §8» §7Use a specific seed");
                    send(src, " §f/rbmdfind §3<item>       §8» §7What mines or drops this item");
                    send(src, " §f/rbmdmob §3<mob>         §8» §7What a mob drops now");
                    send(src, "§8§m                                    ");
                    if (Config.chaosMode()) {
                        send(src, " §c§l⚠ CHAOS MODE ON §c— all filters & blacklists skipped");
                        send(src, " §7Every item is in the pool. Unobtainables may appear.");
                        send(src, "§8§m                                    ");
                    }
                    send(src, " §8§oDebug §8— §8§orequires OP");
                    send(src, " §7/rbmddebug  §8» §8Force rebuild if drops seem broken");
                    send(src, " §7/rbmdcheck  §8» §8Verify blacklists are applied correctly");
                    send(src, " §7/rbmddump   §8» §8Export all drop changes to a file");
                    send(src, "§8§m                                    ");
                    send(src, " §7Config: §fconfig/rbmd-common.toml");
                    send(src, " §8chaos_mode, blacklists, toggles §8— see the file for details");
                    send(src, "§8§m                                    ");
                    return Command.SINGLE_SUCCESS;
                })
        );
    }

    // /rbmddebug
    private static void registerMakePack(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rbmddebug")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    CommandSourceStack src = ctx.getSource();

                    Permutation.ensureBuilt(server);
                    send(src, "§6RBMD: §7Building datapack...");
                    LOGGER.info("Manual /rbmddebug triggered.");

                    int written = makeDatapack(server, server.getResourceManager());

                    if (written == 0) {
                        send(src, "§cRBMD: No loot tables written — search 'RBMD' in server logs.");
                        return Command.SINGLE_SUCCESS;
                    }

                    send(src, "§aRBMD: Done! §f" + written + " §7loot tables written. Reloading...");
                    server.execute(() -> triggerReload(server, "/rbmddebug"));
                    return Command.SINGLE_SUCCESS;
                })
        );
    }

    // /rbmdreshuffle [seed]
    private static void registerReshuffle(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rbmdreshuffle")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    long seed = new Random().nextLong();
                    return doReshuffle(ctx.getSource(), seed, false);
                })
                .then(Commands.argument("seed", LongArgumentType.longArg())
                        .executes(ctx -> {
                            long seed = LongArgumentType.getLong(ctx, "seed");
                            return doReshuffle(ctx.getSource(), seed, true);
                        })
                )
        );
    }

    private static int doReshuffle(CommandSourceStack src, long seed, boolean userProvided) {
        MinecraftServer server = src.getServer();
        Permutation.clearAndSetSeed(seed);
        Permutation.ensureBuilt(server);

        send(src, "§6RBMD: §7Re-shuffling drops...");
        LOGGER.info("Reshuffle triggered. Seed: {}", seed);

        int written = makeDatapack(server, server.getResourceManager());

        if (written == 0) {
            send(src, "§cRBMD: No loot tables written — search 'RBMD' in server logs.");
            return Command.SINGLE_SUCCESS;
        }

        // Persist the new seed so this world remembers it next session
        Path seedFile = server.getWorldPath(LevelResource.DATAPACK_DIR)
                .resolve("rbmd_randomizer").resolve("rbmd_seed.txt");
        try {
            Files.writeString(seedFile, String.valueOf(Permutation.getLastBuiltSeed()));
            Files.writeString(
                    server.getWorldPath(LevelResource.DATAPACK_DIR)
                            .resolve("rbmd_randomizer").resolve("rbmd_config.txt"),
                    buildConfigHash());
        } catch (Exception e) {
            LOGGER.warn("Could not save rbmd_seed.txt: {}", e.getMessage());
        }

        if (userProvided) {
            send(src, "§aRBMD: Done! §f" + written + " §7tables written. Seed: §f" + seed);
        } else {
            send(src, "§aRBMD: Done! §f" + written + " §7tables written with a new random seed.");
        }
        send(src, "§7Reloading...");
        server.execute(() -> triggerReload(server, "/rbmdreshuffle"));
        return Command.SINGLE_SUCCESS;
    }

    // /rbmdfind <item> — reverse lookup: what blocks/mobs drop this item?
    private static void registerFind(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rbmdfind")
                .requires(src -> true)
                .then(Commands.argument("item", ResourceLocationArgument.id())
                        .suggests(SUGGEST_ITEMS)
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            MinecraftServer server = src.getServer();

                            ResourceLocation itemId = ResourceLocationArgument.getId(ctx, "item");
                            Item targetItem = BuiltInRegistries.ITEM.get(itemId);

                            if (targetItem == Items.AIR) {
                                send(src, "§cUnknown item: §f" + itemId);
                                send(src, "§7Use the registry ID, e.g. §fdiamond §7or §fmymod:gem");
                                return Command.SINGLE_SUCCESS;
                            }

                            List<String> blockSources = new ArrayList<>();
                            List<String> mobSources = new ArrayList<>();

                            Set<Item> sourceItems = new HashSet<>();
                            for (Item candidate : BuiltInRegistries.ITEM) {
                                if (Permutation.map(candidate, server) == targetItem) {
                                    sourceItems.add(candidate);
                                }
                            }

                            if (sourceItems.isEmpty()) {
                                send(src, "§eNothing currently drops §f" + BuiltInRegistries.ITEM.getKey(targetItem) + "§e.");
                                return Command.SINGLE_SUCCESS;
                            }

                            Map<String, List<String>> itemToBlocks = new HashMap<>();
                            Map<String, List<String>> itemToMobs = new HashMap<>();

                            for (ResourceLocation tableId : COVERED_TABLES) {
                                String path = tableId.getPath();
                                boolean isBlockTable = path.contains("/blocks/");
                                boolean isEntityTable = path.contains("/entities/") || path.contains("/entity/");
                                if (!isBlockTable && !isEntityTable) continue;

                                Path tableFile = server.getWorldPath(LevelResource.DATAPACK_DIR)
                                        .resolve("rbmd_randomizer").resolve("data")
                                        .resolve(tableId.getNamespace()).resolve(tableId.getPath());
                                if (!java.nio.file.Files.exists(tableFile)) continue;

                                String tableName = tableId.getNamespace() + ":"
                                        + path.replaceAll(".*(blocks|entities|entity)/", "")
                                        .replace(".json", "");

                                try {
                                    String json = java.nio.file.Files.readString(tableFile,
                                            java.nio.charset.StandardCharsets.UTF_8);
                                    com.google.gson.JsonObject tbl = GSON.fromJson(json,
                                            com.google.gson.JsonObject.class);
                                    List<String> names = new ArrayList<>();
                                    if (tbl.has("pools"))
                                        for (com.google.gson.JsonElement pe : tbl.getAsJsonArray("pools")) {
                                            if (!pe.isJsonObject()) continue;
                                            com.google.gson.JsonObject pool = pe.getAsJsonObject();
                                            if (pool.has("entries"))
                                                for (com.google.gson.JsonElement ee : pool.getAsJsonArray("entries"))
                                                    if (ee.isJsonObject()) collectDropNames(ee.getAsJsonObject(), names);
                                        }
                                    for (String droppedItem : names) {
                                        if (isBlockTable) itemToBlocks.computeIfAbsent(droppedItem, k -> new ArrayList<>()).add(tableName);
                                        else itemToMobs.computeIfAbsent(droppedItem, k -> new ArrayList<>()).add(tableName);
                                    }
                                } catch (Exception ignored) {}
                            }

                            for (Item src2 : sourceItems) {
                                ResourceLocation srcId = BuiltInRegistries.ITEM.getKey(src2);
                                String idStr = srcId.toString();
                                List<String> blocks = itemToBlocks.get(idStr);
                                List<String> mobs = itemToMobs.get(idStr);
                                if (blocks != null) blockSources.addAll(blocks);
                                if (mobs != null) mobSources.addAll(mobs);
                                if (blocks == null && mobs == null) {
                                    if (BuiltInRegistries.BLOCK.containsKey(srcId)) blockSources.add(idStr);
                                    else mobSources.add(idStr);
                                }
                            }

                            String targetName = BuiltInRegistries.ITEM.getKey(targetItem).toString();
                            send(src, "§8§m                              ");
                            send(src, " §6Drops for: §f" + targetName);
                            send(src, "§8§m                              ");

                            if (!blockSources.isEmpty()) {
                                Collections.sort(blockSources);
                                send(src, " §e⛏ Mine");
                                for (String s : blockSources) send(src, "  §7" + s);
                            }

                            if (!mobSources.isEmpty()) {
                                Collections.sort(mobSources);
                                send(src, " §e☠ Kill");
                                for (String s : mobSources) send(src, "  §7" + s);
                            }

                            if (blockSources.isEmpty() && !Config.randomizeBlocks())
                                send(src, " §8Mine results hidden — randomize_blocks is disabled in config.");
                            if (mobSources.isEmpty() && !Config.randomizeMobs())
                                send(src, " §8Kill results hidden — randomize_mobs is disabled in config.");
                            if (blockSources.isEmpty() && mobSources.isEmpty()
                                    && Config.randomizeBlocks() && Config.randomizeMobs()) {
                                send(src, " §7Nothing found — may be from a special loot table.");
                            }

                            send(src, "§8§m                              ");
                            return Command.SINGLE_SUCCESS;
                        })
                )
        );
    }

    // /rbmdmob <mob> — show what a mob drops (randomized)
    private static void registerMob(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rbmdmob")
                .requires(src -> true)
                .then(Commands.argument("mob", ResourceLocationArgument.id())
                        .suggests(SUGGEST_MOBS)
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            MinecraftServer server = src.getServer();

                            ResourceLocation mobId = ResourceLocationArgument.getId(ctx, "mob");

                            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(mobId)) {
                                send(src, "§cUnknown mob: §f" + mobId);
                                send(src, "§7Use the registry ID, e.g. §fzombie §7or §fmymod:boss");
                                return Command.SINGLE_SUCCESS;
                            }

                            // Find the loot table for this mob by looking through COVERED_TABLES
                            // Mob loot tables live at loot_table/<namespace>/entities/<name>.json
                            String mobPath = mobId.getPath();
                            String mobNamespace = mobId.getNamespace();
                            List<ResourceLocation> mobTables = new ArrayList<>();
                            for (ResourceLocation tableId : COVERED_TABLES) {
                                String path = tableId.getPath();
                                if ((path.contains("/entities/") || path.contains("/entity/"))
                                        && path.endsWith("/" + mobPath + ".json")
                                        && tableId.getNamespace().equals(mobNamespace)) {
                                    mobTables.add(tableId);
                                }
                            }

                            send(src, "§8§m                              ");
                            send(src, " §6§l" + mobId + " §8— §7Randomized drops");
                            send(src, "§8§m                              ");

                            if (mobTables.isEmpty()) {
                                if (!Config.randomizeMobs()) {
                                    send(src, " §7Mob randomization is disabled in config (randomize_mobs=false).");
                                } else {
                                    send(src, " §7This mob drops nothing or its drops cannot be randomized.");
                                    send(src, " §7Try §f/rbmdfind §7to search for a specific item instead.");
                                }
                                send(src, "§8§m                              ");
                                return Command.SINGLE_SUCCESS;
                            }

                            List<String> drops = new ArrayList<>();
                            for (ResourceLocation tableId : mobTables) {
                                Path dpRoot = server.getWorldPath(LevelResource.DATAPACK_DIR)
                                        .resolve("rbmd_randomizer").resolve("data")
                                        .resolve(tableId.getNamespace()).resolve(tableId.getPath());
                                if (!java.nio.file.Files.exists(dpRoot)) continue;
                                try {
                                    String json = java.nio.file.Files.readString(dpRoot, java.nio.charset.StandardCharsets.UTF_8);
                                    com.google.gson.JsonObject table = new com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject.class);
                                    if (table.has("pools")) {
                                        for (com.google.gson.JsonElement poolEl : table.getAsJsonArray("pools")) {
                                            if (!poolEl.isJsonObject()) continue;
                                            com.google.gson.JsonObject pool = poolEl.getAsJsonObject();
                                            if (!pool.has("entries")) continue;
                                            for (com.google.gson.JsonElement entryEl : pool.getAsJsonArray("entries")) {
                                                collectDropNames(entryEl.getAsJsonObject(), drops);
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    LOGGER.warn("Could not read loot table for {}: {}", mobId, e.getMessage());
                                }
                            }

                            if (drops.isEmpty()) {
                                send(src, " §7No drops found for this mob.");
                            } else {
                                Collections.sort(drops);
                                send(src, " §e☠ Drops");
                                for (String s : drops) send(src, "  §f" + s);
                            }
                            send(src, "§8§m                              ");
                            return Command.SINGLE_SUCCESS;
                        })
                )
        );
    }

    /** Recursively collect item names from a loot table entry JSON. */
    // Static version used by Permutation.ensureBuilt before instance context is available
    static void collectDropNamesStatic(com.google.gson.JsonObject entry, List<String> out) {
        if (!entry.isJsonObject()) return;
        String type = entry.has("type") ? entry.get("type").getAsString() : "";
        if (type.equals("minecraft:item") && entry.has("name"))
            out.add(entry.get("name").getAsString());
        for (String key : List.of("entries", "children")) {
            if (entry.has(key))
                for (com.google.gson.JsonElement child : entry.getAsJsonArray(key))
                    if (child.isJsonObject()) collectDropNamesStatic(child.getAsJsonObject(), out);
        }
    }

    private static void collectDropNames(com.google.gson.JsonObject entry, List<String> out) {
        if (!entry.isJsonObject()) return;
        String type = entry.has("type") ? entry.get("type").getAsString() : "";
        if (type.equals("minecraft:item") && entry.has("name")) {
            out.add(entry.get("name").getAsString());
        }
        for (String key : List.of("entries", "children")) {
            if (entry.has(key)) {
                for (com.google.gson.JsonElement child : entry.getAsJsonArray(key)) {
                    if (child.isJsonObject()) collectDropNames(child.getAsJsonObject(), out);
                }
            }
        }
    }

    // Scan the already-written datapack folder and populate COVERED_TABLES from it
    private static void populateCoveredTablesFromDisk(Path dpRoot) {
        COVERED_TABLES.clear();
        Path dataDir = dpRoot.resolve("data");
        if (!Files.exists(dataDir)) return;
        try (var walk = Files.walk(dataDir)) {
            walk.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> p.toString().replace("\\", "/").contains("/loot_table/"))
                    .forEach(p -> {
                        String rel = dataDir.relativize(p).toString().replace("\\", "/");
                        int nsEnd = rel.indexOf("/");
                        if (nsEnd < 0) return;
                        String ns = rel.substring(0, nsEnd);
                        String rest = rel.substring(nsEnd + 1); // loot_table/...
                        COVERED_TABLES.add(ResourceLocation.fromNamespaceAndPath(ns, rest));
                    });
        } catch (Exception e) {
            LOGGER.warn("Could not scan existing datapack for COVERED_TABLES: {}", e.getMessage());
        }
        LOGGER.info("Repopulated COVERED_TABLES with {} entries from disk.", COVERED_TABLES.size());
    }

    // /rbmdcheck — verify blacklisted items/mobs/mods are not present in the pool or loot tables
    private static void registerBlacklistCheck(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rbmdcheck")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    MinecraftServer server = src.getServer();
                    Set<ResourceLocation> itemBL = Config.itemBlacklist();
                    Set<ResourceLocation> mobBL = Config.mobBlacklist();
                    Set<String> modBL = Config.modBlacklist();

                    send(src, "§8§m                                    ");
                    send(src, " §6§lRBMD Blacklist Check");
                    send(src, "§8§m                                    ");

                    int itemOk = 0, itemFail = 0;
                    for (ResourceLocation id : itemBL) {
                        Item item = BuiltInRegistries.ITEM.get(id);
                        if (item == Items.AIR) {
                            send(src, " §e? §7" + id + " §8(unknown item — typo?)");
                            continue;
                        }
                        // Check if this item maps to anything other than itself (would mean it's in the pool)
                        Item mapped = Permutation.map(item, server);
                        if (mapped != item) {
                            send(src, " §c✗ §f" + id + " §cis still in the pool! Mapped to: "
                                    + BuiltInRegistries.ITEM.getKey(mapped));
                            itemFail++;
                        } else {
                            itemOk++;
                        }
                    }
                    if (itemBL.isEmpty()) {
                        send(src, " §7item_blacklist is empty.");
                    } else {
                        send(src, " §e§lItems: §a" + itemOk + " excluded§7, §c" + itemFail + " still in pool");
                    }

                    int mobOk = 0, mobFail = 0;
                    for (ResourceLocation id : mobBL) {
                        if (BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                            mobOk++;
                        } else {
                            send(src, " §c✗ §f" + id + " §c— unknown entity (typo?)");
                            mobFail++;
                        }
                    }
                    if (mobBL.isEmpty()) {
                        send(src, " §7mob_blacklist is empty.");
                    } else {
                        send(src, " §e§lMobs: §a" + mobOk + " valid§7, §c" + mobFail + " unknown");
                    }

                    for (String modId : modBL) {
                        long itemCount = 0, mobCount = 0;
                        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet())
                            if (id.getNamespace().equals(modId)) itemCount++;
                        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet())
                            if (id.getNamespace().equals(modId)) mobCount++;
                        if (itemCount == 0 && mobCount == 0) {
                            send(src, " §c✗ §fmod_blacklist: §c" + modId + " §c— no items or mobs found (typo?)");
                        } else {
                            send(src, " §a✓ §fmod_blacklist: §f" + modId
                                    + " §8(" + itemCount + " items, " + mobCount + " mobs excluded)");
                        }
                    }
                    if (modBL.isEmpty()) send(src, " §7mod_blacklist is empty.");

                    long totalItems = BuiltInRegistries.ITEM.keySet().size();
                    long poolSize = BuiltInRegistries.ITEM.stream()
                            .filter(item -> {
                                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                                return id != null
                                        && !id.getPath().endsWith("_spawn_egg")
                                        && !modBL.contains(id.getNamespace())
                                        && !itemBL.contains(id);
                            }).count();
                    send(src, "§8§m                                    ");
                    send(src, " §7Pool: §f" + poolSize + "§7 / " + totalItems + " items active");
                    send(src, "§8§m                                    ");
                    return Command.SINGLE_SUCCESS;
                })
        );
    }

    // /rbmddump — write full permutation to a human-readable file
    private static void registerDump(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rbmddump")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    MinecraftServer server = src.getServer();

                    send(src, "§6RBMD: §7Writing permutation dump...");

                    Path dumpFile = server.getWorldPath(LevelResource.DATAPACK_DIR)
                            .resolve("rbmd_randomizer")
                            .resolve("rbmd_dump.txt");

                    try {
                        StringBuilder sb = new StringBuilder();
                        sb.append("RBMD Permutation Dump\n");
                        sb.append("Seed: ").append(Permutation.getLastBuiltSeed()).append("\n");
                        sb.append("Chaos mode: ").append(Config.chaosMode()).append("\n");
                        sb.append("=".repeat(60)).append("\n\n");

                        // Collect all block loot table mappings
                        Map<String, String> blockMappings = new LinkedHashMap<>();
                        Map<String, String> mobMappings = new LinkedHashMap<>();
                        Map<String, String> otherMappings = new LinkedHashMap<>();

                        // Walk the datapack to find what each table now drops
                        Path dataDir = server.getWorldPath(LevelResource.DATAPACK_DIR)
                                .resolve("rbmd_randomizer").resolve("data");

                        if (Files.exists(dataDir)) {
                            try (var walk = Files.walk(dataDir)) {
                                walk.filter(p -> p.toString().endsWith(".json"))
                                        .sorted()
                                        .forEach(p -> {
                                            String rel = dataDir.relativize(p).toString().replace("\\", "/");
                                            int nsEnd = rel.indexOf("/");
                                            if (nsEnd < 0) return;
                                            String ns = rel.substring(0, nsEnd);
                                            String rest = rel.substring(nsEnd + 1);
                                            String tableName = ns + ":" + rest
                                                    .replaceAll("loot_table/", "")
                                                    .replace(".json", "");
                                            try {
                                                String json = Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
                                                com.google.gson.JsonObject tbl = GSON.fromJson(json,
                                                        com.google.gson.JsonObject.class);
                                                List<String> drops = new ArrayList<>();
                                                if (tbl.has("pools"))
                                                    for (com.google.gson.JsonElement pe : tbl.getAsJsonArray("pools")) {
                                                        if (!pe.isJsonObject()) continue;
                                                        com.google.gson.JsonObject pool = pe.getAsJsonObject();
                                                        if (pool.has("entries"))
                                                            for (com.google.gson.JsonElement ee : pool.getAsJsonArray("entries"))
                                                                if (ee.isJsonObject()) collectDropNames(ee.getAsJsonObject(), drops);
                                                    }
                                                if (!drops.isEmpty()) {
                                                    String dropStr = String.join(", ", drops);
                                                    if (rest.contains("/blocks/")) blockMappings.put(tableName, dropStr);
                                                    else if (rest.contains("/entities/") || rest.contains("/entity/")) mobMappings.put(tableName, dropStr);
                                                    else otherMappings.put(tableName, dropStr);
                                                }
                                            } catch (Exception ignored) {}
                                        });
                            }
                        }

                        sb.append("BLOCK DROPS (").append(blockMappings.size()).append(")\n");
                        sb.append("-".repeat(60)).append("\n");
                        blockMappings.forEach((table, drops) ->
                                sb.append("  Mine ").append(table).append("  →  ").append(drops).append("\n"));

                        sb.append("\nMOB DROPS (").append(mobMappings.size()).append(")\n");
                        sb.append("-".repeat(60)).append("\n");
                        mobMappings.forEach((table, drops) ->
                                sb.append("  Kill ").append(table).append("  →  ").append(drops).append("\n"));

                        if (!otherMappings.isEmpty()) {
                            sb.append("\nOTHER (").append(otherMappings.size()).append(")\n");
                            sb.append("-".repeat(60)).append("\n");
                            otherMappings.forEach((table, drops) ->
                                    sb.append("  ").append(table).append("  →  ").append(drops).append("\n"));
                        }

                        Files.writeString(dumpFile, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
                        send(src, "§aRBMD: Dump written to:");
                        send(src, "§f" + dumpFile.toAbsolutePath());
                        send(src, "§7" + (blockMappings.size() + mobMappings.size() + otherMappings.size()) + " entries written.");

                    } catch (Exception e) {
                        send(src, "§cFailed to write dump: " + e.getMessage());
                        LOGGER.error("Failed to write rbmd_dump.txt", e);
                    }
                    return Command.SINGLE_SUCCESS;
                })
        );
    }

    private static String buildConfigHash() {
        // Include sorted mod list so adding/removing mods triggers a rebuild
        TreeSet<String> mods = new TreeSet<>();
        net.neoforged.fml.ModList.get().getMods().forEach(
                info -> mods.add(info.getModId() + "@" + info.getVersion())
        );
        return String.valueOf(Objects.hash(
                Config.chaosMode(),
                Config.randomizeBlocks(),
                Config.randomizeMobs(),
                Config.randomizeSilkTouch(),
                new TreeSet<>(Config.itemBlacklist()),
                new TreeSet<>(Config.mobBlacklist()),
                new TreeSet<>(Config.modBlacklist()),
                mods
        ));
    }

    // ── RELOAD: rescan packs then force rbmd_randomizer into the list ───────────

    private static final String PACK_ID = "file/rbmd_randomizer";

    private static void triggerReload(MinecraftServer server, String triggeredBy) {
        server.getPackRepository().reload();
        List<String> packs = new ArrayList<>(
                server.getPackRepository().getSelectedPacks()
                        .stream().map(Pack::getId).toList()
        );
        if (!packs.contains(PACK_ID)) {
            packs.add(PACK_ID);
            LOGGER.info("Force-added {} to pack list.", PACK_ID);
        }

        LOGGER.info("Reloading with packs: {}", packs);
        server.reloadResources(packs);
        LOGGER.info("Reload complete (triggered by {}) — drops are now randomized!", triggeredBy);
    }

    // ── DATAPACK GENERATION ──────────────────────────────────────────────────────

    /**
     * Loot table ResourceLocations that were successfully written to the datapack.
     * Mob drops whose loot table is in this set are already randomized by the datapack
     * so onLivingDrops skips them to avoid double-randomization.
     */
    private static final Set<ResourceLocation> COVERED_TABLES = new HashSet<>();

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private static int makeDatapack(MinecraftServer server, ResourceManager rm) {
        try {
            Path dpRoot = server.getWorldPath(LevelResource.DATAPACK_DIR)
                    .resolve("rbmd_randomizer");
            LOGGER.info("Writing datapack to: {}", dpRoot);
            Files.createDirectories(dpRoot);

            // pack_format 26 = Minecraft 1.21.1
            String meta = """
                    {
                      "pack": {
                        "pack_format": 26,
                        "description": "RBMD randomized block & mob drops (auto-generated)"
                      }
                    }
                    """;
            Files.writeString(dpRoot.resolve("pack.mcmeta"), meta, StandardCharsets.UTF_8);

            COVERED_TABLES.clear();
            var found = rm.listResources("loot_table", rl -> rl.getPath().endsWith(".json"));
            LOGGER.info("Found {} loot table files to process.", found.size());

            int count = 0;
            int skipped = 0;

            boolean randomizeBlocks = Config.randomizeBlocks();
            boolean randomizeMobs = Config.randomizeMobs();

            for (Map.Entry<ResourceLocation, ?> entry : found.entrySet()) {
                ResourceLocation loc = entry.getKey();
                String locPath = loc.getPath();

                if (!randomizeMobs && (locPath.contains("/entities/") || locPath.contains("/entity/"))) {
                    skipped++; continue;
                }
                if (!randomizeBlocks && locPath.contains("/blocks/")) {
                    skipped++; continue;
                }

                var opt = rm.getResource(loc);
                if (opt.isEmpty()) continue;

                try (InputStream in = opt.get().open();
                     Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {

                    JsonObject table = GSON.fromJson(reader, JsonObject.class);
                    JsonObject transformed = transformTable(table, server);
                    if (transformed == null) {
                        skipped++;
                        continue;
                    }

                    Path out = dpRoot
                            .resolve("data")
                            .resolve(loc.getNamespace())
                            .resolve(loc.getPath());
                    Files.createDirectories(out.getParent());
                    Files.writeString(out, GSON.toJson(transformed), StandardCharsets.UTF_8);
                    COVERED_TABLES.add(loc);
                    count++;

                } catch (Exception ex) {
                    skipped++;
                    LOGGER.warn("Skipped {} — {}", loc, ex.getMessage());
                }
            }

            LOGGER.info("Done. Written: {} | Skipped (no pools/errors): {}", count, skipped);
            return count;

        } catch (IOException e) {
            LOGGER.error("IOException during datapack generation!", e);
            return 0;
        }
    }

    /** Returns true if this pool or entry JSON has a silk touch match_tool condition. */
    private static boolean hasSilkTouchCondition(JsonObject obj) {
        if (!obj.has("conditions")) return false;
        for (JsonElement condEl : obj.getAsJsonArray("conditions")) {
            if (!condEl.isJsonObject()) continue;
            JsonObject cond = condEl.getAsJsonObject();
            String condType = cond.has("type") ? cond.get("type").getAsString() : "";
            if (!condType.equals("minecraft:match_tool")) continue;
            // Check for silk_touch enchantment predicate
            JsonObject predicate = cond.has("predicate") ? cond.getAsJsonObject("predicate") : null;
            if (predicate == null) continue;
            if (predicate.has("enchantments")) {
                for (JsonElement enchEl : predicate.getAsJsonArray("enchantments")) {
                    if (!enchEl.isJsonObject()) continue;
                    JsonObject ench = enchEl.getAsJsonObject();
                    String enchId = ench.has("enchantment") ? ench.get("enchantment").getAsString() : "";
                    if (enchId.equals("minecraft:silk_touch")) return true;
                }
            }
            // 1.21 format: enchantments as an object with "levels" and id list
            if (predicate.has("components")) {
                String raw = predicate.toString();
                if (raw.contains("silk_touch")) return true;
            }
        }
        return false;
    }

    private static JsonObject transformTable(JsonObject table, MinecraftServer server) {
        if (table == null || !table.has("pools")) return null;
        for (JsonElement poolEl : table.getAsJsonArray("pools")) {
            if (!poolEl.isJsonObject()) continue;
            JsonObject pool = poolEl.getAsJsonObject();
            // Skip entire pool if it has a silk touch condition and randomize_silk_touch is false
            if (!Config.randomizeSilkTouch() && hasSilkTouchCondition(pool)) continue;
            if (!pool.has("entries")) continue;
            for (JsonElement entryEl : pool.getAsJsonArray("entries")) {
                if (!entryEl.isJsonObject()) continue;
                JsonObject entry = entryEl.getAsJsonObject();
                // Also skip individual entries with silk touch conditions
                if (!Config.randomizeSilkTouch() && hasSilkTouchCondition(entry)) continue;
                rewriteEntry(entry, server);
            }
        }
        return table;
    }

    private static void rewriteEntry(JsonObject e, MinecraftServer server) {
        String type = e.has("type") ? e.get("type").getAsString() : "";
        switch (type) {
            case "minecraft:item" -> {
                if (e.has("name")) {
                    ResourceLocation fromId = ResourceLocation.parse(e.get("name").getAsString());
                    Item from = BuiltInRegistries.ITEM.get(fromId);
                    if (from != Items.AIR) {
                        ResourceLocation fromKey = BuiltInRegistries.ITEM.getKey(from);
                        boolean blacklisted = Config.itemBlacklist().contains(fromKey);
                        boolean isSpawnEgg = fromKey.getPath().endsWith("_spawn_egg");
                        if (!blacklisted && !isSpawnEgg) {
                            Item to = Permutation.map(from, server);
                            e.addProperty("name", BuiltInRegistries.ITEM.getKey(to).toString());
                        }
                    }
                }
            }
            case "minecraft:alternatives", "minecraft:sequence", "minecraft:group" -> {
                recurseEntries(e, "children", server);
                recurseEntries(e, "entries", server);
            }
            default -> recurseEntries(e, "entries", server);
        }
    }

    private static void recurseEntries(JsonObject e, String key, MinecraftServer server) {
        if (e.has(key)) {
            for (JsonElement ch : e.getAsJsonArray(key)) {
                if (ch.isJsonObject()) rewriteEntry(ch.getAsJsonObject(), server);
            }
        }
    }

    // ── ITEM PERMUTATION ─────────────────────────────────────────────────────────

    static final class Permutation {

        private static final Set<Item> EXCLUDED = Set.of(Items.AIR);

        private static final Map<Item, Item> MAP = new HashMap<>();
        private static boolean built = false;
        private static Long overrideSeed = null;
        /** The effective seed used to build the current MAP — used to detect world changes. */
        private static Long lastBuiltSeed = null;

        static synchronized void clearAndSetSeed(long seed) {
            MAP.clear();
            built = false;
            overrideSeed = seed;
            lastBuiltSeed = null;
        }

        static synchronized void clear() {
            MAP.clear();
            built = false;
            overrideSeed = null;
            lastBuiltSeed = null;
        }

        static synchronized boolean ensureBuiltForSeed(MinecraftServer server) {
            if (server == null) return false;
            long worldSeed = server.getWorldData().worldGenOptions().seed();
            long effectiveSeed = overrideSeed != null ? overrideSeed : worldSeed;
            if (built && lastBuiltSeed != null && lastBuiltSeed == effectiveSeed) {
                LOGGER.info("World seed {} unchanged — reusing existing permutation.", effectiveSeed);
                return false; // nothing changed
            }
            // Seed changed (new world) or never built — rebuild
            MAP.clear();
            built = false;
            ensureBuilt(server);
            return true; // rebuilt
        }

        static synchronized void ensureBuilt(MinecraftServer server) {
            if (built || server == null) return;

            Set<ResourceLocation> itemBlacklist = Config.itemBlacklist();
            Set<String> modBlacklist = Config.modBlacklist();

            Set<ResourceLocation> naturallyObtainable = new HashSet<>();
            var found = server.getResourceManager().listResources("loot_table",
                    rl -> rl.getPath().endsWith(".json"));
            for (var entry : found.entrySet()) {
                var opt = server.getResourceManager().getResource(entry.getKey());
                if (opt.isEmpty()) continue;
                try (var in = opt.get().open();
                     var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                    com.google.gson.JsonObject tbl = new com.google.gson.Gson().fromJson(reader,
                            com.google.gson.JsonObject.class);
                    if (tbl == null || !tbl.has("pools")) continue;
                    for (com.google.gson.JsonElement pe : tbl.getAsJsonArray("pools")) {
                        if (!pe.isJsonObject()) continue;
                        com.google.gson.JsonObject pool2 = pe.getAsJsonObject();
                        if (!pool2.has("entries")) continue;
                        List<String> names = new ArrayList<>();
                        for (com.google.gson.JsonElement ee : pool2.getAsJsonArray("entries"))
                            if (ee.isJsonObject()) collectDropNamesStatic(ee.getAsJsonObject(), names);
                        for (String name : names) {
                            try { naturallyObtainable.add(ResourceLocation.parse(name)); }
                            catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }
            LOGGER.info("Found {} naturally obtainable items from loot tables.", naturallyObtainable.size());

            List<Item> pool = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == null || EXCLUDED.contains(item)) continue;
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                if (!Config.chaosMode()) {
                    if (id.getPath().endsWith("_spawn_egg")) continue;
                    if (modBlacklist.contains(id.getNamespace())) continue;
                    if (itemBlacklist.contains(id)) continue;
                    if (!naturallyObtainable.contains(id)) continue;
                }
                pool.add(item);
            }

            LOGGER.info("Building item permutation from {} items.", pool.size());

            if (!pool.isEmpty()) {
                long worldSeed = server.getWorldData().worldGenOptions().seed();
                long seed = overrideSeed != null ? overrideSeed : worldSeed;
                Random rng = new Random(seed ^ 0xA2D15EEFL);
                Collections.shuffle(pool, rng);
                for (int i = 0; i < pool.size(); i++) {
                    MAP.put(pool.get(i), pool.get((i + 1) % pool.size()));
                }
                lastBuiltSeed = seed;
                LOGGER.info("Permutation built with seed: {}", seed);
            }

            built = true;
        }

        static synchronized long getLastBuiltSeed() {
            return lastBuiltSeed != null ? lastBuiltSeed : 0L;
        }

        static Item map(Item in, MinecraftServer server) {
            ensureBuilt(server);
            return MAP.getOrDefault(in, in);
        }
    }

    // ── UTILITY ──────────────────────────────────────────────────────────────────

    private static void send(CommandSourceStack src, String msg) {
        if (src.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            player.sendSystemMessage(Component.literal(msg));
        } else {
            src.sendSuccess(() -> Component.literal(msg), false);
        }
    }

    // ── CONFIG (config/rbmd-common.toml) ─────────────────────────────────────────

    public static final class Config {

        private static ModConfigSpec.BooleanValue CHAOS_MODE;
        private static ModConfigSpec.BooleanValue RANDOMIZE_BLOCKS;
        private static ModConfigSpec.BooleanValue RANDOMIZE_MOBS;
        private static ModConfigSpec.BooleanValue RANDOMIZE_SILK_TOUCH;
        private static ModConfigSpec.BooleanValue RESET_WELCOME;
        private static ModConfigSpec.ConfigValue<List<? extends String>> ITEM_BLACKLIST;
        private static ModConfigSpec.ConfigValue<List<? extends String>> MOB_BLACKLIST;
        private static ModConfigSpec.ConfigValue<List<? extends String>> MOD_BLACKLIST;

        static ModConfigSpec SPEC;

        static void init(ModConfigSpec.Builder b) {
            b.comment("RBMD — Randomized Block & Mob Drops");
            b.push("general");

            CHAOS_MODE = b
                    .comment(
                            "Chaos Mode — skips ALL blacklists and the natural drop filter.",
                            "Every registered item (including spawn eggs, unobtainables, modded items) is in the pool.",
                            "WARNING: some items may become unobtainable or break the drop cycle."
                    )
                    .define("chaos_mode", false);

            RANDOMIZE_BLOCKS = b
                    .comment("Randomize block loot tables (stone, ores, trees, etc).")
                    .define("randomize_blocks", true);

            RANDOMIZE_MOBS = b
                    .comment("Randomize mob drops (zombies, skeletons, modded mobs, etc).")
                    .define("randomize_mobs", true);

            RANDOMIZE_SILK_TOUCH = b
                    .comment(
                            "Randomize Silk Touch drops.",
                            "When true (default), Silk Touch drops are randomized like everything else.",
                            "Set to false to always get the original item with Silk Touch."
                    )
                    .define("randomize_silk_touch", true);

            ITEM_BLACKLIST = b
                    .comment(
                            "Items that always drop as themselves and are never randomized.",
                            "Defaults include unobtainable/unbreakable blocks so they never appear as drops.",
                            "Remove any entry if you want that item to be included in the randomization.",
                            "Use registry IDs, e.g. \"minecraft:diamond\""
                    )
                    .defineListAllowEmpty("item_blacklist", List.of(
                            "minecraft:bedrock", "minecraft:barrier",
                            "minecraft:command_block", "minecraft:chain_command_block",
                            "minecraft:repeating_command_block", "minecraft:structure_block",
                            "minecraft:structure_void", "minecraft:jigsaw",
                            "minecraft:light", "minecraft:debug_stick",
                            "minecraft:knowledge_book", "minecraft:bundle"
                    ), e -> e instanceof String);

            MOB_BLACKLIST = b
                    .comment(
                            "Mobs whose drops will never be randomized.",
                            "Use registry IDs, e.g. [\"minecraft:zombie\", \"mymod:boss\"]"
                    )
                    .defineListAllowEmpty("mob_blacklist", List.of(), e -> e instanceof String);

            MOD_BLACKLIST = b
                    .comment(
                            "Mod IDs to fully exclude from randomization.",
                            "All items and mob drops from these mods are excluded.",
                            "Use the mod ID e.g. [\"mymod\", \"anothermod\"]"
                    )
                    .defineListAllowEmpty("mod_blacklist", List.of(), e -> e instanceof String);

            RESET_WELCOME = b
                    .comment(
                            "Set to true to show the welcome message again on the next world join (per player).",
                            "Resets itself back to false automatically after showing."
                    )
                    .define("reset_welcome_message", false);

            b.pop();
        }

        static void register() {
            ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
            init(builder);
            SPEC = builder.build();
            net.neoforged.fml.ModList.get().getModContainerById(RBMDMod.MODID)
                    .ifPresent(c -> c.registerConfig(ModConfig.Type.COMMON, SPEC));
        }

        public static boolean chaosMode() {
            return CHAOS_MODE.get();
        }

        public static boolean randomizeBlocks() {
            return RANDOMIZE_BLOCKS.get();
        }

        public static boolean randomizeMobs() {
            return RANDOMIZE_MOBS.get();
        }

        public static boolean randomizeSilkTouch() {
            return RANDOMIZE_SILK_TOUCH.get();
        }

        public static boolean resetWelcome() {
            return RESET_WELCOME.get();
        }

        public static void clearResetWelcome() {
            RESET_WELCOME.set(false);
            SPEC.save();
        }

        public static Set<ResourceLocation> itemBlacklist() {
            Set<ResourceLocation> set = new HashSet<>();
            for (String s : ITEM_BLACKLIST.get()) {
                try { set.add(ResourceLocation.parse(s)); } catch (Exception ignored) {}
            }
            return set;
        }

        public static Set<ResourceLocation> mobBlacklist() {
            Set<ResourceLocation> set = new HashSet<>();
            for (String s : MOB_BLACKLIST.get()) {
                try { set.add(ResourceLocation.parse(s)); } catch (Exception ignored) {}
            }
            return set;
        }

        public static Set<String> modBlacklist() {
            Set<String> set = new HashSet<>();
            for (String s : MOD_BLACKLIST.get()) set.add(s.trim().toLowerCase());
            return set;
        }
    }
}
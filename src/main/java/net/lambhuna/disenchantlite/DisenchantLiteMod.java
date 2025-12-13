package net.lambhuna.disenchantlite;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class DisenchantLiteMod implements ModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("DisenchantLite");

    @Override
    public void onInitialize() {
        // Log mod loaded with version (falls back to "unknown" if not found)
        String version = FabricLoader.getInstance()
                .getModContainer("disenchantlite")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        LOGGER.info("DisenchantLite loaded with version: {}", version);

        // Register the command when the server starts.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("disenchanttest")
                    // The command requires a permission level of 2 (operator).
                    .requires(source -> {
                        var perm = source.getPermissions();
                        if (perm instanceof net.minecraft.command.permission.LeveledPermissionPredicate leveled) {
                            return leveled.getLevel().getLevel() >= 2;
                        }
                        return false;
                    })
                    // Executable part for when no player is specified (targets the command runner).
                    .executes(this::runGiveTestItems)
                    // Adds a sub-command to target a specific player.
                    .then(argument("player", EntityArgumentType.player())
                            .executes(context -> runGiveTestItems(context, EntityArgumentType.getPlayer(context, "player")))));
        });
    }

    /**
     * Runs the command, targeting the player who executed it.
     * @param context The command context.
     * @return 1 on success, 0 on failure.
     */
    private int runGiveTestItems(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendError(Text.literal("This command must be run by a player."));
            return 0;
        }
        return giveTestItems(context, player);
    }

    /**
     * Runs the command, targeting a specified player.
     * @param context The command context.
     * @param targetPlayer The player to give items to.
     * @return 1 on success.
     */
    private int runGiveTestItems(CommandContext<ServerCommandSource> context, ServerPlayerEntity targetPlayer) {
        return giveTestItems(context, targetPlayer);
    }

    /**
     * Contains the core logic for creating and giving all the specified items and experience.
     * @param context The command context, used for sending feedback.
     * @param player The player who will receive the items.
     * @return 1 on success.
     */
    private int giveTestItems(CommandContext<ServerCommandSource> context, ServerPlayerEntity player) {
        RegistryWrapper.WrapperLookup registryLookup = context.getSource().getRegistryManager();
        RegistryWrapper<Enchantment> enchantmentWrapper = registryLookup.getOrThrow(RegistryKeys.ENCHANTMENT);

        // 1. Create the Enchanted Diamond Sword
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        ItemEnchantmentsComponent.Builder swordEnchantments = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
        swordEnchantments.add(enchantmentWrapper.getOrThrow(Enchantments.SHARPNESS), 5);
        swordEnchantments.add(enchantmentWrapper.getOrThrow(Enchantments.SWEEPING_EDGE), 3);
        swordEnchantments.add(enchantmentWrapper.getOrThrow(Enchantments.LOOTING), 3);
        swordEnchantments.add(enchantmentWrapper.getOrThrow(Enchantments.UNBREAKING), 3);
        swordEnchantments.add(enchantmentWrapper.getOrThrow(Enchantments.FIRE_ASPECT), 2);
        swordEnchantments.add(enchantmentWrapper.getOrThrow(Enchantments.KNOCKBACK), 2);
        swordEnchantments.add(enchantmentWrapper.getOrThrow(Enchantments.MENDING), 1);
        sword.set(DataComponentTypes.ENCHANTMENTS, swordEnchantments.build());
        if (!player.getInventory().insertStack(sword)) {
            player.dropItem(sword, false);
        }

        // 2. Create the specific Enchanted Books
        insertOrDrop(player, createEnchantedBook(enchantmentWrapper, Enchantments.UNBREAKING, 3));
        insertOrDrop(player, createEnchantedBook(enchantmentWrapper, Enchantments.MENDING, 1));
        insertOrDrop(player, createEnchantedBook(enchantmentWrapper, Enchantments.AQUA_AFFINITY, 1));
        insertOrDrop(player, createEnchantedBook(enchantmentWrapper, Enchantments.RESPIRATION, 3));
        insertOrDrop(player, createEnchantedBook(enchantmentWrapper, Enchantments.THORNS, 3));
        insertOrDrop(player, createEnchantedBook(enchantmentWrapper, Enchantments.PROTECTION, 4));

        // 3. Give Unenchanted Netherite Armor
        insertOrDrop(player, new ItemStack(Items.NETHERITE_HELMET));
        insertOrDrop(player, new ItemStack(Items.NETHERITE_CHESTPLATE));
        insertOrDrop(player, new ItemStack(Items.NETHERITE_LEGGINGS));
        insertOrDrop(player, new ItemStack(Items.NETHERITE_BOOTS));

        // 4. Give other materials
        insertOrDrop(player, new ItemStack(Items.ANVIL));
        insertOrDrop(player, new ItemStack(Items.ENCHANTING_TABLE));
        insertOrDrop(player, new ItemStack(Items.BOOK, 64));
        insertOrDrop(player, new ItemStack(Items.LAPIS_LAZULI, 64));

        // 5. Give 1000 XP Levels
        player.addExperienceLevels(1000);

        // Send feedback to the command executor.
        context.getSource().sendFeedback(() -> Text.literal("Gave disenchant test items to " + player.getName().getString()), true);
        return 1;
    }

    /**
     * Helper method to create an enchanted book with a single enchantment.
     *
     * @param enchantmentWrapper Registry wrapper for looking up Enchantment registry entries.
     * @param enchantmentKey     The registry key of the enchantment to add to the book.
     * @param level              The level of the enchantment to store on the book.
     * @return An ItemStack representing an enchanted book that stores the specified enchantment and level.
     */
    private ItemStack createEnchantedBook(RegistryWrapper<Enchantment> enchantmentWrapper, net.minecraft.registry.RegistryKey<Enchantment> enchantmentKey, int level) {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        RegistryEntry<Enchantment> enchantment = enchantmentWrapper.getOrThrow(enchantmentKey);
        ItemEnchantmentsComponent.Builder bookEnchantments = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
        bookEnchantments.add(enchantment, level);
        book.set(DataComponentTypes.STORED_ENCHANTMENTS, bookEnchantments.build());
        return book;
    }

    /**
     * Helper method to safely insert items into player inventory or drop them if full.
     * @param player The player to give the item to.
     * @param item The item to give.
     */
    private void insertOrDrop(ServerPlayerEntity player, ItemStack item) {
        if (!player.getInventory().insertStack(item)) {
            player.dropItem(item, false);
        }
    }
}

package net.lambhuna.disenchantlite;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

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
                        var perm = source.permissions();
                        if (perm instanceof net.minecraft.server.permissions.LevelBasedPermissionSet leveled) {
                            return leveled.level().id() >= 2;
                        }
                        return false;
                    })
                    // Executable part for when no player is specified (targets the command runner).
                    .executes(this::runGiveTestItems)
                    // Adds a sub-command to target a specific player.
                    .then(argument("player", EntityArgument.player())
                            .executes(context -> runGiveTestItems(context, EntityArgument.getPlayer(context, "player")))));
        });
    }

    /**
     * Runs the command, targeting the player who executed it.
     * @param context The command context.
     * @return 1 on success, 0 on failure.
     */
    private int runGiveTestItems(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("This command must be run by a player."));
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
    private int runGiveTestItems(CommandContext<CommandSourceStack> context, ServerPlayer targetPlayer) {
        return giveTestItems(context, targetPlayer);
    }

    /**
     * Contains the core logic for creating and giving all the specified items and experience.
     * @param context The command context, used for sending feedback.
     * @param player The player who will receive the items.
     * @return 1 on success.
     */
    private int giveTestItems(CommandContext<CommandSourceStack> context, ServerPlayer player) {
        HolderLookup.Provider registryLookup = context.getSource().registryAccess();
        HolderLookup<Enchantment> enchantmentWrapper = registryLookup.lookupOrThrow(Registries.ENCHANTMENT);

        // 1. Create the Enchanted Diamond Sword
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        ItemEnchantments.Mutable swordEnchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        swordEnchantments.upgrade(enchantmentWrapper.getOrThrow(Enchantments.SHARPNESS), 5);
        swordEnchantments.upgrade(enchantmentWrapper.getOrThrow(Enchantments.SWEEPING_EDGE), 3);
        swordEnchantments.upgrade(enchantmentWrapper.getOrThrow(Enchantments.LOOTING), 3);
        swordEnchantments.upgrade(enchantmentWrapper.getOrThrow(Enchantments.UNBREAKING), 3);
        swordEnchantments.upgrade(enchantmentWrapper.getOrThrow(Enchantments.FIRE_ASPECT), 2);
        swordEnchantments.upgrade(enchantmentWrapper.getOrThrow(Enchantments.KNOCKBACK), 2);
        swordEnchantments.upgrade(enchantmentWrapper.getOrThrow(Enchantments.MENDING), 1);
        sword.set(DataComponents.ENCHANTMENTS, swordEnchantments.toImmutable());
        if (!player.getInventory().add(sword)) {
            player.drop(sword, false);
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
        player.giveExperienceLevels(1000);

        // Send feedback to the command executor.
        context.getSource().sendSuccess(() -> Component.literal("Gave disenchant test items to " + player.getName().getString()), true);
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
    private ItemStack createEnchantedBook(HolderLookup<Enchantment> enchantmentWrapper, net.minecraft.resources.ResourceKey<Enchantment> enchantmentKey, int level) {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        Holder<Enchantment> enchantment = enchantmentWrapper.getOrThrow(enchantmentKey);
        ItemEnchantments.Mutable bookEnchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        bookEnchantments.upgrade(enchantment, level);
        book.set(DataComponents.STORED_ENCHANTMENTS, bookEnchantments.toImmutable());
        return book;
    }

    /**
     * Helper method to safely insert items into player inventory or drop them if full.
     * @param player The player to give the item to.
     * @param item The item to give.
     */
    private void insertOrDrop(ServerPlayer player, ItemStack item) {
        if (!player.getInventory().add(item)) {
            player.drop(item, false);
        }
    }
}

package net.lambhuna.disenchantlite.mixin;

import net.lambhuna.disenchantlite.util.EnchantmentUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.ItemCombinerMenuSlotDefinition;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mixin(AnvilMenu.class)
public abstract class AnvilScreenHandlerMixin extends ItemCombinerMenu {
    private static final Logger LOGGER = LoggerFactory.getLogger("DisenchantLite");

    // Dummy constructor required when extending parent class
    public AnvilScreenHandlerMixin(@Nullable MenuType<?> type, int syncId,
                                   Inventory playerInventory,
                                   ContainerLevelAccess context,
                                   ItemCombinerMenuSlotDefinition forgingSlotsManager) {
        super(type, syncId, playerInventory, context, forgingSlotsManager);
    }

    @Shadow private DataSlot cost;

    @Shadow public void createResult() { throw new AssertionError(); }

    /**
     * Force synchronization of the level cost to the client.
     * This bypasses the dirty-check mechanism to ensure the client displays the cost correctly
     * even when vanilla client code sets an invalid recipe during slot updates.
     */
    private void forceSyncCost(int cost) {
        this.cost.set(cost);
        if (this.player instanceof ServerPlayer serverPlayer) {
            AnvilMenu handler = (AnvilMenu)(Object)this;
            serverPlayer.connection.send(
                new ClientboundContainerSetDataPacket(handler.containerId, 0, cost)
            );
        }
    }


    @Inject(method = "createResult", at = @At("HEAD"), cancellable = true)
    private void disenchantingUpdate(CallbackInfo ci) {
        AnvilMenu handler = (AnvilMenu)(Object)this;
        ItemStack left = handler.getSlot(0).getItem();
        ItemStack right = handler.getSlot(1).getItem();

//        LOGGER.info("DisenchantLite: Checking anvil operation - Left: {}, Right: {}",
//                left.isEmpty() ? "empty" : left.getItem().toString(),
//                right.isEmpty() ? "empty" : right.getItem().toString());

        // Case A: Tool/armor + Book → Enchanted Book + Clean Tool
        if (!left.isEmpty() && right.is(Items.BOOK) && !left.is(Items.ENCHANTED_BOOK)) {
            ItemEnchantments enchantments = EnchantmentUtils.getEnchantments(left);
            if (!enchantments.isEmpty()) {
//                LOGGER.info("DisenchantLite: Found tool with {} enchantments, creating enchanted book", enchantments.getSize());

                // Create enchanted book with all enchantments
                ItemStack newBook = new ItemStack(Items.ENCHANTED_BOOK);
                EnchantmentUtils.setEnchantments(enchantments, newBook);

                // Set the result
                handler.getSlot(2).setByPlayer(newBook);
                forceSyncCost(EnchantmentUtils.calculateCost(enchantments));

//                LOGGER.info("DisenchantLite: Set enchanted book as output with cost {}", EnchantmentUtils.calculateCost(enchantments));

                // Cancel vanilla processing since we handled this
                ci.cancel();
                return;
            }
        }

        // Case B: Enchanted book split (must have ≥2 enchants)
        if (left.is(Items.ENCHANTED_BOOK) && right.is(Items.BOOK) && !right.is(Items.ENCHANTED_BOOK)) {
            ItemEnchantments enchantments = EnchantmentUtils.getEnchantments(left);
            if (enchantments.size() > 1) {
//                LOGGER.info("DisenchantLite: Found enchanted book with {} enchantments, splitting", enchantments.getSize());

                var iterator = enchantments.entrySet().iterator();
                if (iterator.hasNext()) {
                    var firstEntry = iterator.next();
                    Holder<net.minecraft.world.item.enchantment.Enchantment> firstEnchantment = firstEntry.getKey();
                    int firstLevel = firstEntry.getIntValue();

                    // Create new book with first enchantment only
                    ItemEnchantments.Mutable firstBuilder = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
                    firstBuilder.upgrade(firstEnchantment, firstLevel);

                    ItemStack newBook = new ItemStack(Items.ENCHANTED_BOOK);
                    EnchantmentUtils.setEnchantments(firstBuilder.toImmutable(), newBook);

                    // Set the result
                    handler.getSlot(2).setByPlayer(newBook);
                    forceSyncCost(EnchantmentUtils.calculateCost(firstBuilder.toImmutable()));

//                    LOGGER.info("DisenchantLite: Set single enchantment book as output with cost {}",
//                            EnchantmentUtils.calculateCost(firstBuilder.build()));

                    // Cancel vanilla processing since we handled this
                    ci.cancel();
                    return;
                }
            }
        }

        // If we didn't handle the operation, let vanilla proceed
//        LOGGER.debug("DisenchantLite: No custom operation detected, allowing vanilla processing");
    }

    /**
     * Handle actual slot changes when the player takes the result.
     * We must replace/modify the input stacks here (vanilla would otherwise perform its own logic,
     * which does not match our custom result logic).
     */
    @Inject(method = "onTake", at = @At("HEAD"), cancellable = true)
    private void onTakeOutput(Player player, ItemStack result, CallbackInfo ci) {
        AnvilMenu handler = (AnvilMenu)(Object)this;
        ItemStack left = handler.getSlot(0).getItem();
        ItemStack right = handler.getSlot(1).getItem();

        // Case A: Tool/armor + Book -> give enchanted book, return clean tool, consume one book
        if (!left.isEmpty() && right.is(Items.BOOK) && !left.is(Items.ENCHANTED_BOOK)) {
            ItemEnchantments enchantments = EnchantmentUtils.getEnchantments(left);
            if (!enchantments.isEmpty()) {
//                LOGGER.info("DisenchantLite: onTakeOutput - disenchanted tool + consume one book");

                // Save the cost BEFORE updateResult() overwrites it
                int costToDeduct = this.cost.get();

                // Create clean copy of the left item (same item and damage, but no enchantments)
                ItemStack cleanLeft = left.copy();
                EnchantmentUtils.setEnchantments(ItemEnchantments.EMPTY, cleanLeft);
                cleanLeft.set(DataComponents.REPAIR_COST, 0);

                // Put the clean tool back into slot 0
                handler.getSlot(0).setByPlayer(cleanLeft);

                // Decrement one book from slot 1
                if (!right.isEmpty()) {
                    right.shrink(1);
                    handler.getSlot(1).setByPlayer(right.isEmpty() ? ItemStack.EMPTY : right);
                }

                // Clear the result slot (player receives 'result' already)
                handler.getSlot(2).setByPlayer(ItemStack.EMPTY);

                // Recompute the result (this will update the UI so the next split output appears)
                this.createResult();

                // Play anvil sound
                player.level().playSound(
                        null,
                        player.blockPosition(),
                        SoundEvents.ANVIL_USE,
                        SoundSource.PLAYERS,
                        1.0F, 1.0F
                );

                // Deduct experience levels (server-side only, skip creative mode)
                if (player instanceof ServerPlayer && !player.getAbilities().instabuild) {
                    player.giveExperienceLevels(-costToDeduct);
                }

                // Prevent vanilla onTakeOutput from running
                ci.cancel();
                return;
            }
        }

        // Case B: Enchanted book split -> remove the first enchant from left, return remaining book, consume one book
        if (left.is(Items.ENCHANTED_BOOK) && right.is(Items.BOOK) && !right.is(Items.ENCHANTED_BOOK)) {
            ItemEnchantments enchantments = EnchantmentUtils.getEnchantments(left);
            if (enchantments.size() > 1) {
//                LOGGER.info("DisenchantLite: onTakeOutput - splitting enchanted book and consuming one book");

                // Save the cost BEFORE updateResult() overwrites it
                int costToDeduct = this.cost.get();

                // Build remaining enchantments (skip the first entry)
                var iterator = enchantments.entrySet().iterator();
                if (iterator.hasNext()) {
                    // skip first
                    var first = iterator.next();

                    ItemEnchantments.Mutable remainingBuilder = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
                    while (iterator.hasNext()) {
                        var entry = iterator.next();
                        remainingBuilder.upgrade(entry.getKey(), entry.getIntValue());
                    }

                    // Put remaining enchanted book back into slot 0
                    ItemStack remainingBook = new ItemStack(Items.ENCHANTED_BOOK);
                    EnchantmentUtils.setEnchantments(remainingBuilder.toImmutable(), remainingBook);
                    handler.getSlot(0).setByPlayer(remainingBook);

                    // Decrement one book from slot 1
                    if (!right.isEmpty()) {
                        right.shrink(1);
                        handler.getSlot(1).setByPlayer(right.isEmpty() ? ItemStack.EMPTY : right);
                    }

                    // Clear the result slot
                    handler.getSlot(2).setByPlayer(ItemStack.EMPTY);

                    // Recompute the result (this will update the UI so the next split output appears)
                    this.createResult();

                    // Play anvil sound
                    player.level().playSound(
                            null,
                            player.blockPosition(),
                            SoundEvents.ANVIL_USE,
                            SoundSource.PLAYERS,
                            1.0F, 1.0F
                    );

                    // Deduct experience levels (server-side only, skip creative mode)
                    if (player instanceof net.minecraft.server.level.ServerPlayer && !player.getAbilities().instabuild) {
                        player.giveExperienceLevels(-costToDeduct);
                    }

                    ci.cancel();
                    return;
                }
            }
        }

        // If not one of our custom operations, allow vanilla to run
    }
}

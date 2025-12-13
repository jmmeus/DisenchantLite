package net.lambhuna.disenchantlite.mixin;

import net.lambhuna.disenchantlite.util.EnchantmentUtils;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.ScreenHandlerPropertyUpdateS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ForgingScreenHandler;
import net.minecraft.screen.Property;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.ForgingSlotsManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.sound.SoundCategory;
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

@Mixin(AnvilScreenHandler.class)
public abstract class AnvilScreenHandlerMixin extends ForgingScreenHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("DisenchantLite");

    // Dummy constructor required when extending parent class
    public AnvilScreenHandlerMixin(@Nullable ScreenHandlerType<?> type, int syncId,
                                   PlayerInventory playerInventory,
                                   ScreenHandlerContext context,
                                   ForgingSlotsManager forgingSlotsManager) {
        super(type, syncId, playerInventory, context, forgingSlotsManager);
    }

    @Shadow private Property levelCost;

    @Shadow public void updateResult() { throw new AssertionError(); }

    /**
     * Force synchronization of the level cost to the client.
     * This bypasses the dirty-check mechanism to ensure the client displays the cost correctly
     * even when vanilla client code sets an invalid recipe during slot updates.
     */
    private void forceSyncCost(int cost) {
        this.levelCost.set(cost);
        if (this.player instanceof ServerPlayerEntity serverPlayer) {
            AnvilScreenHandler handler = (AnvilScreenHandler)(Object)this;
            serverPlayer.networkHandler.sendPacket(
                new ScreenHandlerPropertyUpdateS2CPacket(handler.syncId, 0, cost)
            );
        }
    }


    @Inject(method = "updateResult", at = @At("HEAD"), cancellable = true)
    private void disenchantingUpdate(CallbackInfo ci) {
        AnvilScreenHandler handler = (AnvilScreenHandler)(Object)this;
        ItemStack left = handler.getSlot(0).getStack();
        ItemStack right = handler.getSlot(1).getStack();

//        LOGGER.info("DisenchantLite: Checking anvil operation - Left: {}, Right: {}",
//                left.isEmpty() ? "empty" : left.getItem().toString(),
//                right.isEmpty() ? "empty" : right.getItem().toString());

        // Case A: Tool/armor + Book → Enchanted Book + Clean Tool
        if (!left.isEmpty() && right.isOf(Items.BOOK) && !left.isOf(Items.ENCHANTED_BOOK)) {
            ItemEnchantmentsComponent enchantments = EnchantmentUtils.getEnchantments(left);
            if (!enchantments.isEmpty()) {
//                LOGGER.info("DisenchantLite: Found tool with {} enchantments, creating enchanted book", enchantments.getSize());

                // Create enchanted book with all enchantments
                ItemStack newBook = new ItemStack(Items.ENCHANTED_BOOK);
                EnchantmentUtils.setEnchantments(enchantments, newBook);

                // Set the result
                handler.getSlot(2).setStack(newBook);
                forceSyncCost(EnchantmentUtils.calculateCost(enchantments));

//                LOGGER.info("DisenchantLite: Set enchanted book as output with cost {}", EnchantmentUtils.calculateCost(enchantments));

                // Cancel vanilla processing since we handled this
                ci.cancel();
                return;
            }
        }

        // Case B: Enchanted book split (must have ≥2 enchants)
        if (left.isOf(Items.ENCHANTED_BOOK) && right.isOf(Items.BOOK) && !right.isOf(Items.ENCHANTED_BOOK)) {
            ItemEnchantmentsComponent enchantments = EnchantmentUtils.getEnchantments(left);
            if (enchantments.getSize() > 1) {
//                LOGGER.info("DisenchantLite: Found enchanted book with {} enchantments, splitting", enchantments.getSize());

                var iterator = enchantments.getEnchantmentEntries().iterator();
                if (iterator.hasNext()) {
                    var firstEntry = iterator.next();
                    RegistryEntry<net.minecraft.enchantment.Enchantment> firstEnchantment = firstEntry.getKey();
                    int firstLevel = firstEntry.getIntValue();

                    // Create new book with first enchantment only
                    ItemEnchantmentsComponent.Builder firstBuilder = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
                    firstBuilder.add(firstEnchantment, firstLevel);

                    ItemStack newBook = new ItemStack(Items.ENCHANTED_BOOK);
                    EnchantmentUtils.setEnchantments(firstBuilder.build(), newBook);

                    // Set the result
                    handler.getSlot(2).setStack(newBook);
                    forceSyncCost(EnchantmentUtils.calculateCost(firstBuilder.build()));

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
    @Inject(method = "onTakeOutput", at = @At("HEAD"), cancellable = true)
    private void onTakeOutput(PlayerEntity player, ItemStack result, CallbackInfo ci) {
        AnvilScreenHandler handler = (AnvilScreenHandler)(Object)this;
        ItemStack left = handler.getSlot(0).getStack();
        ItemStack right = handler.getSlot(1).getStack();

        // Case A: Tool/armor + Book -> give enchanted book, return clean tool, consume one book
        if (!left.isEmpty() && right.isOf(Items.BOOK) && !left.isOf(Items.ENCHANTED_BOOK)) {
            ItemEnchantmentsComponent enchantments = EnchantmentUtils.getEnchantments(left);
            if (!enchantments.isEmpty()) {
//                LOGGER.info("DisenchantLite: onTakeOutput - disenchanted tool + consume one book");

                // Save the cost BEFORE updateResult() overwrites it
                int costToDeduct = this.levelCost.get();

                // Create clean copy of the left item (same item and damage, but no enchantments)
                ItemStack cleanLeft = left.copy();
                EnchantmentUtils.setEnchantments(ItemEnchantmentsComponent.DEFAULT, cleanLeft);
                cleanLeft.set(DataComponentTypes.REPAIR_COST, 0);

                // Put the clean tool back into slot 0
                handler.getSlot(0).setStack(cleanLeft);

                // Decrement one book from slot 1
                if (!right.isEmpty()) {
                    right.decrement(1);
                    handler.getSlot(1).setStack(right.isEmpty() ? ItemStack.EMPTY : right);
                }

                // Clear the result slot (player receives 'result' already)
                handler.getSlot(2).setStack(ItemStack.EMPTY);

                // Recompute the result (this will update the UI so the next split output appears)
                this.updateResult();

                // Play anvil sound
                player.getWorld().playSound(
                        null,
                        player.getBlockPos(),
                        SoundEvents.BLOCK_ANVIL_USE,
                        SoundCategory.PLAYERS,
                        1.0F, 1.0F
                );

                // Deduct experience levels (server-side only, skip creative mode)
                if (player instanceof ServerPlayerEntity && !player.getAbilities().creativeMode) {
                    player.addExperienceLevels(-costToDeduct);
                }

                // Prevent vanilla onTakeOutput from running
                ci.cancel();
                return;
            }
        }

        // Case B: Enchanted book split -> remove the first enchant from left, return remaining book, consume one book
        if (left.isOf(Items.ENCHANTED_BOOK) && right.isOf(Items.BOOK) && !right.isOf(Items.ENCHANTED_BOOK)) {
            ItemEnchantmentsComponent enchantments = EnchantmentUtils.getEnchantments(left);
            if (enchantments.getSize() > 1) {
//                LOGGER.info("DisenchantLite: onTakeOutput - splitting enchanted book and consuming one book");

                // Save the cost BEFORE updateResult() overwrites it
                int costToDeduct = this.levelCost.get();

                // Build remaining enchantments (skip the first entry)
                var iterator = enchantments.getEnchantmentEntries().iterator();
                if (iterator.hasNext()) {
                    // skip first
                    var first = iterator.next();

                    ItemEnchantmentsComponent.Builder remainingBuilder = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
                    while (iterator.hasNext()) {
                        var entry = iterator.next();
                        remainingBuilder.add(entry.getKey(), entry.getIntValue());
                    }

                    // Put remaining enchanted book back into slot 0
                    ItemStack remainingBook = new ItemStack(Items.ENCHANTED_BOOK);
                    EnchantmentUtils.setEnchantments(remainingBuilder.build(), remainingBook);
                    handler.getSlot(0).setStack(remainingBook);

                    // Decrement one book from slot 1
                    if (!right.isEmpty()) {
                        right.decrement(1);
                        handler.getSlot(1).setStack(right.isEmpty() ? ItemStack.EMPTY : right);
                    }

                    // Clear the result slot
                    handler.getSlot(2).setStack(ItemStack.EMPTY);

                    // Recompute the result (this will update the UI so the next split output appears)
                    this.updateResult();

                    // Play anvil sound
                    player.getWorld().playSound(
                            null,
                            player.getBlockPos(),
                            SoundEvents.BLOCK_ANVIL_USE,
                            SoundCategory.PLAYERS,
                            1.0F, 1.0F
                    );

                    // Deduct experience levels (server-side only, skip creative mode)
                    if (player instanceof ServerPlayerEntity && !player.getAbilities().creativeMode) {
                        player.addExperienceLevels(-costToDeduct);
                    }

                    ci.cancel();
                    return;
                }
            }
        }

        // If not one of our custom operations, allow vanilla to run
    }
}

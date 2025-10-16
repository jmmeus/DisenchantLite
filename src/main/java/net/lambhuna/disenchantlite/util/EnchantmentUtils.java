package net.lambhuna.disenchantlite.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;

public class EnchantmentUtils {

    public static ItemEnchantmentsComponent getEnchantments(ItemStack stack) {
        if (stack.isOf(Items.ENCHANTED_BOOK)) {
            return stack.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        } else {
            return stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        }
    }

    public static void setEnchantments(ItemEnchantmentsComponent enchantments, ItemStack stack) {
        if (stack.isOf(Items.ENCHANTED_BOOK)) {
            stack.set(DataComponentTypes.STORED_ENCHANTMENTS, enchantments);
        } else {
            stack.set(DataComponentTypes.ENCHANTMENTS, enchantments);
        }
    }

    public static int calculateCost(ItemEnchantmentsComponent enchantments) {
        int cost = 0;
        for (var entry : enchantments.getEnchantmentEntries()) {
            RegistryEntry<Enchantment> enchantmentEntry = entry.getKey();
            Enchantment enchantment = enchantmentEntry.value();
            int level = entry.getIntValue();
            int rarity = enchantment.getAnvilCost(); // anvil cost method
            cost += Math.max(1, rarity / 2) * level;
        }
        return Math.max(1, cost);
    }
}
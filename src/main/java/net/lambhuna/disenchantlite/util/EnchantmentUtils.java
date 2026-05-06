package net.lambhuna.disenchantlite.util;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public class EnchantmentUtils {

    public static ItemEnchantments getEnchantments(ItemStack stack) {
        if (stack.is(Items.ENCHANTED_BOOK)) {
            return stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        } else {
            return stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        }
    }

    public static void setEnchantments(ItemEnchantments enchantments, ItemStack stack) {
        if (stack.is(Items.ENCHANTED_BOOK)) {
            stack.set(DataComponents.STORED_ENCHANTMENTS, enchantments);
        } else {
            stack.set(DataComponents.ENCHANTMENTS, enchantments);
        }
    }

    public static int calculateCost(ItemEnchantments enchantments) {
        int cost = 0;
        for (var entry : enchantments.entrySet()) {
            Holder<Enchantment> enchantmentEntry = entry.getKey();
            Enchantment enchantment = enchantmentEntry.value();
            int level = entry.getIntValue();
            int rarity = enchantment.getAnvilCost(); // anvil cost method
            cost += Math.max(1, rarity / 2) * level;
        }
        return Math.max(1, cost);
    }
}
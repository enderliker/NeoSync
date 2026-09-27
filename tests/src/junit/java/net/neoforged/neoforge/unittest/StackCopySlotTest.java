/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemHandlerCopySlot;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.StackCopySlot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StackCopySlotTest {
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void copySlotReportsBackingIndexAndUpdatesThatSlot(boolean wrapExistingSlot) {
        var handler = new ItemStackHandler(4);
        handler.setStackInSlot(0, new ItemStack(Items.DIRT, 7));
        handler.setStackInSlot(3, new ItemStack(Items.STONE, 5));
        var backingSlot = new SlotItemHandler(handler, 3, 12, 34);
        // Menu indices are independent of indices in the backing item handler.
        ((Slot) backingSlot).index = 19;
        var slot = wrapExistingSlot ? new ItemHandlerCopySlot(backingSlot) : new ItemHandlerCopySlot(handler, 3, 12, 34);
        slot.index = 23;

        assertEquals(3, slot.getSlotIndex());
        assertEquals(3, slot.getContainerSlot());
        assertEquals(12, slot.x);
        assertEquals(34, slot.y);
        assertSame(handler, slot.getItemHandler());
        assertEquals(Items.STONE, slot.getItem().getItem());

        slot.set(new ItemStack(Items.STONE, 2));
        assertEquals(2, handler.getStackInSlot(3).getCount());
        assertEquals(Items.DIRT, handler.getStackInSlot(0).getItem());
        assertEquals(7, handler.getStackInSlot(0).getCount());
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyConstructorRetainsZeroIndex() {
        var slot = new StackCopySlot(12, 34) {
            @Override
            protected ItemStack getStackCopy() {
                return ItemStack.EMPTY;
            }

            @Override
            protected void setStackCopy(ItemStack stack) {}
        };

        assertEquals(0, slot.getSlotIndex());
        assertEquals(0, slot.getContainerSlot());
        assertEquals(12, slot.x);
        assertEquals(34, slot.y);
    }
}

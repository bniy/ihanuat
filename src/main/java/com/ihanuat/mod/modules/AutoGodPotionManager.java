package com.ihanuat.mod.modules;

import com.ihanuat.mod.MacroConfig;
import com.ihanuat.mod.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

public class AutoGodPotionManager {
    public static volatile long lastConsumeTime = 0;
    public static volatile boolean shouldConsume = false;

    public static volatile long lastPassiveCheck = 0;
    public static volatile boolean forceTest = false;
    public static volatile com.ihanuat.mod.MacroState.State stateBeforeTest = null;

    // Consume state machine
    private enum ConsumeState { IDLE, SWAP_TO_HOTBAR, CLOSE_AND_USE, REOPEN_INVENTORY, SWAP_BACK, CLOSE_DONE }
    private static volatile ConsumeState consumeState = ConsumeState.IDLE;
    private static volatile long consumeStateTime = 0;
    private static volatile int potionSlot = -1;       // original slot of the god potion
    private static volatile int previousSelected = -1;  // hotbar slot that was selected before



    public static boolean hasGodPotionActive(Minecraft client) {
        if (client.getConnection() == null) return true; // default to true to prevent accidental drinking
        
        boolean inSkyblock = false;
        
        if (client.level != null && client.level.getScoreboard() != null) {
            net.minecraft.world.scores.Objective sidebar = client.level.getScoreboard().getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
            if (sidebar != null) {
                String objName = sidebar.getDisplayName().getString().replaceAll("(?i)§.", "").toLowerCase();
                if (objName.contains("skyblock")) {
                    inSkyblock = true;
                }
            }
        }
        
        if (!inSkyblock) return true; 
        
        StringBuilder tabList = new StringBuilder();
        
        for (net.minecraft.client.multiplayer.PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            if (info.getTabListDisplayName() != null) {
                tabList.append(info.getTabListDisplayName().getString()).append(" ");
            } else if (info.getProfile() != null) {
                tabList.append(String.valueOf(info.getProfile())).append(" ");
            }
        }
        
        String clean = tabList.toString().replaceAll("(?i)§[0-9a-fk-or]", "");
        String normalized = clean.replace('\u00A0', ' ').toLowerCase();
        
        return normalized.contains("god potion");
    }

    public static void update(Minecraft client) {
        if (!MacroConfig.autoGodPotion || client.player == null) return;
        if (!com.ihanuat.mod.MacroStateManager.isMacroRunning()) return;

        long now = System.currentTimeMillis();

        if (!shouldConsume && now - lastPassiveCheck > 10000) {
            lastPassiveCheck = now;
            if (!hasGodPotionActive(client)) {
                shouldConsume = true;
                if (MacroConfig.showDebug) {
                    ClientUtils.sendDebugMessage(client, "AutoGodPotion: Passive Tablist check detected no God Potion.");
                }
            }
        }

    }

    public static void consumeIfShould(Minecraft client) {
        if (!shouldConsume || client.player == null) return;

        // Final sanity check to avoid drinking multiple (skip if force testing)
        if (!forceTest && hasGodPotionActive(client)) {
            shouldConsume = false;
            return;
        }
        forceTest = false;

        long now = System.currentTimeMillis();
        if (now - lastConsumeTime < 5000) return;

        // Find God Potion in entire inventory (slots 0-35)
        int foundSlot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack != null && !stack.isEmpty()) {
                String name = stack.getHoverName().getString().replaceAll("(?i)§[0-9a-fk-or]", "").trim();
                String normalized = name.replace('\u00A0', ' ').toLowerCase();
                if (normalized.contains("god potion")) {
                    foundSlot = i;
                    break;
                }
            }
        }

        if (foundSlot != -1) {
            com.ihanuat.mod.mixin.AccessorInventory inv = (com.ihanuat.mod.mixin.AccessorInventory) client.player.getInventory();
            previousSelected = inv.getSelected();

            if (foundSlot < 9) {
                // Already in hotbar — just select, use, and restore
                inv.setSelected(foundSlot);
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                inv.setSelected(previousSelected);
                lastConsumeTime = now;
                shouldConsume = false;
                if (com.ihanuat.mod.MacroStateManager.getCurrentState() == com.ihanuat.mod.MacroState.State.GOD_POTION
                        && AutoGodPotionBuyer.getCurrentState() == AutoGodPotionBuyer.BuyerState.IDLE) {
                    com.ihanuat.mod.MacroState.State restoreTo = stateBeforeTest != null ? stateBeforeTest : com.ihanuat.mod.MacroState.State.FARMING;
                    stateBeforeTest = null;
                    com.ihanuat.mod.MacroStateManager.setCurrentState(restoreTo);
                }
                if (MacroConfig.showDebug) {
                    ClientUtils.sendDebugMessage(client, "AutoGodPotion: Consumed from hotbar slot " + foundSlot);
                }
            } else {
                // In upper inventory — start the swap state machine
                potionSlot = foundSlot;
                consumeState = ConsumeState.SWAP_TO_HOTBAR;
                consumeStateTime = now;
                if (MacroConfig.showDebug) {
                    ClientUtils.sendDebugMessage(client, "AutoGodPotion: God Potion found in slot " + foundSlot + ". Starting swap sequence...");
                }
            }
            return;
        }

        // No God Potion found — try buying after 30s
        if (now - lastConsumeTime > 30000) {
            if (MacroConfig.autoGodPotionBuyFromAH) {
                if (MacroConfig.showDebug) {
                    ClientUtils.sendDebugMessage(client, "AutoGodPotion: Could not find God Potion in inventory. Starting Buy Sequence.");
                }
                lastConsumeTime = now;
                AutoGodPotionBuyer.start();
            } else {
                if (MacroConfig.showDebug) {
                    ClientUtils.sendDebugMessage(client, "AutoGodPotion: No God Potion found in inventory. AH buying is disabled.");
                }
                shouldConsume = false;
            }
        }
    }

    public static void updateConsume(Minecraft client) {
        if (consumeState == ConsumeState.IDLE || client.player == null) return;

        long now = System.currentTimeMillis();
        com.ihanuat.mod.mixin.AccessorInventory inv = (com.ihanuat.mod.mixin.AccessorInventory) client.player.getInventory();

        switch (consumeState) {
            case SWAP_TO_HOTBAR:
                // Open inventory and swap god potion to hotbar slot 7 (slot 8 is SkyBlock menu)
                if (now - consumeStateTime < 500) return;
                client.gameMode.handleInventoryMouseClick(
                    client.player.inventoryMenu.containerId, potionSlot, 7,
                    net.minecraft.world.inventory.ClickType.SWAP, client.player);
                if (MacroConfig.showDebug) {
                    ClientUtils.sendDebugMessage(client, "AutoGodPotion: Swapped slot " + potionSlot + " to hotbar slot 7");
                }
                consumeState = ConsumeState.CLOSE_AND_USE;
                consumeStateTime = now;
                break;

            case CLOSE_AND_USE:
                // Wait for swap to register, then use the item from hotbar slot 7
                if (now - consumeStateTime < 600) return;
                if (client.screen != null) {
                    client.setScreen(null);
                }
                inv.setSelected(7);
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                if (MacroConfig.showDebug) {
                    ClientUtils.sendDebugMessage(client, "AutoGodPotion: Using God Potion from hotbar slot 7");
                }
                consumeState = ConsumeState.SWAP_BACK;
                consumeStateTime = now;
                break;

            case SWAP_BACK:
                // Wait for use to register, then swap original item back
                if (now - consumeStateTime < 800) return;
                client.gameMode.handleInventoryMouseClick(
                    client.player.inventoryMenu.containerId, potionSlot, 7,
                    net.minecraft.world.inventory.ClickType.SWAP, client.player);
                if (MacroConfig.showDebug) {
                    ClientUtils.sendDebugMessage(client, "AutoGodPotion: Swapped hotbar slot 7 back to slot " + potionSlot);
                }
                consumeState = ConsumeState.CLOSE_DONE;
                consumeStateTime = now;
                break;

            case CLOSE_DONE:
                // Restore selected slot and clean up
                if (now - consumeStateTime < 400) return;
                if (client.screen != null) {
                    client.setScreen(null);
                }
                inv.setSelected(previousSelected);
                lastConsumeTime = now;
                shouldConsume = false;
                consumeState = ConsumeState.IDLE;
                potionSlot = -1;
                previousSelected = -1;
                if (com.ihanuat.mod.MacroStateManager.getCurrentState() == com.ihanuat.mod.MacroState.State.GOD_POTION
                        && AutoGodPotionBuyer.getCurrentState() == AutoGodPotionBuyer.BuyerState.IDLE) {
                    com.ihanuat.mod.MacroState.State restoreTo = stateBeforeTest != null ? stateBeforeTest : com.ihanuat.mod.MacroState.State.FARMING;
                    stateBeforeTest = null;
                    com.ihanuat.mod.MacroStateManager.setCurrentState(restoreTo);
                }
                if (MacroConfig.showDebug) {
                    ClientUtils.sendDebugMessage(client, "AutoGodPotion: Consume sequence complete.");
                }
                break;

            default:
                break;
        }
    }
}

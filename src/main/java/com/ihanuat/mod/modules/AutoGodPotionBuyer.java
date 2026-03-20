package com.ihanuat.mod.modules;

import com.ihanuat.mod.MacroConfig;
import com.ihanuat.mod.MacroState;
import com.ihanuat.mod.MacroStateManager;
import com.ihanuat.mod.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;

public class AutoGodPotionBuyer {
    
    public enum BuyerState {
        IDLE,
        WAIT_AH_OPEN,
        VERIFY_SORT_BIN,
        CLICK_GOD_POTION,
        CONFIRM_BUY_NOW,
        CONFIRM_FINAL,
        VERIFYING_PURCHASE,
        CLAIM_OPEN_AH,
        CLAIM_MANAGE_BIDS,
        CLAIM_CLICK_POTION,
        CLAIM_COLLECT_AUCTION,
        PURCHASE_FAILED_REOPEN,
        FINISH
    }

    private static volatile BuyerState currentState = BuyerState.IDLE;
    public static BuyerState getCurrentState() { return currentState; }
    private static volatile long stateStartTime = 0;
    private static volatile long globalStartTime = 0;
    private static volatile int retryCount = 0;
    private static volatile int lastClickedSlotIndex = -1; // Track which slot we last tried to buy
    private static volatile int attemptCount = 0; // How many potions we've tried to buy total
    private static volatile int sortClickCount = 0; // How many times we've clicked the sort hopper
    private static final int MAX_ATTEMPTS = 10; // Give up after this many failed purchases

    public static void start() {
        if (!MacroStateManager.isMacroRunning()) return;

        Minecraft mc = Minecraft.getInstance();
        if (!hasFreeInventorySlot(mc)) {
            ClientUtils.sendDebugMessage(mc, "AutoGodPotionBuyer: Inventory is full! Cannot buy God Potion.");
            return;
        }

        MacroStateManager.setCurrentState(MacroState.State.GOD_POTION);
        currentState = BuyerState.WAIT_AH_OPEN;
        stateStartTime = System.currentTimeMillis();
        globalStartTime = stateStartTime;
        retryCount = 0;
        lastClickedSlotIndex = -1;
        attemptCount = 0;
        sortClickCount = 0;
        
        if (MacroConfig.showDebug) {
            ClientUtils.sendDebugMessage(Minecraft.getInstance(), "AutoGodPotionBuyer: Starting purchase sequence (/ahsearch God Potion)");
        }
        
        // Use execute() to send on next tick, avoiding command cooldown conflicts
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.connection.sendCommand("ahsearch God Potion");
            }
        });
    }

    public static void update(Minecraft client) {
        if (MacroStateManager.getCurrentState() != MacroState.State.GOD_POTION) {
            if (currentState != BuyerState.IDLE) {
                currentState = BuyerState.IDLE;
            }
            return;
        }

        long now = System.currentTimeMillis();
        // Global timeout of 45 seconds to prevent getting stuck (includes claim time)
        if (currentState != BuyerState.IDLE && now - globalStartTime > 45000) {
            ClientUtils.sendDebugMessage(client, "AutoGodPotionBuyer: State machine timed out! Canceling purchase.");
            finishAndResumeFarming(client, false);
            return;
        }

        switch (currentState) {
            case IDLE:
                break;
                
            case WAIT_AH_OPEN:
                if (now - stateStartTime < 500) return;
                
                if (client.screen instanceof AbstractContainerScreen) {
                    AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.screen;
                    String title = screen.getTitle().getString().toLowerCase();
                    
                    if (title.contains("auctions: \"god potion\"") || title.contains("auctions") || title.contains("god potion")) {
                        currentState = BuyerState.VERIFY_SORT_BIN;
                        stateStartTime = now;
                    }
                }
                break;
                
            case VERIFY_SORT_BIN:
                if (now - stateStartTime < 500) return; // Wait for GUI items to populate
                
                if (client.screen instanceof AbstractContainerScreen) {
                    AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.screen;
                    
                    if (50 < screen.getMenu().slots.size()) {
                        Slot sortSlot = screen.getMenu().slots.get(50);
                        if (!sortSlot.getItem().isEmpty()) {
                            // Read the lore/tooltip to check which sort is selected
                            // The selected option has a ► arrow prefix
                            java.util.List<Component> tooltip = sortSlot.getItem().getTooltipLines(
                                net.minecraft.world.item.Item.TooltipContext.of(client.level),
                                client.player,
                                net.minecraft.world.item.TooltipFlag.Default.NORMAL);
                            
                            boolean isLowestPrice = false;
                            for (Component line : tooltip) {
                                String lineText = line.getString().replaceAll("(?i)§[0-9a-fk-or]", "").trim();
                                // Check for arrow (►/▶) followed by "Lowest Price"
                                if (lineText.contains("Lowest Price") && 
                                    (lineText.contains("\u25BA") || lineText.contains("\u25B6") || lineText.contains("►") || lineText.contains("▶"))) {
                                    isLowestPrice = true;
                                    break;
                                }
                            }
                            
                            if (isLowestPrice) {
                                if (MacroConfig.showDebug) {
                                    ClientUtils.sendDebugMessage(client, "AutoGodPotionBuyer: Sort is set to Lowest Price. Proceeding.");
                                }
                                currentState = BuyerState.CLICK_GOD_POTION;
                                stateStartTime = now;
                            } else {
                                // Click the sort hopper to cycle to the next option
                                sortClickCount++;
                                if (sortClickCount > 4) {
                                    // Cycled through all options without finding Lowest Price — just proceed
                                    if (MacroConfig.showDebug) {
                                        ClientUtils.sendDebugMessage(client, "AutoGodPotionBuyer: Could not set sort to Lowest Price. Proceeding anyway.");
                                    }
                                    currentState = BuyerState.CLICK_GOD_POTION;
                                    stateStartTime = now;
                                } else {
                                    if (MacroConfig.showDebug) {
                                        ClientUtils.sendDebugMessage(client, "AutoGodPotionBuyer: Sort not set to Lowest Price. Clicking sort (attempt " + sortClickCount + ")");
                                    }
                                    client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, sortSlot.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, client.player);
                                    stateStartTime = now; // Reset timer, stay in VERIFY_SORT_BIN to re-check
                                }
                            }
                        }
                    } else {
                        // Sort slot not available, just proceed
                        currentState = BuyerState.CLICK_GOD_POTION;
                        stateStartTime = now;
                    }
                }
                break;
                
            case CLICK_GOD_POTION:
                if (now - stateStartTime < 500) return;
                
                if (client.screen instanceof AbstractContainerScreen) {
                    AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.screen;
                    
                    // Only scan the actual item slots in the AH GUI (slots 11-16 and 20-25, 0-indexed)
                    boolean clicked = false;
                    int[] validSlots = {11, 12, 13, 14, 15, 16, 20, 21, 22, 23, 24, 25};
                    
                    for (int i : validSlots) {
                        if (i <= lastClickedSlotIndex) continue; // Skip already-tried slots
                        if (i >= screen.getMenu().slots.size()) break;
                        Slot slot = screen.getMenu().slots.get(i);
                        if (!slot.getItem().isEmpty()) {
                            String name = slot.getItem().getHoverName().getString().replaceAll("(?i)§[0-9a-fk-or]", "").toLowerCase();
                            if (name.contains("god potion")) {
                                lastClickedSlotIndex = i;
                                attemptCount++;
                                
                                if (MacroConfig.showDebug) {
                                    ClientUtils.sendDebugMessage(client, "AutoGodPotionBuyer: Attempting to buy God Potion in slot " + i + " (attempt " + attemptCount + ")");
                                }
                                
                                client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, client.player);
                                clicked = true;
                                currentState = BuyerState.CONFIRM_BUY_NOW;
                                stateStartTime = now;
                                break;
                            }
                        }
                    }
                    
                    if (!clicked) {
                        // No more God Potions on this page — give up
                        ClientUtils.sendDebugMessage(client, "AutoGodPotionBuyer: No more God Potion listings available. Canceling.");
                        finishAndResumeFarming(client, false);
                    }
                }
                break;
                
            case CONFIRM_BUY_NOW:
                // First confirm screen: click the Gold Nugget named "Buy Item Right Now"
                if (now - stateStartTime < 500) return;
                
                if (client.screen instanceof AbstractContainerScreen) {
                    AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.screen;
                    
                    for (Slot slot : screen.getMenu().slots) {
                        if (!slot.getItem().isEmpty()) {
                            String itemName = slot.getItem().getHoverName().getString().toLowerCase();
                            if (itemName.contains("buy item right now")) {
                                
                                client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, client.player);
                                currentState = BuyerState.CONFIRM_FINAL;
                                stateStartTime = now;
                                return;
                            }
                        }
                    }
                }
                break;
                
            case CONFIRM_FINAL:
                // Second confirm screen: click the Green Concrete named "Confirm"
                if (now - stateStartTime < 500) return;
                
                if (client.screen instanceof AbstractContainerScreen) {
                    AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.screen;
                    
                    for (Slot slot : screen.getMenu().slots) {
                        if (!slot.getItem().isEmpty()) {
                            String itemName = slot.getItem().getHoverName().getString().toLowerCase();
                            if (itemName.contains("confirm")) {
                                
                                client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, client.player);
                                currentState = BuyerState.VERIFYING_PURCHASE;
                                stateStartTime = now;
                                return;
                            }
                        }
                    }
                }
                break;
                
            case VERIFYING_PURCHASE:
                // Give it 2 seconds to get a chat response
                if (now - stateStartTime > 2000) {
                    // No chat message received — assume success and try to claim
                    if (MacroConfig.showDebug) {
                        ClientUtils.sendDebugMessage(client, "AutoGodPotionBuyer: No failure message received. Opening AH to claim...");
                    }
                    if (client.screen != null) {
                        client.setScreen(null);
                    }
                    currentState = BuyerState.CLAIM_OPEN_AH;
                    stateStartTime = now;
                }
                break;
            
            case CLAIM_OPEN_AH:
                // Open /ah to get to the Auction House main menu
                if (now - stateStartTime < 500) return;

                if (client.screen == null) {
                    ClientUtils.sendCommand(client, "/ah");
                    stateStartTime = now;
                } else if (client.screen instanceof AbstractContainerScreen) {
                    AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.screen;
                    String title = screen.getTitle().getString().toLowerCase();

                    if (title.contains("auction house")) {
                        currentState = BuyerState.CLAIM_MANAGE_BIDS;
                        stateStartTime = now;
                    }
                }
                break;

            case CLAIM_MANAGE_BIDS:
                // Click the golden carrot named "Manage Bids"
                if (now - stateStartTime < 500) return;

                if (client.screen instanceof AbstractContainerScreen) {
                    AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.screen;

                    for (Slot slot : screen.getMenu().slots) {
                        if (!slot.getItem().isEmpty()) {
                            String itemName = slot.getItem().getHoverName().getString().toLowerCase();
                            if (itemName.contains("manage bids")) {
                                if (MacroConfig.showDebug) {
                                    ClientUtils.sendDebugMessage(client, "AutoGodPotionBuyer: Clicking 'Manage Bids'...");
                                }
                                client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, client.player);
                                currentState = BuyerState.CLAIM_CLICK_POTION;
                                stateStartTime = now;
                                return;
                            }
                        }
                    }
                }
                break;

            case CLAIM_CLICK_POTION:
                // Click the God Potion in the Manage Bids menu
                if (now - stateStartTime < 500) return;

                if (client.screen instanceof AbstractContainerScreen) {
                    AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.screen;

                    for (Slot slot : screen.getMenu().slots) {
                        if (!slot.getItem().isEmpty()) {
                            String itemName = slot.getItem().getHoverName().getString().replaceAll("(?i)§[0-9a-fk-or]", "").toLowerCase();
                            if (itemName.contains("god potion")) {
                                if (MacroConfig.showDebug) {
                                    ClientUtils.sendDebugMessage(client, "AutoGodPotionBuyer: Clicking God Potion to claim...");
                                }
                                client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, client.player);
                                currentState = BuyerState.CLAIM_COLLECT_AUCTION;
                                stateStartTime = now;
                                return;
                            }
                        }
                    }
                    // God Potion not found in bids — maybe already collected
                    if (hasGodPotionInInventory(client)) {
                        finishAndResumeFarming(client, true);
                    } else {
                        ClientUtils.sendDebugMessage(client, "AutoGodPotionBuyer: God Potion not found in Manage Bids. Resuming farming.");
                        finishAndResumeFarming(client, false);
                    }
                }
                break;

            case CLAIM_COLLECT_AUCTION:
                // Click the gold block named "Collect Auction"
                if (now - stateStartTime < 500) return;

                if (client.screen instanceof AbstractContainerScreen) {
                    AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.screen;

                    for (Slot slot : screen.getMenu().slots) {
                        if (!slot.getItem().isEmpty()) {
                            String itemName = slot.getItem().getHoverName().getString().toLowerCase();
                            if (itemName.contains("collect auction")) {
                                if (MacroConfig.showDebug) {
                                    ClientUtils.sendDebugMessage(client, "AutoGodPotionBuyer: Clicking 'Collect Auction'...");
                                }
                                client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, client.player);
                                stateStartTime = now;
                                break;
                            }
                        }
                    }

                    // Check if we got the potion after clicking
                    if (now - stateStartTime > 1000) {
                        if (hasGodPotionInInventory(client)) {
                            if (MacroConfig.showDebug) {
                                ClientUtils.sendDebugMessage(client, "AutoGodPotionBuyer: God Potion collected successfully!");
                            }
                            finishAndResumeFarming(client, true);
                        } else if (now - stateStartTime > 5000) {
                            ClientUtils.sendDebugMessage(client, "AutoGodPotionBuyer: Could not collect God Potion from AH. Resuming farming.");
                            finishAndResumeFarming(client, false);
                        }
                    }
                } else {
                    // Screen closed — check inventory
                    if (hasGodPotionInInventory(client)) {
                        finishAndResumeFarming(client, true);
                    } else {
                        finishAndResumeFarming(client, false);
                    }
                }
                break;

            case PURCHASE_FAILED_REOPEN:
                // After a failed purchase, we need to go back to the AH search results
                if (now - stateStartTime < 500) return;
                
                if (client.screen instanceof AbstractContainerScreen) {
                    AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.screen;
                    String title = screen.getTitle().getString().toLowerCase();
                    
                    if (title.contains("auctions") || title.contains("god potion")) {
                        // We're back on the search results — try the next one
                        if (MacroConfig.showDebug) {
                            ClientUtils.sendDebugMessage(client, "AutoGodPotionBuyer: Back on AH listing. Trying next God Potion...");
                        }
                        currentState = BuyerState.CLICK_GOD_POTION;
                        stateStartTime = now;
                    } else if (title.contains("bin auction") || title.contains("confirm")) {
                        // Still on the confirm screen — close it by pressing escape/back
                        client.setScreen(null);
                        // Re-search
                        stateStartTime = now;
                        ClientUtils.sendCommand(client, "/ahsearch God Potion");
                        currentState = BuyerState.WAIT_AH_OPEN;
                        // Reset slot tracking since it's a fresh search
                        lastClickedSlotIndex = -1;
                    }
                } else {
                    // No screen open — need to re-search
                    ClientUtils.sendCommand(client, "/ahsearch God Potion");
                    currentState = BuyerState.WAIT_AH_OPEN;
                    stateStartTime = now;
                    lastClickedSlotIndex = -1;
                }
                break;
                
            case FINISH:
                finishAndResumeFarming(client, false);
                break;
        }
    }

    public static void handleChatMessage(String chatText) {
        if (MacroStateManager.getCurrentState() != MacroState.State.GOD_POTION) return;
        if (currentState != BuyerState.VERIFYING_PURCHASE && currentState != BuyerState.CONFIRM_BUY_NOW && currentState != BuyerState.CONFIRM_FINAL) return;
        
        String lower = chatText.toLowerCase();
        
        if (lower.contains("you don't have enough coins") || lower.contains("not enough coins")) {
            // Can't afford
            ClientUtils.sendDebugMessage(Minecraft.getInstance(), "AutoGodPotionBuyer: Not enough coins! Canceling.");
            finishAndResumeFarming(Minecraft.getInstance(), false);
            
        } else if (lower.contains("escrow refunded")) {
            // Someone sniped it — try the next one
            if (attemptCount >= MAX_ATTEMPTS) {
                ClientUtils.sendDebugMessage(Minecraft.getInstance(), "AutoGodPotionBuyer: Failed " + attemptCount + " attempts. Giving up.");
                finishAndResumeFarming(Minecraft.getInstance(), false);
            } else {
                if (MacroConfig.showDebug) {
                    ClientUtils.sendDebugMessage(Minecraft.getInstance(), "AutoGodPotionBuyer: Purchase failed (sold out). Trying next listing... (attempt " + attemptCount + "/" + MAX_ATTEMPTS + ")");
                }
                currentState = BuyerState.PURCHASE_FAILED_REOPEN;
                stateStartTime = System.currentTimeMillis();
            }
            
        } else if (lower.contains("you purchased") || lower.contains("bought god potion") || lower.contains("purchased god potion")) {
            // Success — now we need to claim it from AH
            if (MacroConfig.showDebug) {
                ClientUtils.sendDebugMessage(Minecraft.getInstance(), "AutoGodPotionBuyer: Purchase confirmed! Opening AH to claim...");
            }
            Minecraft client = Minecraft.getInstance();
            if (client.screen != null) {
                client.setScreen(null);
            }
            currentState = BuyerState.CLAIM_OPEN_AH;
            stateStartTime = System.currentTimeMillis();
        }
    }

    private static boolean hasFreeInventorySlot(Minecraft client) {
        if (client.player == null) return false;
        for (int i = 0; i < 36; i++) {
            if (client.player.getInventory().getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasGodPotionInInventory(Minecraft client) {
        if (client.player == null) return false;
        for (int i = 0; i < client.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                String name = stack.getHoverName().getString().replaceAll("(?i)§[0-9a-fk-or]", "").toLowerCase();
                if (name.contains("god potion")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void finishAndResumeFarming(Minecraft client, boolean purchaseSucceeded) {
        currentState = BuyerState.IDLE;
        lastClickedSlotIndex = -1;
        attemptCount = 0;

        if (client.screen != null) {
            client.setScreen(null);
        }

        if (purchaseSucceeded) {
            AutoGodPotionManager.shouldConsume = true;
            AutoGodPotionManager.forceTest = true;
            AutoGodPotionManager.consumeIfShould(client);
        } else {
            MacroState.State restoreTo = AutoGodPotionManager.stateBeforeTest != null ? AutoGodPotionManager.stateBeforeTest : MacroState.State.FARMING;
            AutoGodPotionManager.stateBeforeTest = null;
            MacroStateManager.setCurrentState(restoreTo);
        }
    }
}

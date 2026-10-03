package com.tablecards;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/** A deck for one card game. Right-click a mahjong table with it to host or join a game. */
public class DeckItem extends Item {
    public final GameType type;

    public DeckItem(GameType type, Settings settings) {
        super(settings);
        this.type = type;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType tooltipType) {
        tooltip.add(Text.translatable("item.tablecards.deck.tooltip.use").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("item.tablecards.deck.tooltip.bot").formatted(Formatting.GRAY));
    }
}

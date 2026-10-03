package com.tablecards.engine;

import java.util.List;

/** A titled block of text lines in a player's view of the board. */
public record Section(String title, List<String> lines) {
}

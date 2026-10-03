package com.tablecards.engine;

import java.util.List;

/** The question the game is waiting on: which player must choose, what is asked, and the options. */
public record Decision(int player, String prompt, List<Option> options) {
    public List<String> labels() {
        return options.stream().map(Option::label).toList();
    }
}

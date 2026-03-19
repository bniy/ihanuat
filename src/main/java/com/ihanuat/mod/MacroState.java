package com.ihanuat.mod;

public class MacroState {
    public enum State {
        OFF,
        FARMING,
        CLEANING,
        RECOVERING,
        VISITING,
        AUTOSELLING,
        SPRAYING,
        BUYING_POTION
    }

    public enum Location {
        GARDEN, HUB, LOBBY, LIMBO, UNKNOWN
    }
}

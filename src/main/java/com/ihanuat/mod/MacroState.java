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
        GOD_POTION
    }

    public enum Location {
        GARDEN, HUB, LOBBY, LIMBO, UNKNOWN
    }
}

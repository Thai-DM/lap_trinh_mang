package com.smashprofs.server.network;

import com.smashprofs.server.game.MatchSetup;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Setup bất biến đã được runtime sở hữu; guard chỉ quan sát lifecycle/metadata, không JNI. */
public record GameStartDelivery(MatchSetup setup, BooleanSupplier stillValid) {
    public GameStartDelivery { Objects.requireNonNull(setup); Objects.requireNonNull(stillValid); }
}

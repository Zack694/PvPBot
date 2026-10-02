package dev.z.pvpbot.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to the raw cursor position plus an invoker for the raw GLFW callback
 * so the bot can inject mouse deltas through the very same handler the OS
 * uses ({@link Mouse#onCursorPos}). This is a genuine mouse-move event from
 * the game's point of view.
 */
@Mixin(Mouse.class)
public interface MouseAccessor {

        @Accessor("x")
        double pvpbot$x();

        @Accessor("y")
        double pvpbot$y();

        // v2.3: setters so the injected cursor position can be put back —
        // onCursorPos stores the injected x/y, and GLFW's real cursor never
        // moved, so the NEXT real (or launcher-emitted) cursor event produced
        // a delta that undid every degree the bot had turned.
        @Accessor("x")
        void pvpbot$setX(double x);

        @Accessor("y")
        void pvpbot$setY(double y);

        @Invoker("onCursorPos")
        void pvpbot$onCursorPos(long window, double x, double y);
}

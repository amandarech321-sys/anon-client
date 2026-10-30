package com.anon.client.mixin;

import com.anon.client.AnonClient;
import com.anon.client.Fullbright;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.option.GameOptions;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public class AnonMixins {
}

@Mixin(ClientPlayNetworkHandler.class)
class MixinNetworkHandler {
    @Inject(method = "onPacket", at = @At("HEAD"), cancellable = true)
    private void anon$onPacket(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof EntityVelocityUpdateS2CPacket vp) {
            if (AnonClient.MODULE_MANAGER == null) return;
            var velocityMod = AnonClient.MODULE_MANAGER.get("Velocity");
            if (velocityMod != null && velocityMod.isEnabled()) {
                var h = velocityMod.settings.get(0);
                var v = velocityMod.settings.get(1);
                if (h != null && v != null && h instanceof com.anon.client.NumSetting n1 && v instanceof com.anon.client.NumSetting n2) {
                    if (n1.value == 0 && n2.value == 0) {
                        ci.cancel();
                    }
                }
            }
        }
    }
}

@Mixin(GameOptions.class)
class MixinGameOptions {
    @Inject(method = "getGamma", at = @At("HEAD"), cancellable = true)
    private void anon$getGamma(CallbackInfoReturnable<Double> cir) {
        if (Fullbright.active()) cir.setReturnValue(100.0D);
    }
}

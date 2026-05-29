package com.hypixel.reward;

import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

@Mod(modid = HypixelRewardMod.MODID, name = "Hypixel每日奖励", version = "1.1")
public class HypixelRewardMod {

    public static final String MODID = "hypixelreward";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new HypRewardEventHandler());
        ClientCommandHandler.instance.registerCommand(new CommandHypClaim());
    }
}

package com.sch246.maidtaskscroll;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;

@Mod(MaidTaskScroll.MODID)
public class MaidTaskScroll {
    public static final String MODID = "maidtaskscroll";
    public static final Logger LOGGER = LogUtils.getLogger();
    public MaidTaskScroll(IEventBus modEventBus, ModContainer modContainer) {
    }

}

package com.hfr.mixin.bootstrap;

import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Configuration selection only; UniMixins owns all transformation and remapping. */
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.Name("Xenofactions Mixin Loader")
@IFMLLoadingPlugin.TransformerExclusions("com.hfr.mixin.bootstrap")
public final class XFMixinLoader implements IFMLLoadingPlugin, IEarlyMixinLoader {
    @Override public String getMixinConfig() { return "mixins.hfr.early.json"; }

    @Override public List<String> getMixins(Set<String> loadedCoreMods) {
        // The pinned coremod JAR is already on LaunchClassLoader's path at this phase.
        // Do not load its classes here, or query FML's not-yet-discovered normal mods.
        if (loadedCoreMods.contains("com.cardinalstar.cubicchunks.asm.coremod.CubicChunksCoreMod")) {
            return Arrays.asList("MixinChatRewards", "MixinMinerToolEnergy", "earth.MixinCubicPlayerManager", "earth.MixinNetworkChannel",
                    "earth.MixinWorldSyncStateMachine");
        }
        return Arrays.asList("MixinChatRewards", "MixinMinerToolEnergy");
    }

    @Override public String[] getASMTransformerClass() { return new String[0]; }
    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public void injectData(Map<String, Object> data) { }
    @Override public String getAccessTransformerClass() { return null; }
}

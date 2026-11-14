
package dev.icyplace;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IcyPlace implements ModInitializer {
    public static final String MOD_ID = "icyplace";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("IcyPlace loaded!");
    }
}


package dev.icyplace.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IcyPlaceClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("icyplace");

    @Override
    public void onInitializeClient() {
        LOGGER.info("IcyPlace client initialized!");
    }
}

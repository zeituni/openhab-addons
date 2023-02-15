/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.dmx.internal.handler;

import static org.openhab.binding.dmx.internal.DmxBindingConstants.THING_TYPE_ARTNET_BRIDGE;

import java.io.IOException;
import java.net.*;
import java.util.Collections;
import java.util.Set;

import org.openhab.binding.dmx.internal.config.ArtnetBridgeHandlerConfiguration;
import org.openhab.binding.dmx.internal.dmxoverethernet.ArtnetNode;
import org.openhab.binding.dmx.internal.dmxoverethernet.ArtnetPacket;
import org.openhab.binding.dmx.internal.dmxoverethernet.DmxOverEthernetHandler;
import org.openhab.binding.dmx.internal.dmxoverethernet.IpNode;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingTypeUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ArtnetBridgeHandler} is responsible for handling the communication
 * with ArtNet devices
 *
 * @author Jan N. Klug - Initial contribution
 */
public class ArtnetBridgeHandler extends DmxOverEthernetHandler {
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_ARTNET_BRIDGE);
    public static final int MIN_UNIVERSE_ID = 0;
    public static final int MAX_UNIVERSE_ID = 32767;

    private final Logger logger = LoggerFactory.getLogger(ArtnetBridgeHandler.class);

    private int retryIterval;

    private Socket checkConnectionSocket;

    public ArtnetBridgeHandler(Bridge artnetBridge) {
        super(artnetBridge);
    }

    @Override
    protected void updateConfiguration() {
        ArtnetBridgeHandlerConfiguration configuration = getConfig().as(ArtnetBridgeHandlerConfiguration.class);

        setUniverse(configuration.universe, MIN_UNIVERSE_ID, MAX_UNIVERSE_ID);
        packetTemplate.setUniverse(universe.getUniverseId());
        retryIterval = configuration.retryInterval;
        receiverNodes.clear();
        if (configuration.address.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Could not initialize sender (address not set)");
            uninstallScheduler();
            logger.debug("remote address not set for {}", this.thing.getUID());
            return;
        } else {
            try {
                receiverNodes = IpNode.fromString(configuration.address, ArtnetNode.DEFAULT_PORT);
                logger.debug("using unicast mode to {} for {}", receiverNodes.toString(), this.thing.getUID());
            } catch (IllegalArgumentException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
                return;
            }
        }

        if (!configuration.localaddress.isEmpty()) {
            senderNode = new IpNode(configuration.localaddress);
        }
        logger.debug("originating address is {} for {}", senderNode, this.thing.getUID());

        refreshAlways = configuration.refreshmode.equals("always");

        logger.debug("refresh mode set to always: {}", refreshAlways);

        updateStatus(ThingStatus.UNKNOWN);
        super.updateConfiguration();

        logger.debug("updated configuration for ArtNet bridge {}", this.thing.getUID());
    }

    @Override
    public void initialize() {
        logger.debug("initializing ArtNet bridge {}", this.thing.getUID());

        packetTemplate = new ArtnetPacket();
        updateConfiguration();
    }

    @Override
    protected void openConnection() {
        if (getThing().getStatus() != ThingStatus.ONLINE) {
            try {
                createDatagramSocket();
                // try to connect to verify that we can send packets
                checkConnection();
                logger.debug("opened socket {} in bridge {}", senderNode, this.thing.getUID());
                // There can be only one receiver node in Artnet Bridge
                updateStatus(ThingStatus.ONLINE);
            } catch (IOException e) {
                logger.debug("could not open socket {} in bridge {}: {}", senderNode, this.thing.getUID(),
                        e.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "opening UDP socket failed");
                // closing the socket (as the datagram socket is probably not null)
                closeConnection();
                logger.debug("Waiting {} seconds until next connection retry", retryIterval);
                try {
                    Thread.sleep(retryIterval * 1000);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
                openConnection();
            }
        }
    }

    private void checkConnection() throws IOException {
        logger.debug("Checking connection: trying to send a packet to {}", receiverNodes.get(0));
        sendDataToReceiverNode(getDatagramPacket(), receiverNodes.get(0));
        if (getThing().getStatus() == ThingStatus.OFFLINE) {
            throw new IOException("Could not connect to " + receiverNodes.get(0));
        } else {
            logger.debug("Connection to {} was successful", receiverNodes.get(0));
        }
    }
}

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
package org.openhab.binding.dmx.internal.dmxoverethernet;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

import org.openhab.binding.dmx.internal.DmxBridgeHandler;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link DmxOverEthernetHandler} is an abstract class with base functions
 * for DMX over Ethernet Bridges (ArtNet, sACN)
 *
 * @author Jan N. Klug - Initial contribution
 */

public abstract class DmxOverEthernetHandler extends DmxBridgeHandler {
    private final Logger logger = LoggerFactory.getLogger(DmxOverEthernetHandler.class);

    protected DmxOverEthernetPacket packetTemplate;
    protected IpNode senderNode = new IpNode();
    protected List<IpNode> receiverNodes = new ArrayList<>();

    protected boolean refreshAlways = false;

    protected DatagramSocket socket = null;
    private long lastSend = 0;
    private int repeatCounter = 0;
    private int sequenceNo = 0;

    protected void createDatagramSocket() throws SocketException {
        if (senderNode.getAddress() == null) {
            if (senderNode.getPort() == 0) {
                socket = new DatagramSocket();
                senderNode.setInetAddress(socket.getLocalAddress());
                senderNode.setPort(socket.getLocalPort());
            } else {
                socket = new DatagramSocket(senderNode.getPort());
                senderNode.setInetAddress(socket.getLocalAddress());
            }
        } else {
            socket = new DatagramSocket(senderNode.getPort(), senderNode.getAddress());
        }
    }

    @Override
    protected void closeConnection() {
        if (socket != null) {
            logger.debug("closing socket {} in bridge {}", senderNode, this.thing.getUID());
            socket.close();
            socket = null;
        } else {
            logger.debug("socket was already closed when calling closeConnection in bridge {}", this.thing.getUID());
        }
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "UDP socket closed");
    }

    @Override
    protected void sendDmxData() {
        if (getThing().getStatus() == ThingStatus.ONLINE) {
            boolean needsSending = false;
            long now = System.currentTimeMillis();
            universe.calculateBuffer(now);
            if ((universe.getLastBufferChanged() > lastSend) || refreshAlways) {
                needsSending = true;
                repeatCounter = 0;
            } else if (now - lastSend > 800) {
                needsSending = true;
            } else if (repeatCounter < 3) {
                needsSending = true;
                repeatCounter++;
            }
            if (needsSending) {
                DatagramPacket sendPacket = getDatagramPacket();
                for (IpNode receiverNode : receiverNodes) {
                    sendDataToReceiverNode(sendPacket, receiverNode);
                }
                lastSend = now;
                sequenceNo = (sequenceNo + 1) % 256;
            }
        } else {
            openConnection();
        }
    }

    protected DatagramPacket getDatagramPacket() {
        packetTemplate.setPayload(universe.getBuffer(), universe.getBufferSize());
        packetTemplate.setSequence(sequenceNo);
        DatagramPacket sendPacket = new DatagramPacket(packetTemplate.getRawPacket(), packetTemplate.getPacketLength());
        return sendPacket;
    }

    protected void sendDataToReceiverNode(DatagramPacket sendPacket, IpNode receiverNode) {
        if (socket == null || socket.isClosed()) {
            logger.error("Socket is Null or closed!");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            return;
        }
        sendPacket.setAddress(receiverNode.getAddress());
        sendPacket.setPort(receiverNode.getPort());
        logger.trace("sending packet with length {} to {}", packetTemplate.getPacketLength(), receiverNode.toString());
        try {
            socket.send(sendPacket);
            // If I arrived here then it is ok
            if (ThingStatus.OFFLINE == getThing().getStatus()) {
                logger.debug("Changing status of {} back from OFFLINE to ONLINE", getThing());
                updateStatus(ThingStatus.ONLINE);
            }
        } catch (IOException e) {
            logger.debug("Could not send to {} in {}: {}", receiverNode, this.thing.getUID(), e.getMessage());
            closeConnection(ThingStatusDetail.COMMUNICATION_ERROR, "could not send DMX data");
        }
    }

    public DmxOverEthernetHandler(Bridge sacnBridge) {
        super(sacnBridge);
    }
}

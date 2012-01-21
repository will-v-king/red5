/*
 * RED5 Open Source Flash Server - http://code.google.com/p/red5/
 * 
 * Copyright 2006-2012 by respective authors (see below). All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.red5.server.net.rtmp;

import org.apache.mina.core.buffer.IoBuffer;
import org.red5.server.api.IScope;
import org.red5.server.api.stream.IClientStream;
import org.red5.server.api.stream.IRtmpSampleAccess;
import org.red5.server.net.rtmp.event.IRTMPEvent;
import org.red5.server.net.rtmp.event.Invoke;
import org.red5.server.net.rtmp.event.Notify;
import org.red5.server.net.rtmp.message.Header;
import org.red5.server.net.rtmp.message.Packet;
import org.red5.server.net.rtmp.status.Status;
import org.red5.server.net.rtmp.status.StatusCodes;
import org.red5.server.service.Call;
import org.red5.server.service.PendingCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identified connection that transfers packets.
 */
public class Channel {
    
	protected static Logger log = LoggerFactory.getLogger(Channel.class);

	private final static String CALL_ON_STATUS = "onStatus";
	
	/**
     * RTMP connection used to transfer packets.
     */
	private RTMPConnection connection;

    /**
     * Channel id
     */
    private int id;

    /**
     * Creates channel from connection and channel id
     * @param conn                Connection
     * @param channelId           Channel id
     */
	public Channel(RTMPConnection conn, int channelId) {
		connection = conn;
		id = channelId;
	}

    /**
     * Closes channel with this id on RTMP connection.
     */
    public void close() {
        if (connection == null) {
            return;
        }
        
        connection.closeChannel(id);
	}

	/**
     * Getter for id.
     *
     * @return  Channel ID
     */
    public int getId() {
		return id;
	}
	
	/**
     * Getter for RTMP connection.
     *
     * @return  RTMP connection
     */
    protected RTMPConnection getConnection() {
		return connection;
	}

    /**
     * Writes packet from event data to RTMP connection.
	 *
     * @param event          Event data
     */
    public void write(IRTMPEvent event) {
        if (connection == null) {
            return;
        }
        
		final IClientStream stream = connection.getStreamByChannelId(id);
		if (id > 3 && stream == null) {
			log.info("Stream doesn't exist any longer, discarding message {}", event);
			return;
		}
		final int streamId = (stream == null) ? 0 : stream.getStreamId();
		write(event, streamId);
	}

    /**
     * Writes packet from event data to RTMP connection and stream id.
	 *
     * @param event           Event data
     * @param streamId        Stream id
     */
    private void write(IRTMPEvent event, int streamId) {
        if (connection == null) {
            return;
        }
        
		final Header header = new Header();
		final Packet packet = new Packet(header, event);
		header.setChannelId(id);
		header.setTimer(event.getTimestamp());
		header.setStreamId(streamId);
		header.setDataType(event.getDataType());
		// should use RTMPConnection specific method.. 
		connection.write(packet);
	}

    /**
     * Sends status notification.
	 *
     * @param status           Status
     */
    public void sendStatus(Status status) {
        if (connection == null) {
            return;
        }
        
		final boolean andReturn = !status.getCode().equals(StatusCodes.NS_DATA_START);
		final Notify event;
		if (andReturn) {
			final PendingCall call = new PendingCall(null, CALL_ON_STATUS, new Object[] { status });
			event = new Invoke();
			if (status.getCode().equals(StatusCodes.NS_PLAY_START)) {	
				IScope scope = connection.getScope();
				if (scope.getContext().getApplicationContext().containsBean(IRtmpSampleAccess.BEAN_NAME)) {
					IRtmpSampleAccess sampleAccess = (IRtmpSampleAccess) scope.getContext().getApplicationContext().getBean(IRtmpSampleAccess.BEAN_NAME);
					boolean videoAccess = sampleAccess.isVideoAllowed(scope);
					boolean audioAccess = sampleAccess.isAudioAllowed(scope);
					if (videoAccess || audioAccess) {
						final Call call2 = new Call(null, "|RtmpSampleAccess", null);
						Notify notify = new Notify();
						notify.setInvokeId(connection.getInvokeId()); 
						notify.setCall(call2);
						notify.setData(IoBuffer.wrap(new byte[] { 0x01, (byte) (audioAccess ? 0x01 : 0x00), 0x01, (byte) (videoAccess ? 0x01 : 0x00) }));
						write(notify, connection.getStreamIdForChannel(id));
					}
				}
			}
			event.setInvokeId(connection.getInvokeId()); 
			event.setCall(call);
		} else {
			final Call call = new Call(null, CALL_ON_STATUS, new Object[] { status });
			event = new Notify();
			event.setInvokeId(connection.getInvokeId()); 
			event.setCall(call);
		}
		// We send directly to the corresponding stream as for
		// some status codes, no stream has been created and thus
		// "getStreamByChannelId" will fail.
		write(event, connection.getStreamIdForChannel(id));
	}

}

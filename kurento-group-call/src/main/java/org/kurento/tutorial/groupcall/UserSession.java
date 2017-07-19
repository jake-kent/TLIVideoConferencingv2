/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.kurento.tutorial.groupcall;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.kurento.client.Continuation;
import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.IceCandidateFoundEvent;
import org.kurento.client.MediaPipeline;
import org.kurento.client.MediaProfileSpecType;
import org.kurento.client.MediaState;
import org.kurento.client.MediaStateChangedEvent;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.client.RecorderEndpoint;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.google.gson.JsonObject;

/**
 *
 * @author Ivan Gracia (izanmail@gmail.com)
 * @since 4.3.1
 */
public class UserSession implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(UserSession.class);

  private final String name;
  private final WebSocketSession session;

  private final MediaPipeline pipeline;

  private final String roomName;
  private final WebRtcEndpoint outgoingMedia;
  private final ConcurrentMap<String, WebRtcEndpoint> incomingMedia = new ConcurrentHashMap<>();
  private RecorderEndpoint recorderCaller;

  private String preConvertedName;
  private boolean pendingConversion;

  private boolean isTeacher;

  // file storage & recording setup
  private static final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-S");
  public static final String RECORDING_PATH = "file:///tmp/recordings/" + df.format(new Date()) + "-";
  public static final String RECORDING_EXT = ".mp4";

  public UserSession(final String name, final String roomName, final WebSocketSession session,
      boolean isTeacher, final MediaPipeline pipeline) {

    this.pipeline = pipeline;
    this.name = name;
    this.isTeacher = isTeacher;
    this.session = session;
    this.roomName = roomName;
    this.outgoingMedia = new WebRtcEndpoint.Builder(pipeline).build();

    this.outgoingMedia.setMinVideoRecvBandwidth(2000);
    this.outgoingMedia.setMinVideoSendBandwidth(2000);
    this.outgoingMedia.setMaxVideoRecvBandwidth(2000);
    this.outgoingMedia.setMaxVideoSendBandwidth(2000);

    this.outgoingMedia.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

      @Override
      public void onEvent(IceCandidateFoundEvent event) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "iceCandidate");
        response.addProperty("name", name);
        response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
        try {
          synchronized (session) {
            session.sendMessage(new TextMessage(response.toString()));
          }
        } catch (IOException e) {
          log.debug(e.getMessage());
        }
      }
    });

    this.outgoingMedia.addMediaStateChangedListener(new EventListener<MediaStateChangedEvent>() {

      @Override public void onEvent(MediaStateChangedEvent event) {
        if (event.getNewState() == MediaState.CONNECTED) {
          // recording code
          log.info("USER {}: begin recording in room {}", name, roomName);
          preConvertedName =  name + "-" + roomName;
          recorderCaller = new RecorderEndpoint.Builder(pipeline, RECORDING_PATH + preConvertedName + RECORDING_EXT).
              withMediaProfile(MediaProfileSpecType.MP4).build();
          outgoingMedia.connect(recorderCaller);
          recorderCaller.record();
          // END recording code
          pendingConversion = true;
        }
        else {
          recorderCaller.stop();
          recorderCaller.release();
          //convert
          if (pendingConversion == true) {
            log.info("Run Conversion");
            Runtime.getRuntime().exec("ffmpeg -i " + RECORDING_PATH + preConvertedName + RECORDING_EXT + " -qscale 0 " + RECORDING_PATH + preConvertedName + ".mp4");
            //Runtime.getRuntime().exec("ffmpeg -i" + RECORDING_PATH + preConvertedName + RECORDING_EXT + "-profile:v baseline -level 3.0 -s 1280x960 -start_number 0 -hls_time 10 -hls_list_size 0 -f hls " + RECORDING_PATH + preConvertedName + ".m3u8");
          }
          pendingConversion = false
        }
      }
    });
  }

  public WebRtcEndpoint getOutgoingWebRtcPeer() {
    return outgoingMedia;
  }

  public boolean getIsTeacher(){
    return isTeacher;
  }

  public void setIsTeacher(boolean isTeacher){
    this.isTeacher = isTeacher;
  }

  public String getName() {
    return name;
  }

  public WebSocketSession getSession() {
    return session;
  }

  /**
   * The room to which the user is currently attending.
   *
   * @return The room
   */
  public String getRoomName() {
    return this.roomName;
  }

  public void receiveVideoFrom(UserSession sender, String sdpOffer) throws IOException {
    log.info("USER {}: connecting with {} in room {}", this.name, sender.getName(), this.roomName);

    log.trace("USER {}: SdpOffer for {} is {}", this.name, sender.getName(), sdpOffer);

    final String ipSdpAnswer = this.getEndpointForUser(sender).processOffer(sdpOffer);
    final JsonObject scParams = new JsonObject();
    scParams.addProperty("id", "receiveVideoAnswer");
    scParams.addProperty("name", sender.getName());
    scParams.addProperty("sdpAnswer", ipSdpAnswer);

    log.trace("USER {}: SdpAnswer for {} is {}", this.name, sender.getName(), ipSdpAnswer);
    this.sendMessage(scParams);
    log.debug("gather candidates");
    this.getEndpointForUser(sender).gatherCandidates();
  }

  private WebRtcEndpoint getEndpointForUser(final UserSession sender) {
    if (sender.getName().equals(name)) {
      log.debug("PARTICIPANT {}: configuring loopback", this.name);
      return outgoingMedia;
    }

    log.debug("PARTICIPANT {}: receiving video from {}", this.name, sender.getName());

    WebRtcEndpoint incoming = incomingMedia.get(sender.getName());
    if (incoming == null) {
      log.debug("PARTICIPANT {}: creating new endpoint for {}", this.name, sender.getName());
      incoming = new WebRtcEndpoint.Builder(pipeline).build();

      incoming.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

        @Override
        public void onEvent(IceCandidateFoundEvent event) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "iceCandidate");
          response.addProperty("name", sender.getName());
          response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
          try {
            synchronized (session) {
              session.sendMessage(new TextMessage(response.toString()));
            }
          } catch (IOException e) {
            log.debug(e.getMessage());
          }
        }
      });

      incomingMedia.put(sender.getName(), incoming);
    }

    log.debug("PARTICIPANT {}: obtained endpoint for {}", this.name, sender.getName());
    sender.getOutgoingWebRtcPeer().connect(incoming);

    return incoming;
  }

  public void cancelVideoFrom(final UserSession sender) {
    this.cancelVideoFrom(sender.getName());
  }

  public void cancelVideoFrom(final String senderName) {
    log.debug("PARTICIPANT {}: canceling video reception from {}", this.name, senderName);
    final WebRtcEndpoint incoming = incomingMedia.remove(senderName);

    log.debug("PARTICIPANT {}: removing endpoint for {}", this.name, senderName);
    
    if (incoming != null) {
      incoming.release(new Continuation<Void>() {
        @Override
        public void onSuccess(Void result) throws Exception {
          log.trace("PARTICIPANT {}: Released successfully incoming EP for {}",
              UserSession.this.name, senderName);
        }

        @Override
        public void onError(Throwable cause) throws Exception {
          log.warn("PARTICIPANT {}: Could not release incoming EP for {}", UserSession.this.name,
              senderName);
        }
      });
    }
  }

  @Override
  public void close() throws IOException {
    log.debug("PARTICIPANT {}: Releasing resources", this.name);
    for (final String remoteParticipantName : incomingMedia.keySet()) {

      log.trace("PARTICIPANT {}: Released incoming EP for {}", this.name, remoteParticipantName);

      final WebRtcEndpoint ep = this.incomingMedia.get(remoteParticipantName);

      ep.release(new Continuation<Void>() {

        @Override
        public void onSuccess(Void result) throws Exception {
          log.trace("PARTICIPANT {}: Released successfully incoming EP for {}",
              UserSession.this.name, remoteParticipantName);
        }

        @Override
        public void onError(Throwable cause) throws Exception {
          log.warn("PARTICIPANT {}: Could not release incoming EP for {}", UserSession.this.name,
              remoteParticipantName);
        }
      });
    }

    outgoingMedia.release(new Continuation<Void>() {

      @Override
      public void onSuccess(Void result) throws Exception {
        log.trace("PARTICIPANT {}: Released outgoing EP", UserSession.this.name);
      }

      @Override
      public void onError(Throwable cause) throws Exception {
        log.warn("USER {}: Could not release outgoing EP", UserSession.this.name);
      }
    });
    log.info("USER {}: END recording in room {}", name, roomName);
  }

  public void clearRecording() {
    log.info("USER {}: END recording in room {}", name, roomName);
    recorderCaller.stop();
    recorderCaller.release();
    // conversion
    if (pendingConversion == true) {
      log.info("Run Conversion");
      Runtime.getRuntime().exec("ffmpeg -i " + RECORDING_PATH + preConvertedName + RECORDING_EXT + " -qscale 0 " + RECORDING_PATH + preConvertedName + ".mp4");
    }
    pendingConversion = false;
  }

  public void sendMessage(JsonObject message) throws IOException {
    log.debug("USER {}: Sending message {}", name, message);
    synchronized (session) {
      session.sendMessage(new TextMessage(message.toString()));
    }
  }

  public void addCandidate(IceCandidate candidate, String name) {
    if (this.name.compareTo(name) == 0) {
      outgoingMedia.addIceCandidate(candidate);
    } else {
      WebRtcEndpoint webRtc = incomingMedia.get(name);
      if (webRtc != null) {
        webRtc.addIceCandidate(candidate);
      }
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see java.lang.Object#equals(java.lang.Object)
   */
  @Override
  public boolean equals(Object obj) {

    if (this == obj) {
      return true;
    }
    if (obj == null || !(obj instanceof UserSession)) {
      return false;
    }
    UserSession other = (UserSession) obj;
    boolean eq = name.equals(other.name);
    eq &= roomName.equals(other.roomName);
    return eq;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.lang.Object#hashCode()
   */
  @Override
  public int hashCode() {
    int result = 1;
    result = 31 * result + name.hashCode();
    result = 31 * result + roomName.hashCode();
    return result;
  }
}

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

package org.kurento.tutorial.one2manycall;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.IceCandidateFoundEvent;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.client.RecorderEndpoint;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Protocol handler for 1 to N video call communication.
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 5.0.0
 */
public class CallHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(CallHandler.class);
  private static final Gson gson = new GsonBuilder().create();

  private final ConcurrentHashMap<String, UserSession> students = new ConcurrentHashMap<>();

  @Autowired
  private KurentoClient kurento;

  private MediaPipeline pipeline;
  private UserSession teacherUserSession;
  private RecorderEndpoint recorderCaller;
  private static final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-S");
  public static final String RECORDING_PATH = "file:///tmp/" + df.format(new Date()) + "-";
  public static final String RECORDING_EXT = ".webm";

  @Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);
    log.debug("Incoming message from session '{}': {}", session.getId(), jsonMessage);

    switch (jsonMessage.get("id").getAsString()) {
      case "addTeacher":
        try {
          addTeacher(session, jsonMessage);
        } catch (Throwable t) {
          handleErrorResponse(t, session, "addTeacherResponse");
        }
        break;
      case "addStudent":
        try {
          addStudent(session, jsonMessage);
        } catch (Throwable t) {
          handleErrorResponse(t, session, "addStudentResponse");
        }
        break;
      case "onIceCandidate": {
        JsonObject candidate = jsonMessage.get("candidate").getAsJsonObject();

        UserSession user = null;
        if (teacherUserSession != null) {
          if (teacherUserSession.getSession() == session) {
            user = teacherUserSession;
          } else {
            user = students.get(session.getId());
          }
        }
        if (user != null) {
          IceCandidate cand =
              new IceCandidate(candidate.get("candidate").getAsString(), candidate.get("sdpMid")
                  .getAsString(), candidate.get("sdpMLineIndex").getAsInt());
          user.addCandidate(cand);
        }
        break;
      }
      case "stop":
        stop(session);
        break;
      default:
        break;
    }
  }

  private void handleErrorResponse(Throwable throwable, WebSocketSession session, String responseId)
      throws IOException {
    stop(session);
    log.error(throwable.getMessage(), throwable);
    JsonObject response = new JsonObject();
    response.addProperty("id", responseId);
    response.addProperty("response", "rejected");
    response.addProperty("message", throwable.getMessage());
    session.sendMessage(new TextMessage(response.toString()));
  }

  private synchronized void addTeacher(final WebSocketSession session, JsonObject jsonMessage)
      throws IOException {
    if (teacherUserSession == null) {
      teacherUserSession = new UserSession(session);

      pipeline = kurento.createMediaPipeline();
      teacherUserSession.setWebRtcEndpoint(new WebRtcEndpoint.Builder(pipeline).build());

      WebRtcEndpoint teacherWebRtc = teacherUserSession.getWebRtcEndpoint();

      teacherWebRtc.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

        @Override
        public void onEvent(IceCandidateFoundEvent event) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "iceCandidate");
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

      String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();
      String sdpAnswer = teacherWebRtc.processOffer(sdpOffer);

      JsonObject response = new JsonObject();
      response.addProperty("id", "addTeacherResponse");
      response.addProperty("response", "accepted");
      response.addProperty("sdpAnswer", sdpAnswer);

      synchronized (session) {
        teacherUserSession.sendMessage(response);
      }
      teacherWebRtc.gatherCandidates();
      recorderCaller = new RecorderEndpoint.Builder(pipeline, RECORDING_PATH + session.getId() + RECORDING_EXT)
        .build();
      teacherWebRtc.connect(recorderCaller);
      //recorderCaller.record();

    } else {
      JsonObject response = new JsonObject();
      response.addProperty("id", "addTeacherResponse");
      response.addProperty("response", "rejected");
      response.addProperty("message",
          "Another user is currently acting as sender. Try again later ...");
      session.sendMessage(new TextMessage(response.toString()));
    }
  }

  private synchronized void addStudent(final WebSocketSession session, JsonObject jsonMessage)
      throws IOException {
    if (teacherUserSession == null || teacherUserSession.getWebRtcEndpoint() == null) {
      JsonObject response = new JsonObject();
      response.addProperty("id", "addStudentResponse");
      response.addProperty("response", "rejected");
      response.addProperty("message",
          "No active sender now. Become sender or . Try again later ...");
      session.sendMessage(new TextMessage(response.toString()));
    } else {
      if (students.containsKey(session.getId())) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "addStudentResponse");
        response.addProperty("response", "rejected");
        response.addProperty("message", "You are already viewing in this session. "
            + "Use a different browser to add additional students.");
        session.sendMessage(new TextMessage(response.toString()));
        return;
      }
      UserSession student = new UserSession(session);
      students.put(session.getId(), student);

      WebRtcEndpoint nextWebRtc = new WebRtcEndpoint.Builder(pipeline).build();

      nextWebRtc.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

        @Override
        public void onEvent(IceCandidateFoundEvent event) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "iceCandidate");
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

      student.setWebRtcEndpoint(nextWebRtc);
      teacherUserSession.getWebRtcEndpoint().connect(nextWebRtc);

      WebRtcEndpoint studTeachWebRtc = new WebRtcEndpoint.Builder(pipeline).build();
      teacherUserSession.setWebRtcEndpoint(studTeachWebRtc);
      student.getWebRtcEndpoint().connect(studTeachWebRtc);

      String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();
      String sdpAnswer = nextWebRtc.processOffer(sdpOffer);

      JsonObject response = new JsonObject();
      response.addProperty("id", "addStudentResponse");
      response.addProperty("response", "accepted");
      response.addProperty("sdpAnswer", sdpAnswer);

      synchronized (session) {
        student.sendMessage(response);
      }
      nextWebRtc.gatherCandidates();
    }
  }

  private synchronized void stop(WebSocketSession session) throws IOException {
    String sessionId = session.getId();
    if (teacherUserSession != null && teacherUserSession.getSession().getId().equals(sessionId)) {
      for (UserSession student : students.values()) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "stopCommunication");
        student.sendMessage(response);
      }

      log.info("Releasing media pipeline");
      if (pipeline != null) {
        pipeline.release();
      }
      pipeline = null;
      teacherUserSession = null;
    } else if (students.containsKey(sessionId)) {
      if (students.get(sessionId).getWebRtcEndpoint() != null) {
        students.get(sessionId).getWebRtcEndpoint().release();
      }
      students.remove(sessionId);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    stop(session);
  }

}

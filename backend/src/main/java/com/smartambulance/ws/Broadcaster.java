package com.smartambulance.ws;

import com.smartambulance.domain.EmergencyRequest;
import com.smartambulance.dto.Views.FleetItem;
import com.smartambulance.dto.Views.RequestView;
import com.smartambulance.sim.RequestViewFactory;
import com.smartambulance.sim.SignalService.SignalState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Real-time fan-out over WebSocket (STOMP). Also the notification service: in-app toasts plus a simulated SMS log. */
@Component
public class Broadcaster {
    private static final Logger log = LoggerFactory.getLogger(Broadcaster.class);

    public record Event(String type, Object payload) {}

    public record Notice(String level, String title, String body) {}

    private final SimpMessagingTemplate ws;
    private final RequestViewFactory views;

    public Broadcaster(SimpMessagingTemplate ws, RequestViewFactory views) {
        this.ws = ws;
        this.views = views;
    }

    /** Pushes the full snapshot of a request to everyone who follows it. */
    public RequestView pushRequest(EmergencyRequest r) {
        RequestView v = views.build(r);
        Event e = new Event("REQUEST", v);
        ws.convertAndSend("/topic/request/" + r.getId(), v);
        ws.convertAndSend("/topic/police", e);
        if (r.getHospitalId() != null) ws.convertAndSend("/topic/hospital/" + r.getHospitalId(), e);
        if (r.getAmbulanceId() != null) ws.convertAndSend("/topic/ambulance/" + r.getAmbulanceId(), e);
        return v;
    }

    public void police(String type, Object payload) {
        ws.convertAndSend("/topic/police", new Event(type, payload));
    }

    public void hospital(long hospitalId, String type, Object payload) {
        ws.convertAndSend("/topic/hospital/" + hospitalId, new Event(type, payload));
    }

    public void ambulance(long ambulanceId, String type, Object payload) {
        ws.convertAndSend("/topic/ambulance/" + ambulanceId, new Event(type, payload));
    }

    public void signals(List<SignalState> states) {
        ws.convertAndSend("/topic/signals", states);
    }

    public void fleet(List<FleetItem> items) {
        ws.convertAndSend("/topic/fleet", items);
    }

    /** In-app notification (toast) to one user. */
    public void notifyUser(Long userId, String level, String title, String body) {
        if (userId == null) return;
        ws.convertAndSendToUser(String.valueOf(userId), "/queue/notifications", new Notice(level, title, body));
    }

    /** Alert pushed to a road user. */
    public void alertUser(long userId, Event event) {
        ws.convertAndSendToUser(String.valueOf(userId), "/queue/alerts", event);
    }

    /** Stand-in for an SMS / push gateway (e.g. Firebase Cloud Messaging, Twilio). Replace the body to integrate one. */
    public void sms(String phone, String text) {
        if (phone != null && !phone.isBlank()) log.info("SMS (simulated) to {}: {}", phone, text);
    }
}

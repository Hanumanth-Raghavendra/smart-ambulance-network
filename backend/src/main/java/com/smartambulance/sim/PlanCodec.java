package com.smartambulance.sim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartambulance.domain.EmergencyRequest;
import com.smartambulance.geo.RoutePlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Stores the two route legs of a trip as JSON on the request row. */
@Component
public class PlanCodec {
    private final ObjectMapper mapper;

    public PlanCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<RoutePlan> read(EmergencyRequest r) {
        if (r.getRouteJson() == null || r.getRouteJson().isBlank()) return new ArrayList<>();
        try {
            return mapper.readValue(r.getRouteJson(), new TypeReference<List<RoutePlan>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt route data for request " + r.getId(), e);
        }
    }

    public void write(EmergencyRequest r, List<RoutePlan> legs) {
        try {
            r.setRouteJson(mapper.writeValueAsString(legs));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The plan of the leg the request is currently on, or null if not dispatched yet. */
    public RoutePlan current(EmergencyRequest r) {
        List<RoutePlan> legs = read(r);
        int i = r.getLeg().ordinal();
        return legs.size() > i ? legs.get(i) : null;
    }
}

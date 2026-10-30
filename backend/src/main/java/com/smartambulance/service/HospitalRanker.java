package com.smartambulance.service;

import com.smartambulance.config.AppProperties;
import com.smartambulance.domain.Enums.Severity;
import com.smartambulance.domain.Hospital;
import com.smartambulance.dto.Views.RankedHospital;
import com.smartambulance.repo.Repos.HospitalRepo;
import com.smartambulance.sim.RoadNetwork;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Hospital ranking. Lower score is better:
 * <pre>
 * score = w_travel * severityMultiplier * travelMinutes
 *       + w_speciality * (speciality missing ? 1 : 0)
 *       + w_beds * (1 - bedsAvailable / totalBeds)
 * </pre>
 * Hospitals without a free bed (or already refused) are skipped. Critical patients weigh travel time more.
 */
@Component
public class HospitalRanker {
    private final HospitalRepo hospitals;
    private final RoadNetwork network;
    private final AppProperties props;

    public HospitalRanker(HospitalRepo hospitals, RoadNetwork network, AppProperties props) {
        this.hospitals = hospitals;
        this.network = network;
        this.props = props;
    }

    public double severityMultiplier(Severity s) {
        var m = props.ranking().severityMultiplier();
        return switch (s) {
            case MINOR -> m.minor();
            case SERIOUS -> m.serious();
            case CRITICAL -> m.critical();
        };
    }

    public double score(double travelMinutes, boolean specialityMatch, int beds, int totalBeds, Severity sev) {
        var w = props.ranking();
        double bedFraction = totalBeds <= 0 ? 0 : (double) beds / totalBeds;
        return w.wTravel() * severityMultiplier(sev) * travelMinutes
                + (specialityMatch ? 0 : w.wSpeciality())
                + w.wBeds() * (1 - bedFraction);
    }

    public List<RankedHospital> rank(double lat, double lng, Severity sev, String speciality, Set<Long> excluded) {
        return hospitals.findByActiveTrue().stream()
                .filter(h -> h.getBedsAvailable() > 0 && !excluded.contains(h.getId()))
                .map(h -> toRanked(h, lat, lng, sev, speciality))
                .sorted(Comparator.comparingDouble(RankedHospital::score))
                .toList();
    }

    private RankedHospital toRanked(Hospital h, double lat, double lng, Severity sev, String speciality) {
        double minutes = network.route("ESTIMATE", lat, lng, h.getLat(), h.getLng()).baseSeconds / 60.0;
        boolean match = h.hasSpeciality(speciality);
        double score = score(minutes, match, h.getBedsAvailable(), h.getTotalBeds(), sev);
        return new RankedHospital(h.getId(), h.getName(), round(minutes), match, h.getBedsAvailable(), h.getTotalBeds(), round(score));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

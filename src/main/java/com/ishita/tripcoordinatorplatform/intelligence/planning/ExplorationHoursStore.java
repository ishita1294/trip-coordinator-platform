package com.ishita.tripcoordinatorplatform.intelligence.planning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ExplorationHoursStore {
    public record Saved(ExplorationHours hours,long version) {}
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public ExplorationHoursStore(JdbcTemplate jdbc,JsonMapper json) {this.jdbc=jdbc;this.json=json;}
    public Saved read(Long trip,Long user) {
        return jdbc.query("select preferences::text,version from explore_personal_hours where trip_id=? and user_id=?",(rs,n)->new Saved(decode(rs.getString(1)),rs.getLong(2)),trip,user).stream().findFirst().orElse(new Saved(ExplorationHours.defaults(),0));
    }
    public static ExplorationHours decode(String value) {
        return value==null?ExplorationHours.defaults():JsonMapper.builder().build().readValue(value,ExplorationHours.class);
    }
    @Transactional
    public Saved save(Long trip,Long actor,Saved requested) {
        if(requested==null || requested.hours()==null || requested.version()<0) throw new IllegalArgumentException("Supply exploration hours and their current version.");
        var dates=jdbc.query("select t.start_date,t.end_date from trip_members m join trip t on t.id=m.trip_id where m.trip_id=? and m.user_id=? for update of m",(rs,n)->new java.time.LocalDate[]{rs.getObject(1,java.time.LocalDate.class),rs.getObject(2,java.time.LocalDate.class)},trip,actor);
        if(dates.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Trip not found");
        if(requested.hours().overrides().keySet().stream().anyMatch(d->d.isBefore(dates.getFirst()[0]) || d.isAfter(dates.getFirst()[1]))) throw new IllegalArgumentException("Overrides must start on a trip date; the final day may explicitly end the next day.");
        for(var date=dates.getFirst()[0];!date.isAfter(dates.getFirst()[1]);date=date.plusDays(1)) {
            var day=requested.hours().day(date);String zone=day.timeZone()==null?requested.hours().timeZone():day.timeZone();
            if(zone!=null) {
                var rules=java.time.ZoneId.of(zone).getRules();
                if(rules.getValidOffsets(date.atTime(day.dayStart())).size()!=1 || rules.getValidOffsets(date.plusDays(day.endDayOffset()).atTime(day.dayEnd())).size()!=1)
                    throw new IllegalArgumentException("Exploration hours on "+date+" contain an ambiguous or nonexistent local time. Use a day override.");
            }
        }
        int changed=requested.version()==0
            ? jdbc.update("insert into explore_personal_hours(trip_id,user_id,preferences,version) values(?,?,cast(? as jsonb),1) on conflict(trip_id,user_id) do nothing",trip,actor,json.writeValueAsString(requested.hours()))
            : jdbc.update("update explore_personal_hours set preferences=cast(? as jsonb),version=version+1,updated_at=now() where trip_id=? and user_id=? and version=?",json.writeValueAsString(requested.hours()),trip,actor,requested.version());
        if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Exploration hours changed. Reload before saving.");
        return new Saved(requested.hours(),requested.version()+1);
    }
}

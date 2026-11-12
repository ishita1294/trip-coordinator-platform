package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.*;
import static com.ishita.tripcoordinatorplatform.ai.document.BookingFact.Status.CLEAR;

/** Documentary lifecycle claims, not an application reservation status or confirmation decision. */
public final class ReadingBookingLifecycle {
    private ReadingBookingLifecycle() {}
    public enum State { ACTIVE, CANCELLED, SUPERSEDED, PROVISIONAL, UNKNOWN }
    /** The containing booking replaces this index. A printed reference is optional corroboration, never a lookup heuristic. */
    public record BookingAssociation(Integer bookingIndex, String bookingReference) {}
    public static final int MAX_REPLACEMENTS = 16;

    static ReadingFact<State> unestablished() {
        return new ReadingFact<>(BookingFact.Status.MISSING,null,List.of(),List.of(),
                "Documentary booking validity was not established by this historical contract",List.of(),
                new ReadingFact.Interpretation(BookingFact.Basis.NOT_ESTABLISHED,List.of(),null));
    }
    static void validate(TourReading extraction, TourReadingValidator validator) {
        Map<Integer,List<Integer>> edges=new HashMap<>();
        for(var b:extraction.bookings()) {
            require(b.lifecycle()!=null && b.replaces()!=null,"Lifecycle fact and replacement list are required");
            var fields=validator.fields(b);
            validateLocalFact(b.lifecycle(),b.bookingIndex(),fields,validator);
            require(b.lifecycle().status()!=CLEAR || b.lifecycle().value()!=State.UNKNOWN,
                    "Unknown lifecycle must remain unresolved, never CLEAR");
            require(b.replaces().size()<=MAX_REPLACEMENTS,"Replacement association bound exceeded");
            Set<Integer> selected=new HashSet<>();
            Set<ReadingFact<BookingAssociation>> entries=new HashSet<>();
            for(var f:b.replaces()) {
                require(f!=null && entries.add(f),"Duplicate replacement fact");
                validateLocalFact(f,b.bookingIndex(),fields,validator);
                if(f.value()!=null)validateTarget(f.value(),b,extraction);
                Set<Integer> alternatives=new HashSet<>();
                for(var alternative:f.alternatives()) {
                    validateTarget(alternative,b,extraction);
                    require(alternatives.add(alternative.bookingIndex()),"Replacement alternatives must identify distinct bookings");
                }
                if(f.status()==CLEAR) {
                    int target=f.value().bookingIndex();
                    require(selected.add(target),"Duplicate replacement target");
                    edges.computeIfAbsent(b.bookingIndex(),ignored->new ArrayList<>()).add(target);
                }
            }
        }
        validateEffectiveStates(extraction,Map.of());
        Set<Integer> done=new HashSet<>();
        for(int index:edges.keySet())visit(index,edges,new HashSet<>(),done);
    }
    /** Reuse source relationships against separate user declarations; never fabricate AI facts/evidence. */
    public static void validateEffectiveStates(TourReading extraction,Map<Integer,State> userStates) {
        for(var e:userStates.entrySet())require(e.getKey()!=null && e.getKey()>=0 && e.getKey()<extraction.bookings().size()
                && e.getValue()!=null,"Invalid lifecycle clarification association");
        for(var b:extraction.bookings())for(var relation:b.replaces())if(relation.status()==CLEAR){
            int target=relation.value().bookingIndex();
            var prior=extraction.bookings().get(target).lifecycle();
            var effective=userStates.getOrDefault(target,prior.status()==CLEAR?prior.value():State.UNKNOWN);
            require(effective==State.CANCELLED || effective==State.SUPERSEDED,
                    "Established replacement requires an established inactive predecessor");
        }
    }
    private static void validateLocalFact(ReadingFact<?> f,int index,
            Map<ReadingFact.Reference,ReadingFact<?>> fields,TourReadingValidator validator) {
        validator.validateFact(f,fields);
        require(f.interpretation().calculation()==null,"Lifecycle and replacement facts are not arithmetic results");
        for(var e:f.evidence())require(e.bookingIndex()!=null && e.bookingIndex()==index,
                "Lifecycle/replacement evidence must identify the containing booking");
    }
    private static void validateTarget(BookingAssociation link,TourReading.Booking owner,TourReading extraction) {
        require(link!=null && link.bookingIndex()!=null && link.bookingIndex()>=0
                        && link.bookingIndex()<extraction.bookings().size() && link.bookingIndex()!=owner.bookingIndex(),
                "Invalid, dangling or self-referential replacement association");
        if(link.bookingReference()!=null) {
            var reference=extraction.bookings().get(link.bookingIndex()).bookingReference();
            require(!link.bookingReference().isBlank() && reference.status()==CLEAR
                            && link.bookingReference().equals(reference.value()),"Replacement index/reference disagreement");
        }
    }
    private static void visit(int index,Map<Integer,List<Integer>> edges,Set<Integer> path,Set<Integer> done) {
        if(done.contains(index))return;
        require(path.add(index),"Cyclic replacement associations");
        for(int target:edges.getOrDefault(index,List.of()))visit(target,edges,path,done);
        path.remove(index);done.add(index);
    }
    private static void require(boolean valid,String message) {
        if(!valid)throw new InvalidDocumentExtractionException(message);
    }
}

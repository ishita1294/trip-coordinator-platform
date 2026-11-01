package com.ishita.tripcoordinatorplatform.request;
import com.ishita.tripcoordinatorplatform.model.Location;
import jakarta.validation.Valid;
/** The location is an explicit booking assertion, not a cache of provider content. */
public record ActivityEndingLocationRequest(String choice, @Valid Location location, String placeId, Long version) {
    public void validate() {
        if(!java.util.Set.of("UNKNOWN","SAME_AS_MEETING_POINT","LOCATION").contains(choice==null?"":choice)) throw new IllegalArgumentException("Choose an unknown endpoint, the meeting point, or a separate ending location.");
        if(version!=null && version<0) throw new IllegalArgumentException("Invalid endpoint version.");
        if("UNKNOWN".equals(choice) && (location!=null || placeId!=null) || "SAME_AS_MEETING_POINT".equals(choice) && location!=null) throw new IllegalArgumentException("Do not supply a separate location for this choice.");
        if("LOCATION".equals(choice) && (location==null || (location.getName()==null || location.getName().isBlank()) && (location.getAddress()==null || location.getAddress().isBlank()))) throw new IllegalArgumentException("Name or address is required for the ending location.");
        if(location!=null && (!location.isCoordinatePairValid() || location.getLatitude()!=null && (location.getLatitude().abs().compareTo(java.math.BigDecimal.valueOf(90))>0 || location.getLongitude().abs().compareTo(java.math.BigDecimal.valueOf(180))>0))) throw new IllegalArgumentException("Invalid ending-location coordinates.");
        if(placeId!=null && (!placeId.matches("[A-Za-z0-9_-]{1,255}") || "LOCATION".equals(choice) && (location==null || location.getLatitude()==null))) throw new IllegalArgumentException("A selected place needs a valid identity and coordinates.");
        if(location!=null && ((location.getName()!=null && location.getName().length()>255) || (location.getAddress()!=null && location.getAddress().length()>1000))) throw new IllegalArgumentException("Ending-location text is too long.");
    }
}

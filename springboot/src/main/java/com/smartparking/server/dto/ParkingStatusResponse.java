package com.smartparking.server.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

@Data
public class ParkingStatusResponse {
    
    @JsonProperty("last_update")
    private Double lastUpdate;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private Map<String, PartitionData> partitions = new HashMap<>();

    @JsonAnySetter
    public void addPartition(String key, PartitionData value) {
        if (!key.equals("last_update")) {
            this.partitions.put(key, value);
        }
    }

    @com.fasterxml.jackson.annotation.JsonAnyGetter
    public Map<String, PartitionData> getPartitions() {
        return partitions;
    }
    

    @Data
    public static class PartitionData {
        private SummaryData summary;
        private List<SlotData> slots;
    }

    @Data
    public static class SummaryData {
        private int total;
        private int available;
        @JsonProperty("disabled_available")
        private int disabledAvailable;
    }

    @Data
    public static class SlotData {
        @JsonProperty("slot_id")
        private int slotId;
        private String type;
        private String status;
        private List<Double> center;
    }
}

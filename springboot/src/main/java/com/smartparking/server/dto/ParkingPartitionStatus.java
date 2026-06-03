package com.smartparking.server.dto;

import lombok.Data;
import java.util.List;

@Data
public class ParkingPartitionStatus {
    private List<Integer> occupied_slots;
    private List<Integer> available_slots;
    private List<Integer> disabled_slots;
    private Integer total_available;
}
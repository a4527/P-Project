package com.smartparking.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "saved_parking_locations")
public class SavedParkingLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parking_lot_id")
    private ParkingLot parkingLot;

    @Column(nullable = false)
    private Integer slotId;

    @Column(length = 100)
    private String vehicleLabel;

    @Column(length = 500)
    private String memo;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private LocalDateTime savedAt;

    private LocalDateTime releasedAt;
}

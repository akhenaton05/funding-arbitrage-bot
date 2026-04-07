package ru.dto.db.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "trades")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Trade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "position_id", nullable = false)
    private String positionId;

    @Column(name = "ticker", nullable = false)
    private String ticker;

    @Column(name = "exchange", nullable = false)
    private String exchange;

    @Column(name = "volume")
    private Double volume;

    @Column(name = "funding")
    private Double funding;

    @Column(name = "pnl", nullable = false)
    private Double pnl;

    @Column(name = "opened_rate")
    private double openedFundingRate;

    @Column(name = "closed_rate")
    private double closedFindingRate;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "closed_at", nullable = false)
    private LocalDateTime closedAt;
}

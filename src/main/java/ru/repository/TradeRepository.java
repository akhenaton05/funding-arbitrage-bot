package ru.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.dto.db.model.Trade;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {

    List<Trade> findByClosedAtBetween(LocalDateTime from, LocalDateTime to);

    // Для подсчёта суммарного PnL за период
    @Query("SELECT SUM(t.pnl) FROM Trade t WHERE t.closedAt BETWEEN :from AND :to")
    Double sumPnlBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // Для статистики по тикеру
    List<Trade> findByTickerAndClosedAtBetween(String ticker, LocalDateTime from, LocalDateTime to);

    List<Trade> findByClosedAtAfter(LocalDateTime from);
}

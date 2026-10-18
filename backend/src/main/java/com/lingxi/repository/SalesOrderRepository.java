package com.lingxi.repository;

import com.lingxi.model.SalesOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface SalesOrderRepository extends JpaRepository<SalesOrderEntity, Long> {
    List<SalesOrderEntity> findByQuarterOrderByAmountDesc(String quarter);

    List<SalesOrderEntity> findByQuarterAndRegionOrderByAmountDesc(String quarter, String region);

    @Query("select coalesce(sum(s.amount), 0) from SalesOrderEntity s where s.quarter = :quarter")
    BigDecimal totalByQuarter(@Param("quarter") String quarter);

    @Query("select coalesce(sum(s.amount), 0) from SalesOrderEntity s where s.quarter = :quarter and s.region = :region")
    BigDecimal totalByQuarterAndRegion(@Param("quarter") String quarter, @Param("region") String region);

    @Query("""
            select s.region as region, sum(s.amount) as total, count(s.id) as orderCount, avg(s.amount) as avgAmount
            from SalesOrderEntity s
            where s.quarter = :quarter
            group by s.region
            order by sum(s.amount) desc
            """)
    List<RegionAggregate> aggregateByQuarter(@Param("quarter") String quarter);

    @Query("""
            select s.region as region, sum(s.amount) as total
            from SalesOrderEntity s
            where s.quarter = :quarter
            group by s.region
            """)
    List<RegionTotal> totalsByQuarter(@Param("quarter") String quarter);

    interface RegionAggregate {
        String getRegion();

        BigDecimal getTotal();

        Long getOrderCount();

        BigDecimal getAvgAmount();
    }

    interface RegionTotal {
        String getRegion();

        BigDecimal getTotal();
    }
}

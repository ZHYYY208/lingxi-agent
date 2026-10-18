package com.lingxi.service;

import com.lingxi.model.SalesOrderEntity;
import com.lingxi.rag.TextTools;
import com.lingxi.repository.SalesOrderRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DatabaseTool {
    private static final List<String> REGIONS = List.of("华东", "华北", "华南", "西南", "华中", "东北", "西北");
    private static final Map<String, Integer> CN_NUMBERS = Map.of(
            "一", 1, "二", 2, "三", 3, "四", 4, "1", 1, "2", 2, "3", 3, "4", 4
    );
    private final SalesOrderRepository repository;
    private final TextTools textTools;

    public DatabaseTool(SalesOrderRepository repository, TextTools textTools) {
        this.repository = repository;
        this.textTools = textTools;
    }

    public DatabaseResult query(String question) {
        String quarter = extractQuarter(question);
        String region = extractRegion(question);
        boolean wantsRank = textTools.containsAny(question, List.of("排名", "排行", "最高", "最好", "top", "TOP", "哪些区域"));
        boolean wantsTrend = textTools.containsAny(question, List.of("环比", "同比", "趋势", "增长", "对比", "相比"));

        if (region != null) {
            List<SalesOrderEntity> rows = repository.findByQuarterAndRegionOrderByAmountDesc(quarter, region);
            if (rows.isEmpty()) {
                return new DatabaseResult(
                        "经营数据库中未找到 " + quarter + " " + region + " 区的销售记录。",
                        "_没有查询到符合条件的数据。_", List.of(),
                        "sales_orders WHERE quarter=" + quarter + " AND region=" + region, 0
                );
            }
            BigDecimal total = rows.stream().map(SalesOrderEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            String previousQuarter = previousQuarter(quarter);
            BigDecimal previousTotal = repository.totalByQuarterAndRegion(previousQuarter, region);
            double growth = previousTotal.compareTo(BigDecimal.ZERO) == 0
                    ? 0
                    : total.subtract(previousTotal).divide(previousTotal, 6, RoundingMode.HALF_UP).doubleValue() * 100;

            List<List<String>> tableRows = new ArrayList<>();
            List<Map<String, Object>> dataRows = new ArrayList<>();
            for (SalesOrderEntity row : rows) {
                tableRows.add(List.of(
                        row.getOrderNo(), row.getChannel(), row.getProduct(), row.getCustomer(),
                        textTools.formatNumber(row.getAmount(), "元"), row.getOwner()
                ));
                dataRows.add(Map.of(
                        "order_no", row.getOrderNo(), "region", row.getRegion(), "quarter", row.getQuarter(),
                        "channel", row.getChannel(), "product", row.getProduct(), "customer", row.getCustomer(),
                        "amount", row.getAmount().doubleValue()
                ));
            }
            String summary = quarter + " " + region + "区销售额为 **" + textTools.formatNumber(total, "元")
                    + "**，共 " + rows.size() + " 笔订单。";
            if (previousTotal.compareTo(BigDecimal.ZERO) > 0) {
                summary += " 相比上一季度（" + textTools.formatNumber(previousTotal, "元") + "）"
                        + (growth >= 0 ? "增长" : "下降") + " **" + String.format(Locale.ROOT, "%.1f", Math.abs(growth)) + "%**。";
            }
            String table = textTools.tableToMarkdown(
                    List.of("订单号", "渠道", "产品", "客户", "销售额", "负责人"), tableRows
            );
            return new DatabaseResult(summary, table, dataRows,
                    "sales_orders WHERE quarter=" + quarter + " AND region=" + region, rows.size());
        }

        List<SalesOrderRepository.RegionAggregate> aggregates = repository.aggregateByQuarter(quarter);
        if (aggregates.isEmpty()) {
            return new DatabaseResult(
                    "经营数据库中未找到 " + quarter + " 的销售记录。",
                    "_没有查询到符合条件的数据。_", List.of(),
                    "sales_orders WHERE quarter=" + quarter, 0
            );
        }
        BigDecimal total = aggregates.stream().map(SalesOrderRepository.RegionAggregate::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalOrders = aggregates.stream().mapToLong(SalesOrderRepository.RegionAggregate::getOrderCount).sum();
        String previousQuarter = previousQuarter(quarter);
        BigDecimal previousTotal = repository.totalByQuarter(previousQuarter);
        double growth = previousTotal.compareTo(BigDecimal.ZERO) == 0
                ? 0
                : total.subtract(previousTotal).divide(previousTotal, 6, RoundingMode.HALF_UP).doubleValue() * 100;

        String summary = quarter + " 全区域总销售额为 **" + textTools.formatNumber(total, "元")
                + "**，共 " + totalOrders + " 笔订单。";
        if (previousTotal.compareTo(BigDecimal.ZERO) > 0) {
            summary += " 环比上一季度" + (growth >= 0 ? "增长" : "下降") + " **"
                    + String.format(Locale.ROOT, "%.1f", Math.abs(growth)) + "%**。";
        }
        if (wantsRank) {
            SalesOrderRepository.RegionAggregate top = aggregates.getFirst();
            summary += " 其中 **" + top.getRegion() + "区** 表现最好，销售额为 "
                    + textTools.formatNumber(top.getTotal(), "元") + "。";
        }

        List<List<String>> tableRows = new ArrayList<>();
        List<Map<String, Object>> dataRows = new ArrayList<>();
        int index = 1;
        for (SalesOrderRepository.RegionAggregate row : aggregates) {
            double share = total.compareTo(BigDecimal.ZERO) == 0 ? 0
                    : row.getTotal().divide(total, 6, RoundingMode.HALF_UP).doubleValue() * 100;
            tableRows.add(List.of(
                    String.valueOf(index++), row.getRegion(), textTools.formatNumber(row.getTotal(), "元"),
                    String.format(Locale.ROOT, "%.1f%%", share), String.valueOf(row.getOrderCount()),
                    textTools.formatNumber(row.getAvgAmount(), "元")
            ));
            dataRows.add(Map.of(
                    "region", row.getRegion(), "total", row.getTotal().doubleValue(),
                    "order_count", row.getOrderCount(), "avg_amount", row.getAvgAmount().doubleValue()
            ));
        }
        String table = textTools.tableToMarkdown(
                List.of("排名", "区域", "销售额", "占比", "订单数", "平均订单金额"), tableRows
        );
        if (wantsTrend) {
            Map<String, BigDecimal> previous = new HashMap<>();
            for (SalesOrderRepository.RegionTotal row : repository.totalsByQuarter(previousQuarter)) {
                previous.put(row.getRegion(), row.getTotal());
            }
            List<List<String>> trendRows = new ArrayList<>();
            for (SalesOrderRepository.RegionAggregate row : aggregates) {
                BigDecimal before = previous.getOrDefault(row.getRegion(), BigDecimal.ZERO);
                String change = before.compareTo(BigDecimal.ZERO) == 0 ? "—"
                        : String.format(Locale.ROOT, "%+.1f%%",
                        row.getTotal().subtract(before).divide(before, 6, RoundingMode.HALF_UP).doubleValue() * 100);
                trendRows.add(List.of(
                        row.getRegion(), textTools.formatNumber(row.getTotal(), "元"),
                        textTools.formatNumber(before, "元"), change
                ));
            }
            table += "\n\n**环比明细**\n\n" + textTools.tableToMarkdown(
                    List.of("区域", quarter, previousQuarter, "变化"), trendRows
            );
        }
        return new DatabaseResult(summary, table, dataRows,
                "sales_orders GROUP BY region WHERE quarter=" + quarter, aggregates.size());
    }

    private String extractQuarter(String question) {
        Matcher match = Pattern.compile("(20\\d{2})\\s*[-年\\s]*\\s*[Qq]\\s*([1-4])").matcher(question);
        if (match.find()) return match.group(1) + "Q" + match.group(2);
        match = Pattern.compile("(20\\d{2})\\s*年?\\s*第?\\s*([一二三四1-4])\\s*季度").matcher(question);
        if (match.find()) return match.group(1) + "Q" + CN_NUMBERS.get(match.group(2));
        match = Pattern.compile("[Qq]\\s*([1-4])").matcher(question);
        if (match.find()) return "2026Q" + match.group(1);
        match = Pattern.compile("第?\\s*([一二三四1-4])\\s*季度").matcher(question);
        if (match.find()) return "2026Q" + CN_NUMBERS.get(match.group(1));
        return "2026Q3";
    }

    private static String extractRegion(String question) {
        for (String region : REGIONS) {
            if (question.contains(region)) return region;
        }
        return null;
    }

    private static String previousQuarter(String quarter) {
        int year = Integer.parseInt(quarter.substring(0, 4));
        int q = Integer.parseInt(quarter.substring(5));
        return q == 1 ? (year - 1) + "Q4" : year + "Q" + (q - 1);
    }

    public record DatabaseResult(
            String summary,
            String table,
            List<Map<String, Object>> rows,
            String queryDescription,
            int rowCount
    ) {
    }
}

package com.lingxi.config;

import com.lingxi.model.SalesOrderEntity;
import com.lingxi.repository.DocumentRepository;
import com.lingxi.repository.SalesOrderRepository;
import com.lingxi.service.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Component
public class DataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private final SalesOrderRepository salesOrderRepository;
    private final DocumentRepository documentRepository;
    private final KnowledgeService knowledgeService;
    private final LingxiProperties properties;

    public DataSeeder(
            SalesOrderRepository salesOrderRepository,
            DocumentRepository documentRepository,
            KnowledgeService knowledgeService,
            LingxiProperties properties
    ) {
        this.salesOrderRepository = salesOrderRepository;
        this.documentRepository = documentRepository;
        this.knowledgeService = knowledgeService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedSales();
        if (properties.demoSeed() && documentRepository.count() == 0) {
            try {
                int seeded = knowledgeService.seedSamples(false).size();
                log.info("Seeded {} demo knowledge documents", seeded);
            } catch (Exception exc) {
                log.error("Demo document seeding failed", exc);
            }
        }
    }

    private void seedSales() {
        if (salesOrderRepository.count() > 0) return;
        List<SalesOrderEntity> rows = List.of(
                order("SO-2601", "华东", "2026Q3", "直营", "灵犀企业版", "1280000", "上海云杉科技", "周明", LocalDate.of(2026, 9, 26)),
                order("SO-2602", "华东", "2026Q3", "渠道", "灵犀企业版", "860000", "杭州知行数据", "周明", LocalDate.of(2026, 9, 18)),
                order("SO-2603", "华北", "2026Q3", "直营", "灵犀企业版", "1120000", "北京启元集团", "林悦", LocalDate.of(2026, 9, 22)),
                order("SO-2604", "华北", "2026Q3", "渠道", "灵犀标准版", "540000", "天津海河智造", "林悦", LocalDate.of(2026, 8, 30)),
                order("SO-2605", "华南", "2026Q3", "直营", "灵犀企业版", "990000", "深圳前海数科", "陈屿", LocalDate.of(2026, 9, 25)),
                order("SO-2606", "华南", "2026Q3", "渠道", "灵犀标准版", "610000", "广州穗智软件", "陈屿", LocalDate.of(2026, 9, 12)),
                order("SO-2607", "西南", "2026Q3", "直营", "灵犀标准版", "430000", "成都锦官云", "许安", LocalDate.of(2026, 9, 8)),
                order("SO-2608", "华中", "2026Q3", "渠道", "灵犀标准版", "380000", "武汉江城智联", "许安", LocalDate.of(2026, 9, 5)),
                order("SO-2609", "华东", "2026Q2", "直营", "灵犀企业版", "1150000", "上海云杉科技", "周明", LocalDate.of(2026, 6, 27)),
                order("SO-2610", "华东", "2026Q2", "渠道", "灵犀企业版", "720000", "杭州知行数据", "周明", LocalDate.of(2026, 6, 16)),
                order("SO-2611", "华北", "2026Q2", "直营", "灵犀企业版", "930000", "北京启元集团", "林悦", LocalDate.of(2026, 6, 21)),
                order("SO-2612", "华北", "2026Q2", "渠道", "灵犀标准版", "460000", "天津海河智造", "林悦", LocalDate.of(2026, 5, 29)),
                order("SO-2613", "华南", "2026Q2", "直营", "灵犀企业版", "870000", "深圳前海数科", "陈屿", LocalDate.of(2026, 6, 24)),
                order("SO-2614", "华南", "2026Q2", "渠道", "灵犀标准版", "520000", "广州穗智软件", "陈屿", LocalDate.of(2026, 6, 10)),
                order("SO-2615", "西南", "2026Q2", "直营", "灵犀标准版", "360000", "成都锦官云", "许安", LocalDate.of(2026, 6, 6)),
                order("SO-2616", "华中", "2026Q2", "渠道", "灵犀标准版", "310000", "武汉江城智联", "许安", LocalDate.of(2026, 6, 3)),
                order("SO-2617", "华东", "2026Q1", "直营", "灵犀企业版", "980000", "上海云杉科技", "周明", LocalDate.of(2026, 3, 26)),
                order("SO-2618", "华北", "2026Q1", "直营", "灵犀企业版", "810000", "北京启元集团", "林悦", LocalDate.of(2026, 3, 20)),
                order("SO-2619", "华南", "2026Q1", "直营", "灵犀企业版", "760000", "深圳前海数科", "陈屿", LocalDate.of(2026, 3, 19))
        );
        salesOrderRepository.saveAll(rows);
        log.info("Seeded {} demo sales orders", rows.size());
    }

    private static SalesOrderEntity order(String orderNo, String region, String quarter, String channel,
                                          String product, String amount, String customer, String owner, LocalDate date) {
        SalesOrderEntity row = new SalesOrderEntity();
        row.setOrderNo(orderNo);
        row.setRegion(region);
        row.setQuarter(quarter);
        row.setChannel(channel);
        row.setProduct(product);
        row.setAmount(new BigDecimal(amount));
        row.setCustomer(customer);
        row.setOwner(owner);
        row.setOrderDate(date);
        return row;
    }
}

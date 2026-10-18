package com.lingxi.rag;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import com.huaban.analysis.jieba.WordDictionary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ChineseTokenizer {
    private static final Pattern ASCII_WORD = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.+\\-]*");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]+");
    private static final List<String> DOMAIN_WORDS = List.of(
            "年假", "病假", "事假", "婚假", "产假", "陪产假", "育儿假", "丧假",
            "差旅", "报销", "发票", "销售额", "营收", "订单量", "客单价", "季度",
            "区域", "环比", "同比", "知识库", "检索链路", "混合检索", "向量检索",
            "重排", "切片", "会话", "权限", "审计", "私有化", "部署", "大模型",
            "智能体", "工具调用", "接口鉴权", "限流", "结构化日志", "向量", "索引",
            "召回", "命中率", "准确率", "数据安全", "数据泄露", "合规", "服务等级",
            "远程办公", "弹性工作", "加班", "调休", "年假天数", "费用报销",
            "差旅标准", "住宿标准", "发票要求", "报销流程", "数据合规"
    );

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    public ChineseTokenizer() {
        WordDictionary dictionary = WordDictionary.getInstance();
        for (String word : DOMAIN_WORDS) {
            dictionary.freqs.put(word, 20_000d);
        }
    }

    public List<String> domainWords() {
        return DOMAIN_WORDS;
    }

    public List<String> tokenize(String text) {
        String normalized = TextTools.normalize(text);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher ascii = ASCII_WORD.matcher(normalized);
        while (ascii.find()) {
            String token = ascii.group().replaceAll("^[._+\\-]+|[._+\\-]+$", "");
            if (!token.isBlank()) tokens.add(token);
        }
        tokens.addAll(jiebaWords(normalized));
        Matcher cjk = CJK_RUN.matcher(normalized);
        while (cjk.find()) {
            String run = cjk.group();
            if (run.length() == 1) {
                tokens.add(run);
            } else {
                for (int i = 0; i < run.length() - 1; i++) {
                    tokens.add(run.substring(i, i + 2));
                }
            }
        }
        return new ArrayList<>(tokens);
    }

    public List<String> tokenizeWords(String text) {
        String normalized = TextTools.normalize(text);
        List<String> words = new ArrayList<>();
        Matcher ascii = ASCII_WORD.matcher(normalized);
        while (ascii.find()) {
            String token = ascii.group().replaceAll("^[._+\\-]+|[._+\\-]+$", "");
            if (!token.isBlank()) words.add(token);
        }
        words.addAll(jiebaWords(normalized));
        return words;
    }

    public Set<String> tokenSet(String text) {
        return new LinkedHashSet<>(tokenize(text));
    }

    private List<String> jiebaWords(String text) {
        List<String> words = new ArrayList<>();
        for (SegToken token : segmenter.process(text, JiebaSegmenter.SegMode.SEARCH)) {
            String word = token.word == null ? "" : token.word.trim();
            if (!word.isEmpty() && CJK_RUN.matcher(word).matches()) {
                words.add(word);
            }
        }
        return words;
    }
}

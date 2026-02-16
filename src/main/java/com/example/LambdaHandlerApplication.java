package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import datadog.trace.api.Trace;
import datadog.trace.api.CorrelationIdentifier;
import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@SpringBootApplication
public class LambdaHandlerApplication {

    private static final Logger log = LogManager.getLogger(LambdaHandlerApplication.class);

    public static void main(String[] args) {
        // ローカル実行時のエントリーポイント（LambdaではFunctionInvoker経由で起動）
        SpringApplication.run(LambdaHandlerApplication.class, args);
    }

    @Bean
    public Function<Map<String, Object>, Map<String, Object>> getItem(JdbcTemplate jdbc) {
        return input -> handleGetItem(jdbc, input);
    }

    // @Trace: Datadogのトレースを付与するアノテーション
    // operationName: トレースのオペレーション名（デフォルトはメソッド名）
    // resourceName: トレースのリソース名（デフォルトはoperationNameと同じ）
    @Trace(operationName = "api.handle_get_item", resourceName = "LambdaHandlerApplication.handleGetItem")
    private Map<String, Object> handleGetItem(JdbcTemplate jdbc, Map<String, Object> input) {
        // 実処理: 入力判定・DB検索（GETのクエリパラメータから取得）
        Number id = null;
        Object qsp = (input == null) ? null : input.get("queryStringParameters");
        if (qsp instanceof Map<?, ?> qspMap) {
            Object idObj = qspMap.get("id");
            if (idObj instanceof String s && !s.isBlank()) {
                try {
                    id = Long.parseLong(s);
                } catch (NumberFormatException ignored) {
                    id = null;
                }
            }
        }
        Map<String, Object> response = new HashMap<>();
        response.put("id", id);
        if (id == null) {
            response.put("found", false);
            return response;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name, status from items where id = ?",
                id
        );
        response.put("found", !rows.isEmpty());
        if (!rows.isEmpty()) {
            response.put("item", rows.get(0));
        }

        // Datadog設定: タグ付け
        // 現在のアクティブなスパンを取得
        Span span = GlobalTracer.get().activeSpan();
        if (span != null) {
            // Datadogのスパンにカスタムタグを付与
            span.setTag("custom.item_id", id.longValue());
        }
        // Datadog設定: ログ相関
        // ログとトレースの相互参照用に trace/span ID をMDCへセット
        try {
            ThreadContext.put("dd.trace_id", CorrelationIdentifier.getTraceId());
            ThreadContext.put("dd.span_id", CorrelationIdentifier.getSpanId());
            log.info("getItem processed. id={}", id);
        } finally {
            ThreadContext.remove("dd.trace_id");
            ThreadContext.remove("dd.span_id");
        }

        return response;
    }
}

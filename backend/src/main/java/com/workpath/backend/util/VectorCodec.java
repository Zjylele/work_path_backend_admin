package com.workpath.backend.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;

/**
 * 向量 JSON 编解码。
 *
 * <p>policy_clause.vector_data 是 TEXT 列，里面存 JSON 数组。
 * 选文本列而不是独立向量库的依据见第1章 1.3.4：条款规模在数十至数百条量级，
 * 应用层算余弦相似度即可满足检索要求。</p>
 *
 * <p>两条硬约束：</p>
 * <ol>
 *   <li><b>只返回 null，不抛异常</b>。库里可能有历史脏数据（手工改过、或旧版本写坏的），
 *       检索时遇到脏数据应该跳过该条款，而不是让整个查询失败；</li>
 *   <li><b>写进去再读回来必须逐位相等</b>。用 Double#toString 而不是
 *       String.format("%f")——后者按小数位截断，会把余弦相似度的排序
 *       静默打乱，而且这种错在结果上看不出来。</li>
 * </ol>
 */
public final class VectorCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VectorCodec() {
    }

    /**
     * 序列化为 JSON 数组。
     *
     * @return {@code [0.5,0.1,...]}；入参为 null 时返回 null（对应"该条款没有向量"）
     */
    public static String format(double[] vector) {
        if (vector == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(vector.length * 4 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            // Double.toString 是 round-trip 安全的，见类注释
            sb.append(Double.toString(vector[i]));
        }
        return sb.append(']').toString();
    }

    /**
     * 反序列化。任何不符合预期的情况都返回 null，由调用方决定跳过。
     *
     * <p>额外容忍三类输入：元素带 {@code f} 后缀（手写 JSON 的浮点字面量）、
     * 科学计数法（服务商常返回 {@code 1.0E-8}）、首尾空白。</p>
     * 反之，只要有一个元素不是数字就整体判脏——部分截断会让维度静默改变，
     * 之后与查询向量算相似度时结果错得没有线索。</p>
     */
    public static double[] parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        String s = json.strip();
        // 去掉浮点后缀，交给 Jackson 前统一成标准 JSON number
        s = s.replaceAll("(?i)(\\d)[fF](?=[,\\]\\s])", "$1");
        try {
            JsonNode node = MAPPER.readTree(s);
            if (!node.isArray()) {
                return null;
            }
            double[] out = new double[node.size()];
            for (int i = 0; i < node.size(); i++) {
                JsonNode e = node.get(i);
                // isNumber 为 false 包含字符串、null、布尔、对象——一律判脏
                if (!e.isNumber()) {
                    return null;
                }
                out[i] = e.doubleValue();
            }
            return out;
        } catch (Exception e) {
            // JSON 语法错误或 Jackson 解析失败：脏数据，跳过
            return null;
        }
    }

    /** 仅用于测试断言的便捷方法：解析结果是否为 null。 */
    static boolean isNullOrEmpty(double[] v) {
        return v == null || v.length == 0;
    }
}

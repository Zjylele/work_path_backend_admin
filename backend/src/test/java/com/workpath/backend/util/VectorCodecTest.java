package com.workpath.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 向量 JSON 编解码单元测试。
 *
 * <p>vector_data 列是 TEXT，里面存的是 JSON 数组。这条链路有两个必须守住的点：
 * 一是 null / 脏数据不能抛异常（库里历史数据或人为改动都可能产生），
 * 二是写进去再读出来必须逐位相等——余弦相似度对数值极敏感，
 * 若序列化把精度削了，相似度排序会静默错乱。</p>
 */
class VectorCodecTest {

    @Test
    @DisplayName("格式化后能被自己解析回来，且数值逐位相等")
    void roundTrip() {
        double[] v = {0d, 1d, -1d, 0.123456789012345, 1.0E-8, 3.141592653589793};
        double[] back = VectorCodec.parse(VectorCodec.format(v));
        assertThat(back).isNotNull();
        assertThat(back).isEqualTo(v);
    }

    @Test
    @DisplayName("空数组格式化为 []，解析回空数组而非 null")
    void emptyArray() {
        double[] back = VectorCodec.parse(VectorCodec.format(new double[0]));
        assertThat(back).isNotNull();
        assertThat(back).isEmpty();
    }

    @Test
    @DisplayName("null 与空白解析为 null（检索时据此跳过该条款）")
    void nullAndBlank() {
        assertThat(VectorCodec.parse(null)).isNull();
        assertThat(VectorCodec.parse("")).isNull();
        assertThat(VectorCodec.parse("   ")).isNull();
    }

    @Test
    @DisplayName("非 JSON / 结构不符时解析为 null，不抛异常")
    void malformed() {
        assertThat(VectorCodec.parse("not json")).isNull();
        assertThat(VectorCodec.parse("{\"a\":1}")).isNull();
        // 元素不是数字时整体视为脏数据：部分截断会让维度静默改变
        assertThat(VectorCodec.parse("[1.0,\"x\",3.0]")).isNull();
        assertThat(VectorCodec.parse("[1.0,2.0")).isNull();
    }

    @Test
    @DisplayName("服务商返回的科学计数法（1.0E-8）可正常解析")
    void scientificNotation() {
        double[] back = VectorCodec.parse("[1.0E-8,-2.5E+3]");
        assertThat(back).isNotNull();
        assertThat(back[0]).isEqualTo(1.0E-8);
        assertThat(back[1]).isEqualTo(-2500d);
    }

    @Test
    @DisplayName("浮点字面量（带 f 后缀）也能解析，兼容手写 JSON")
    void floatSuffix() {
        assertThat(VectorCodec.parse("[1.0f, 2.5f]")).containsExactly(1d, 2.5d);
    }

    @Test
    @DisplayName("维度取自数组长度，与调用方声明的维度互相独立")
    void dimensionFromLength() {
        double[] v = VectorCodec.parse("[1,2,3]");
        assertThat(v).hasSize(3);
        assertThat(List.of(v)).allSatisfy(a -> assertThat(a.length).isEqualTo(3));
    }
}

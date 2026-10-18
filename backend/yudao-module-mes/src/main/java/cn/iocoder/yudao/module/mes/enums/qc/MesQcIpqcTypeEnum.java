package cn.iocoder.yudao.module.mes.enums.qc;

import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/** 过程检验子阶段。保留已有1/4类型，末检追加为6，避免改变历史单据含义。 */
@Getter
@AllArgsConstructor
public enum MesQcIpqcTypeEnum implements ArrayValuable<Integer> {
    PROCESS(1, "自制工序检验"), FIRST(2, "首检"), PATROL(3, "巡检"), SELF(4, "自检"),
    FINISHED_PRODUCT(5, "成品检验"), LAST(6, "末检");

    private final Integer type;
    private final String name;

    public static boolean contains(Integer type) {
        return Arrays.stream(values()).anyMatch(value -> value.type.equals(type));
    }

    @Override
    public Integer[] array() {
        return Arrays.stream(values()).map(MesQcIpqcTypeEnum::getType).toArray(Integer[]::new);
    }
}

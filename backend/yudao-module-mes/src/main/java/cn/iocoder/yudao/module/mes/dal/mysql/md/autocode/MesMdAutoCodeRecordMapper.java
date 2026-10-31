package cn.iocoder.yudao.module.mes.dal.mysql.md.autocode;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.autocode.MesMdAutoCodeRecordDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MES 编码生成记录 Mapper
 *
 * @author 芋道源码
 */
@Mapper
public interface MesMdAutoCodeRecordMapper extends BaseMapperX<MesMdAutoCodeRecordDO> {

    // Match the whole rendered namespace, including visible date/input, padding and serial width.
    // A previous day's larger sequence must not advance today's counter.
    @Select("SELECT MAX(serial_no) FROM mes_md_auto_code_record WHERE deleted=0 AND rule_id=#{ruleId} "
            + "AND CHAR_LENGTH(CAST(serial_no AS CHAR)) <= #{width} "
            + "AND result=CONCAT(#{prefix}, LPAD(CAST(serial_no AS CHAR), #{width}, '0'), #{suffix})")
    Long highestIssued(@Param("ruleId") long ruleId, @Param("prefix") String prefix,
                       @Param("width") int width, @Param("suffix") String suffix);

    default MesMdAutoCodeRecordDO selectByResult(String result) {
        return selectOne(MesMdAutoCodeRecordDO::getResult, result);
    }

}

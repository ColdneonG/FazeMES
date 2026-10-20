package cn.iocoder.yudao.module.mes.service.wm.barcode;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class MesWmBarcodeLabelPolicyTest {
    static Stream<Arguments> contents(){return Stream.of(
        Arguments.of(2,"4006381333931",true),Arguments.of(2,"4006381333932",false),Arguments.of(2,"400638133393",false),Arguments.of(2,"４006381333931",false),Arguments.of(2,"4006381333931 ",false),
        Arguments.of(4,"036000291452",true),Arguments.of(4,"036000291453",false),Arguments.of(4,"03600029145",false),
        Arguments.of(3,"AZ09 -.$/+%",true),Arguments.of(3,"*ITEM-1*",false),Arguments.of(3,"item-1",false),Arguments.of(3,"ITEM\n1",false),Arguments.of(3,"A".repeat(128),true),Arguments.of(3,"A".repeat(129),false),
        Arguments.of(1,"",false),Arguments.of(1,"A".repeat(2048),true),Arguments.of(1,"A".repeat(2049),false),Arguments.of(1,"料".repeat(682)+"AB",true),Arguments.of(1,"料".repeat(683),false),Arguments.of(1,"😀".repeat(512),true),Arguments.of(1,"😀".repeat(512)+"A",false),Arguments.of(1,"\uD800",false),Arguments.of(99,"A",false));}
    @ParameterizedTest @MethodSource("contents") void strictContentRules(int format,String value,boolean valid){if(valid)assertDoesNotThrow(()->MesWmBarcodeLabelPolicy.content(format,value));else assertThrows(ServiceException.class,()->MesWmBarcodeLabelPolicy.content(format,value));}
    @Test void code39RejectsAnActualBackslashButAcceptsAHyphen(){
        assertThrows(ServiceException.class,()->MesWmBarcodeLabelPolicy.content(3,"ITEM\\X"));
        assertDoesNotThrow(()->MesWmBarcodeLabelPolicy.content(3,"ITEM-X"));
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,127})
    void qrRejectsEveryC0AndDelCharacterWithoutDependingOnBlankContent(int codePoint){
        String value="ITEM"+(char)codePoint+"X";
        assertThrows(ServiceException.class,()->MesWmBarcodeLabelPolicy.content(1,value));
    }
    @ParameterizedTest @ValueSource(ints={0x00A0,0x2007,0x202F,0xFEFF})
    void qrRejectsWhitespaceOnlyValuesThatThePrintClientTreatsAsBlank(int codePoint){
        assertThrows(ServiceException.class,()->MesWmBarcodeLabelPolicy.content(1,String.valueOf((char)codePoint)));
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","LABEL_50X30","LABEL_70X40","A4_2COL"}) void templatesHaveOnlyThreeSafePresets(String value){assertTrue(java.util.Set.of("LABEL_50X30","LABEL_70X40","A4_2COL").contains(MesWmBarcodeLabelPolicy.template(value)));}
    @ParameterizedTest @ValueSource(strings={"template1","<img onerror=alert(1)>","label_50x30"}) void arbitraryTemplatesAreRejected(String value){assertThrows(ServiceException.class,()->MesWmBarcodeLabelPolicy.template(value));}
    @Test void unsupportedMetadataAndPlaceholdersAreRejected(){assertThrows(ServiceException.class,()->MesWmBarcodeLabelPolicy.type(302));assertThrows(ServiceException.class,()->MesWmBarcodeLabelPolicy.type(303));assertThrows(ServiceException.class,()->MesWmBarcodeLabelPolicy.status(null));assertThrows(ServiceException.class,()->MesWmBarcodeLabelPolicy.status(4));assertThrows(ServiceException.class,()->MesWmBarcodeLabelPolicy.contentFormat("{JAVASCRIPT}"));assertEquals("WH-A",MesWmBarcodeLabelPolicy.generate("WH-{BUSINESSCODE}","A"));}
}

package cn.iocoder.yudao.module.mes.service.wm.barcode;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Fixed data/encoding rules shared by registration and the read-only print context. */
public final class MesWmBarcodeLabelPolicy {
    private MesWmBarcodeLabelPolicy() { }
    private static final Set<Integer> TYPES=Set.of(102,103,104,105,106,107,109,300,301,400,500,600,601,602,603,604,605);
    private static final Set<String> TEMPLATES=Set.of("LABEL_50X30","LABEL_70X40","A4_2COL");
    public static ServiceException invalid(String message){return new ServiceException(1_040_703_020,message);}
    public static void type(Integer type){if(type==null||!TYPES.contains(type))throw invalid("不支持此条码业务类型，请选择已实现的业务对象");}
    public static void format(Integer format){if(format==null||format<1||format>4)throw invalid("条码格式无效，请修正条码配置");}
    public static void status(Integer status){if(status==null||(status!=0&&status!=1))throw invalid("条码或配置状态无效，请修正记录");}
    public static String template(String value){if(value==null||value.isBlank())return "LABEL_50X30";if(!TEMPLATES.contains(value))throw invalid("打印模板无效，请在条码配置中选择安全的预设模板");return value;}
    public static void contentFormat(String value){if(value==null||value.isBlank())return;if(value.length()>255||value.replace("{BUSINESSCODE}","").matches("(?s).*[{}].*"))throw invalid("条码内容格式仅支持 {BUSINESSCODE} 占位符");}
    public static String generate(String pattern,String code){contentFormat(pattern);return pattern==null||pattern.isBlank()?code:pattern.replace("{BUSINESSCODE}",code);}
    public static void content(Integer format,String content){
        format(format);
        // Match the print client's blank-content rule, including NBSP and BOM,
        // while preserving every byte of nonblank registered content.
        if(content==null||content.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0xFEFF))throw invalid("条码内容不能为空");
        for(int i=0;i<content.length();i++){
            char c=content.charAt(i);
            if(c<=0x1F||c==0x7F)throw invalid("条码内容不能包含控制字符，请修正后再生成或打印");
            if(Character.isHighSurrogate(c)){
                if(i+1>=content.length()||!Character.isLowSurrogate(content.charAt(++i)))throw invalid("条码内容包含无效字符");
            }else if(Character.isLowSurrogate(c))throw invalid("条码内容包含无效字符");
        }
        if(format==1){if(content.getBytes(StandardCharsets.UTF_8).length>2048)throw invalid("二维码内容超过 2048 字节");return;}
        if(format==3){if(content.length()>128||!content.matches("[A-Z0-9 .$/+%\\-]+"))throw invalid("CODE39 仅支持不超过 128 字符的大写字母、数字及许可符号，不能包含 *");return;}
        int length=format==2?13:12;
        if(!content.matches("[0-9]{"+length+"}"))throw invalid(format==2?"EAN13 必须为含校验位的完整 13 位数字":"UPC 必须为含校验位的完整 12 位数字");
        int sum=0,weight=3;for(int i=length-2;i>=0;i--){sum+=(content.charAt(i)-'0')*weight;weight=4-weight;}
        if((10-sum%10)%10!=content.charAt(length-1)-'0')throw invalid("条码校验位错误，请修正后再生成或打印");
    }
}

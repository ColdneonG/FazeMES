package cn.iocoder.yudao.module.mes.service.insight;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.invalid;

/** Pure protocol validation shared by the pre-settlement gateway and the decision reader. */
public final class MesJevProtocol {
    private MesJevProtocol(){ }
    public static void validate(JsonNode request,JsonNode response){
        if(request==null||response==null||!response.isObject()||!response.path("model").isTextual()||!response.path("model").asText().matches("jev-[A-Za-z0-9.\\-]{1,70}"))
            throw invalid("Jev返回的模型标识无效");
        for(String name:List.of("category","department")){
            JsonNode criteria=request.path("questions").path(name).path("criteria");
            if(!criteria.isObject()||criteria.isEmpty())throw invalid("Jev决策问题格式无效");
            var options=new LinkedHashSet<String>();criteria.fieldNames().forEachRemaining(options::add);
            JsonNode answer=response.path("answers").path(name),probabilities=answer.path("probabilities");
            if(!answer.path("type").isTextual()||!answer.path("type").asText().equals("choice")||!answer.path("choice").isTextual()||!options.contains(answer.path("choice").asText())||!unit(answer.path("confidence"))||!probabilities.isObject()||probabilities.size()!=options.size())
                throw invalid("Jev返回的分类或置信度无效，请手动确认");
            double sum=0,selected=0,max=0;
            for(String option:options){JsonNode value=probabilities.path(option);if(!unit(value))throw invalid("Jev返回的概率分布无效，请手动确认");sum+=value.doubleValue();max=Math.max(max,value.doubleValue());if(option.equals(answer.path("choice").asText()))selected=value.doubleValue();}
            if(Math.abs(sum-1)>.01||selected+1e-6<max)throw invalid("Jev返回的概率分布不一致，请手动确认");
        }
        String department=response.path("answers").path("department").path("choice").asText();
        if(!department.equals("UNKNOWN")){
            if(!department.matches("D_[1-9][0-9]*"))throw invalid("Jev返回的部门编号无效，请手动确认");
            try{Long.parseLong(department.substring(2));}catch(NumberFormatException ex){throw invalid("Jev返回的部门编号无效，请手动确认");}
        }
    }
    private static boolean unit(JsonNode value){return value.isNumber()&&Double.isFinite(value.doubleValue())&&value.doubleValue()>=0&&value.doubleValue()<=1;}
}

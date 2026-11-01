package cn.iocoder.yudao.module.mes.service.insight;

import java.util.*;
import java.util.regex.Pattern;

/** Deterministic commands are classified before read tools or model planning. */
final class MesAgentCommandIntent {
    private MesAgentCommandIntent() {}

    private static final String PREFIX="(?:(?:请帮我|请你|请|可以帮我|能否帮我|帮我|麻烦你|麻烦|帮忙|先|再|现在|立即|那就)\\s*)*";
    private static final String ASSIGN="(?:改成|改为|修改为|更改为|变更为|设为|设置为|调整为|切换为|切换到|标记为)";
    private static final String ORDER="(?:(?:这|该)(?:个|张)?\\s*)?(?:生产)?工单";
    private static final String ACTION_VERB="确认|取消|完成|完结|结算|修改|更新|更改|调整|删除|新增|新建|创建|提交|执行|预占|释放|设置|录入|登记|审核|审批|发布|改为|改成";
    private static final Pattern SPLIT=Pattern.compile("[，,；;。\\n]|然后|并且|同时|接着|顺便|以及|并|(?:后|再)(?=(?:请|再|帮我)?(?:"+ACTION_VERB+"))");
    private static final Pattern READ=Pattern.compile("^"+PREFIX+"(?:查询|查看|查|看看|看|统计|列出|有哪些|有多少|显示|只看|仅看|如何|怎么|怎样|说明|解释).*",Pattern.DOTALL);
    private static final Pattern QUESTION=Pattern.compile("如何|怎么|怎样|为什么|为何|需要哪些条件|需要什么条件|什么条件|是否|是不是|有没有|了吗|了没|(?:确认|取消|完成|结算)(?:的)?(?:情况|原因|条件|记录|结果)|确认(?:一下)?.*工单.*(?:状态|进度)$");
    private static final Pattern INCIDENT_CREATE=Pattern.compile("^"+PREFIX+"(?:新建|创建|建立|登记|记录|跟进)(?:一个|一条|一项)?\\s*异常.*",Pattern.DOTALL);
    private static final Pattern INCIDENT_MOVE=Pattern.compile("^"+PREFIX+"(?:(?:(?:将|把)\\s*)?异常(?=.*(?:处理中|待验证|关闭|重新处理)).*(?:设为|标记|开始处理|关闭|重新处理|转为|改为|推进).*|(?:关闭|重新处理|开始处理|推进)\\s*异常.*)",Pattern.DOTALL);
    private static final Pattern WRITE=Pattern.compile("(?<![已未待])(?:"+ACTION_VERB+")|改价格|改价|"+ASSIGN+"|扣减库存|增加库存|排产|排到|记入|(?:执行|办理|帮我).*(?:出库|入库)",Pattern.DOTALL);
    private static final Pattern EXTRA_WRITE=Pattern.compile("删除|新增|新建|创建|提交|扣减|增加库存|执行.*(?:入库|出库)|办理.*(?:入库|出库)|预占|释放|排产|录入|登记|报工|发布|审核|审批",Pattern.DOTALL);
    private static final Pattern DEVICE_CHANGE=Pattern.compile("^(?:把|将|为|对)?(?:修改|更新|更改|变更|调整|设置|切换).*设备.*状态|设备.*(?:修改|更新|更改|变更|调整|设置|切换).*状态|设备.*状态.*(?:修改|更新|更改|变更|调整|设置|切换|改一下)",Pattern.DOTALL);
    private static final Pattern DEVICE_EXTRA_FIELDS=Pattern.compile("(?:修改|更新|更改|变更|调整|设置).*设备.*(?:名称|编码|型号|品牌|车间|类型|数量)",Pattern.DOTALL);
    private static final Pattern STATUS=Pattern.compile("(?:"+ASSIGN+"|状态为)\\s*(停机|生产中|维护中)(?![\\p{IsHan}A-Za-z0-9])");
    private static final Pattern FILTER=Pattern.compile("^(?:(?:把|将|修改|更改|调整)?(?:查询|筛选|过滤|统计)(?:条件|范围|月份|年份|日期|时间|分组).*|(?:把|将|修改|更改|调整)?(?:月份|年份|查询日期|查询范围|筛选条件|分组条件).*|(?:改为|改成|换成|换为)(?:按.+|[0-9一二三四五六七八九十〇零]{1,4}(?:年|月|月份).*|本月|上个月|.+仓(?:库)?))$",Pattern.DOTALL);

    private record Intent(Set<String> kinds,boolean write,boolean unsupported) {}

    static Set<String> actionKinds(String question) { return analyze(question).kinds(); }
    static boolean isWrite(String question) { return analyze(question).write(); }
    static boolean hasUnsupportedActions(String question) { return analyze(question).unsupported(); }

    static Integer deviceStatus(String question) {
        if(question==null)return null;
        Integer status=null;
        for(String part:SPLIT.split(question)) {
            String clause=part.strip();
            if(isRead(clause)||isFilter(clause))continue;
            var matcher=STATUS.matcher(clause);
            while(matcher.find())status=switch(matcher.group(1)){case "停机"->1;case "生产中"->2;default->3;};
        }
        return status;
    }

    private static Intent analyze(String question) {
        String q=question==null?"":question.strip();
        var kinds=new LinkedHashSet<String>();
        // An incident's description may describe requested work; it is data for the incident tool.
        if(INCIDENT_CREATE.matcher(q).matches())return new Intent(kinds,true,false);
        boolean write=false,unsupported=false;int actionClauses=0;
        for(String part:SPLIT.split(q)) {
            String clause=part.strip().replaceFirst("^"+PREFIX,"");
            if(clause.isBlank()||isRead(clause)||isFilter(clause))continue;
            if(INCIDENT_MOVE.matcher(clause).matches()) {write=true;actionClauses++;continue;}
            var clauseKinds=new LinkedHashSet<String>();
            // A bare second verb may refer to the explicitly named business object in the first clause.
            boolean order=clause.contains("工单")||(!clause.contains("设备")&&q.contains("工单")&&!q.contains("设备"));
            boolean device=clause.contains("设备")||(!clause.contains("工单")&&q.contains("设备")&&!q.contains("工单"));
            if(order) {
                if(orderAction(clause,"确认","已确认"))clauseKinds.add("CONFIRM_WORKORDER");
                if(orderAction(clause,"取消","已取消"))clauseKinds.add("CANCEL_WORKORDER");
                if(orderAction(clause,"(?:完成|完结)","已完成"))clauseKinds.add("FINISH_WORKORDER");
                if(clause.matches("(?s)^(?:执行)?结算(?:一下)?\\s*(?:"+ORDER+").*")
                        ||clause.matches("(?s)^(?:把|将|为|对)?\\s*"+ORDER+".*(?<![已未待])结算(?:一下)?(?:的)?(?:实际)?(?:成本)?$"))clauseKinds.add("SETTLE_WORKORDER");
            }
            if(device&&(DEVICE_CHANGE.matcher(clause).find()
                    ||((clause.contains("状态")||STATUS.matcher(clause).find())&&Pattern.compile(ASSIGN).matcher(clause).find())))clauseKinds.add("UPDATE_DEVICE_STATUS");
            kinds.addAll(clauseKinds);
            if(!clauseKinds.isEmpty()) {
                write=true;actionClauses++;
                if(EXTRA_WRITE.matcher(clause).find()
                        ||Pattern.compile(ASSIGN).matcher(clause).results().count()>1
                        ||(clauseKinds.contains("UPDATE_DEVICE_STATUS")&&DEVICE_EXTRA_FIELDS.matcher(clause).find()))unsupported=true;
            }else if(WRITE.matcher(clause).find()) {
                write=true;unsupported=true;
            }
        }
        return new Intent(kinds,write,unsupported||actionClauses>1);
    }

    private static boolean orderAction(String clause,String verb,String state) {
        return clause.matches("(?s)^(?:执行)?"+verb+"(?:一下)?\\s*"+ORDER+".*")
                ||clause.matches("(?s)^"+verb+"(?:一下)?$")
                ||clause.matches("(?s)^(?:把|将|对)?\\s*"+ORDER+".*(?<![已未待])"+verb+"(?:一下|掉)?$")
                ||clause.matches("(?s)^(?:把|将)?\\s*"+ORDER+".*"+ASSIGN+"\\s*(?:已)?"+state.substring(1)+".*");
    }

    private static boolean isRead(String clause) { return READ.matcher(clause).matches()||QUESTION.matcher(clause).find(); }
    private static boolean isFilter(String clause) { return FILTER.matcher(clause).matches(); }
}

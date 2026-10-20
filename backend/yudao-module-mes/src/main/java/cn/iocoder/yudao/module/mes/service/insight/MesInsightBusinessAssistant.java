package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.insight.MesInsightController.Question;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.regex.*;

import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.*;

/** Bounded, parameterized business tools. Models never choose SQL, filters or inventory arithmetic. */
@Service
public class MesInsightBusinessAssistant {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final Pattern YEAR=Pattern.compile("([12][0-9]{3}|[一二三四五六七八九〇零]{4})年");
    private static final Pattern MONTH=Pattern.compile("([0-9]{1,2}|[一二三四五六七八九十]{1,3})月(?:份)?");
    private static final Pattern CODE=Pattern.compile("(?<![A-Za-z0-9_.%\\-])([A-Za-z][A-Za-z0-9_.%\\-]*)(?![A-Za-z0-9_.%\\-])");
    private static final Pattern INVENTORY=Pattern.compile("库存|台账|收发存|入库出库|入库和出库|进出库|原材料.*(入库|出库)|原料.*(入库|出库)");
    private static final Pattern LEGACY=Pattern.compile("成本|价格|售价|定价|预算|BOM|质量|不良|合格|缺陷|瓶颈|等待|流程|工序|异常|安灯|处置|负责人|风险|交期|延期|延误|工单|齐套|缺料|排产|生产概况",Pattern.CASE_INSENSITIVE);
    private final JdbcTemplate jdbc;
    private final SecurityFrameworkService security;
    private final MesInsightConversationStore store;
    private final MesInsightInventoryQuery inventory;
    private final MesInsightService legacy;
    public MesInsightBusinessAssistant(JdbcTemplate jdbc,SecurityFrameworkService security,MesInsightConversationStore store,
                                      MesInsightInventoryQuery inventory,MesInsightService legacy){this.jdbc=jdbc;this.security=security;this.store=store;this.inventory=inventory;this.legacy=legacy;}
    private static String text(Object value){return value==null?"":value.toString();}
    private static int integer(Object value){return value instanceof Number n?n.intValue():Integer.parseInt(value.toString());}
    private static String stamp(){return OffsetDateTime.now(ZONE).toString();}
    @SuppressWarnings("unchecked")
    public Map<String,Object> ask(Question req){
        var claim=store.claim(req);
        try{
            String q=req.question()==null?"":req.question().strip();String roundQuestion=q;
            var context=new LinkedHashMap<>(claim.context());var slots=new LinkedHashMap<String,Object>();
            var pending=claim.pending();boolean selected=!claim.selectedSlots().isEmpty();
            boolean pendingLive=pending.get("expiresAt")!=null&&OffsetDateTime.parse(pending.get("expiresAt").toString()).isAfter(OffsetDateTime.now(ZONE));
            boolean follow=(followup(q)||(pendingLive&&q.length()<=100&&!INVENTORY.matcher(q).find()&&!LEGACY.matcher(q).find()))&&(!context.isEmpty()||pendingLive);
            if(selected||follow){
                if(pendingLive&&pending.get("slots") instanceof Map<?,?> p)slots.putAll((Map<String,Object>)p);
                else slots.putAll(context);
            }
            if(selected){slots.putAll(claim.selectedSlots());q=text(pending.get("originalQuestion"));}
            else if(follow&&pendingLive&&!text(pending.get("originalQuestion")).isBlank())q=text(pending.get("originalQuestion"))+" "+(q.matches("[12][0-9]{3}|[一二三四五六七八九〇零]{4}")?q+"年":q);
            String displayQuestion=req.question()==null||req.question().isBlank()?selectedLabel(pending,req):req.question().strip();
            String intent=isWrite(q)?"scope":selected?text(slots.get("intent")):intent(q,follow?slots:null);
            if(!selected&&!follow)slots.clear();
            Set<String> required=new LinkedHashSet<>();Map<String,Object> answer;
            if(intent.equals("inventory")||intent.equals("document")){
                required.add(MesInsightInventoryQuery.STOCK_PERMISSION);store.require(required);
                slots.put("intent",intent);
                if(q.matches("(?s).*(修改|改成|删除|新增|执行|帮我.*(入库|出库)|提交|扣减|增加[0-9]|转移库存).*")){
                    answer=outOfScope("业务助手仅查询事实。库存调整和业务执行请使用相应单据流程。");
                }else if(q.matches("(?s).*(可用|可领|可发|库存金额|库存价值|库存估值|库存成本|库存货值).*")){
                    answer=outOfScope("当前库存工具查询全部实物库存数量，包含线边、隔离和冻结库存。历史可用量及库存金额缺少相应历史依据，请使用可用库存或价格与成本功能核验。");
                }else{
                    try{
                        if(intent.equals("inventory")){
                            parsePeriod(q,slots);
                            if(q.contains("按仓库"))slots.put("groupBy","WAREHOUSE");else if(q.contains("按物料")||q.contains("按产品"))slots.put("groupBy","ITEM");else if(q.contains("按批次"))slots.put("groupBy","BATCH");
                            slots.putIfAbsent("groupBy","BATCH");
                            Matcher batch=Pattern.compile("(?:批次号[：:\\s]*|批次[：:\\s]+)([^\\s，。？！?]+)").matcher(q);if(batch.find())slots.put("batchCode",batch.group(1));
                            if(q.contains("所有仓库")||q.contains("全部仓库"))slots.remove("warehouseId");
                            resolveEntities(q,slots,selected);
                        }else if(!selected){
                            boolean explicitCode=CODE.matcher(roundQuestion).find()||Pattern.compile("(?:单号|单据编码|单据)[：:\\s]*[^\\s，。？！?]+").matcher(roundQuestion).find();
                            if(explicitCode){slots.put("documentCode",documentCode(roundQuestion));slots.remove("documentBizType");slots.remove("documentBizId");}
                            else if(roundQuestion.matches("(?s).*(按仓库|按物料|按产品|按批次).*"))throw new NeedClarification("NEED_FILTER","单据查询逐条展示原始库存流水，不支持库存台账分组；请提供新的单号，或按月份查询库存台账。",List.of());
                            else if(!slots.containsKey("documentCode"))slots.put("documentCode",documentCode(roundQuestion));
                        }
                        var evidence=intent.equals("inventory")?inventory.inventory(slots):inventory.document(slots);
                        Object permissions=evidence.remove("_requiredPermissions");if(permissions instanceof Collection<?> p)for(Object permission:p)required.add(permission.toString());
                        if(req.useModel())addWarning(evidence,"MODEL_NOT_USED","库存与单据数量由数据库规则精确查询，本次未调用模型。");
                        answer=map("answer",intent.equals("inventory")?inventoryAnswer(evidence):documentAnswer(evidence),"mode","RULES","topic",intent,"evidence",evidence,"suggestions",List.of(),"generatedAt",stamp(),"status","ANSWERED","clarification",null);
                        context=slots;
                    }catch(NeedClarification need){answer=clarify(need.reason,need.getMessage(),need.choices,slots,q);}
                    catch(MesInsightInventoryQuery.ClarificationException need){required.addAll(need.getRequiredPermissions());answer=clarify(need.getReason(),need.getPrompt(),need.getChoices(),slots,q);}
                }
            }else if(intent.equals("legacy")){
                if(Pattern.compile("成本|价格|售价|定价|预算|BOM",Pattern.CASE_INSENSITIVE).matcher(q).find())required.add("mes:cost:query");
                store.require(required);
                if(req.useModel()&&!security.hasPermission("mes:assistant:chat"))throw invalid("调用模型还需要AI助手会话权限");
                answer=new LinkedHashMap<>(legacy.ask(new Question(q,false)));answer.put("status","ANSWERED");answer.put("clarification",null);answer.put("generatedAt",stamp());
                if(req.useModel()){
                    store.base();store.require(required);
                    try{answer=new LinkedHashMap<>(legacy.ask(new Question(q,true)));answer.put("status","ANSWERED");answer.put("clarification",null);answer.put("generatedAt",stamp());}
                    catch(Exception ex){answer.put("modelWarning","模型暂不可用，以下保留已查询的规则答复与业务依据。");}
                }
                context=new LinkedHashMap<>();context.put("intent","legacy");
            }else answer=outOfScope(isWrite(q)?"业务助手仅查询事实。库存、价格和单据变更请使用相应业务流程。":"当前支持库存月度台账、库存单据流水、产品与工单成本、生产风险、质量、流程和异常查询。请给出业务范围或具体编码。");
            var newPending=answer.remove("_pending") instanceof Map<?,?> p?new LinkedHashMap<>((Map<String,Object>)p):new LinkedHashMap<String,Object>();
            return store.finish(claim,displayQuestion,context,newPending,answer,required);
        }catch(RuntimeException ex){store.release(claim);throw ex;}
    }
    private static String selectedLabel(Map<String,Object> pending,Question req){if(req.clarification()==null)return "选择澄清条件";Object choices=pending.get("choices");if(choices instanceof List<?> list)for(Object choice:list)if(choice instanceof Map<?,?> m&&Objects.equals(m.get("id"),req.clarification().choiceId()))return "选择："+m.get("label");return "选择澄清条件";}
    private static boolean followup(String q){return q.matches("(?s)^(那|只看|仅看|按|换成|改为|继续|再看).*" )||q.matches("[12][0-9]{3}年?")||q.matches("[一二三四五六七八九〇零]{4}年?")||q.matches("[0-9一二三四五六七八九十]{1,3}月份?")||q.equals("上个月")||q.equals("本月");}
    private static boolean isWrite(String q){return q.matches("(?s).*(删除|修改|新增|创建工单|创建单据|提交|改价格|改价|把.*改成|将.*改成|扣减库存|增加库存|执行出库|执行入库|帮我.*(排产|入库|出库)).*");}
    private static String intent(String q,Map<String,Object> previous){
        if(q.matches("(?s).*(推荐旅游|天气|写诗|笑话).*"))return "scope";
        if((q.contains("流水")&&Pattern.compile("单|出库|入库|销售|采购|领料|退货|调拨|[A-Za-z]+[-_][0-9A-Za-z]").matcher(q).find())||q.contains("单号")||q.contains("单据")||(q.matches("(?s).*(销售出库单|采购入库单|领料单|退货单|调拨单).*" )&&CODE.matcher(q).find()))return "document";
        if(INVENTORY.matcher(q).find())return "inventory";
        if(LEGACY.matcher(q).find())return "legacy";
        if(previous!=null&&Set.of("inventory","document").contains(previous.get("intent")))return previous.get("intent").toString();
        return "scope";
    }
    private void parsePeriod(String q,Map<String,Object> slots){
        LocalDate today=LocalDate.now(ZONE);Matcher year=YEAR.matcher(q);Integer parsedYear=null;while(year.find())parsedYear=parseChinese(year.group(1));
        if(q.contains("今年"))parsedYear=today.getYear();else if(q.contains("去年"))parsedYear=today.getYear()-1;
        if(parsedYear==null&&q.strip().matches("[12][0-9]{3}"))parsedYear=Integer.parseInt(q.strip());
        if(parsedYear!=null)slots.put("year",parsedYear);
        Matcher month=MONTH.matcher(q);Integer parsedMonth=null;while(month.find())parsedMonth=parseChinese(month.group(1));
        if(q.contains("上个月")){var previous=today.minusMonths(1);slots.put("year",previous.getYear());parsedMonth=previous.getMonthValue();}
        else if(q.contains("本月")||q.contains("这个月")){slots.put("year",today.getYear());parsedMonth=today.getMonthValue();}
        if(parsedMonth!=null)slots.put("month",parsedMonth);
        if(!slots.containsKey("month"))throw new NeedClarification("PERIOD_REQUIRED","请说明需要查询哪个月份，例如2026年9月。",List.of());
        if(!slots.containsKey("year"))throw new NeedClarification("YEAR_REQUIRED","请说明年份，不能仅凭月份推断年份。",List.of(map("label",today.getYear()+"年","slots",map("year",today.getYear())),map("label",(today.getYear()-1)+"年","slots",map("year",today.getYear()-1))));
        int y=integer(slots.get("year")),m=integer(slots.get("month"));
        if(y<1900||y>today.getYear()+1||m<1||m>12||YearMonth.of(y,Math.max(1,Math.min(12,m))).isAfter(YearMonth.from(today)))throw new NeedClarification("INVALID_PERIOD","月份无效或属于未来期间，请输入已发生的月份。",List.of());
    }
    static int parseChinese(String value){if(value.matches("[0-9]+"))return Integer.parseInt(value);String digits="〇一二三四五六七八九";if(value.contains("十")){int index=value.indexOf('十');int tens=index==0?1:digits.indexOf(value.charAt(0));int ones=index==value.length()-1?0:digits.indexOf(value.charAt(index+1));return tens*10+ones;}int result=0;for(char c:value.toCharArray()){int d=c=='零'?0:digits.indexOf(c);if(d<0)throw invalid("无法识别年份或月份");result=result*10+d;}return result;}
    private record Master(String kind,String table,String slot){}
    private static final List<Master> MASTERS=List.of(new Master("物料分类","mes_md_item_type","categoryId"),new Master("仓库","mes_wm_warehouse","warehouseId"),new Master("物料","mes_md_item","itemId"));
    private void resolveEntities(String q,Map<String,Object> slots,boolean selected){
        Set<String> codes=new LinkedHashSet<>();Matcher matcher=CODE.matcher(q);while(matcher.find())if(!Set.of("BOM","ITEM","BATCH","WAREHOUSE").contains(matcher.group(1).toUpperCase(Locale.ROOT)))codes.add(matcher.group(1));
        if(codes.size()>20)throw new NeedClarification("NEED_FILTER","请缩小物料或仓库编码范围。",List.of());
        Set<String> matchedCodes=new HashSet<>();Set<String> consumedNames=new LinkedHashSet<>();var singleMatches=new ArrayList<Map<String,Object>>();
        String entityQuestion=q.replaceAll("按批次|按仓库|按物料|按产品","");
        for(var master:MASTERS){
            List<Map<String,Object>> rows=new ArrayList<>();
            if(!codes.isEmpty()){var args=new ArrayList<Object>();args.add(TenantContextHolder.getRequiredTenantId());for(String code:codes)args.add(code.toUpperCase(Locale.ROOT));rows=jdbc.queryForList("SELECT id,code,name FROM "+master.table+" WHERE tenant_id=? AND deleted=0 AND UPPER(code) IN ("+String.join(",",Collections.nCopies(codes.size(),"?"))+") ORDER BY id LIMIT 31",args.toArray());}
            if(rows.isEmpty())rows=jdbc.queryForList("SELECT id,code,name FROM "+master.table+" WHERE tenant_id=? AND deleted=0 AND name IS NOT NULL AND name<>'' AND LOCATE(name,?)>0 ORDER BY LENGTH(name) DESC,id LIMIT 31",TenantContextHolder.getRequiredTenantId(),entityQuestion);
            if(rows.size()>30)throw new NeedClarification("NEED_FILTER",master.kind+"匹配超过30条，请用具体编码查询。",List.of());
            List<Map<String,Object>> found=new ArrayList<>();int longest=0;
            for(var row:rows){String code=text(row.get("code")),name=text(row.get("name"));boolean exactCode=codes.stream().anyMatch(c->c.equalsIgnoreCase(code));if(!name.isBlank()&&entityQuestion.contains(name))consumedNames.add(name);if(exactCode){found.add(row);matchedCodes.add(code.toUpperCase(Locale.ROOT));continue;}if(!name.isBlank()&&entityQuestion.contains(name)){if(name.length()>longest){found.removeIf(r->!codes.stream().anyMatch(c->c.equalsIgnoreCase(text(r.get("code")))));longest=name.length();}if(name.length()==longest)found.add(row);}}
            if(found.stream().anyMatch(row->codes.stream().anyMatch(code->code.equalsIgnoreCase(text(row.get("code"))))))found.removeIf(row->codes.stream().noneMatch(code->code.equalsIgnoreCase(text(row.get("code")))));
            if(selected&&slots.containsKey(master.slot))continue;
            // A specific new name replaces a previously confirmed filter; silence preserves it.
            if(found.size()>1){var choices=new ArrayList<Map<String,Object>>();for(var row:found.stream().limit(30).toList())choices.add(map("label",master.kind+"："+row.get("name")+"（"+row.get("code")+"）","slots",map(master.slot,row.get("id"))));throw new NeedClarification("ENTITY_AMBIGUOUS","匹配到多个"+master.kind+"，请选择具体记录。",choices);}
            if(found.size()==1){slots.put(master.slot,found.get(0).get("id"));singleMatches.add(map("master",master,"row",found.get(0)));}
        }
        if(!selected)for(var hit:singleMatches){@SuppressWarnings("unchecked") var row=(Map<String,Object>)hit.get("row");String name=text(row.get("name")),code=text(row.get("code"));var same=singleMatches.stream().filter(other->{@SuppressWarnings("unchecked") var r=(Map<String,Object>)other.get("row");return (!name.isBlank()&&name.equals(text(r.get("name"))))||(!code.isBlank()&&code.equalsIgnoreCase(text(r.get("code")))&&codes.stream().anyMatch(c->c.equalsIgnoreCase(code)));}).toList();if(same.size()>1){var choices=new ArrayList<Map<String,Object>>();for(var match:same){Master master=(Master)match.get("master");@SuppressWarnings("unchecked") var matched=(Map<String,Object>)match.get("row");var values=new LinkedHashMap<String,Object>();for(var other:MASTERS)values.put(other.slot,null);values.put(master.slot,matched.get("id"));choices.add(map("label",master.kind+"："+matched.get("name")+"（"+matched.get("code")+"）","slots",values));}throw new NeedClarification("ENTITY_AMBIGUOUS","该名称或编码对应不同类型的资料，请选择物料、分类或仓库。",choices);}}
        if(!codes.isEmpty()&&matchedCodes.isEmpty()&&!slots.containsKey("batchCode"))throw new NeedClarification("NEED_FILTER","未匹配到当前租户的物料、分类或仓库编码，请核对完整编码。",List.of());
        String remainder=YEAR.matcher(q).replaceAll("");remainder=MONTH.matcher(remainder).replaceAll("");
        if(q.strip().matches("[12][0-9]{3}"))remainder="";
        if(slots.get("batchCode")!=null)remainder=remainder.replace(slots.get("batchCode").toString(),"");
        for(String code:codes)remainder=remainder.replace(code,"");for(String name:consumedNames)remainder=remainder.replace(name,"");
        remainder=remainder.replaceAll("上个月|这个月|本月|今年|去年|所有仓库|全部仓库|按批次|按仓库|按物料|按产品|批次号|批次|库存台账|库存流水|台账|收发存|入库和出库|入库出库|进出库|库存|入库|出库|查询|查看|统计|只看|仅看|换成|改为|继续|再看|帮我|全部|所有|请|查|那|一下|的|情况|合计|汇总|明细|数量|多少|[\\s，。？！?、：:（）()\\-]","");
        if(!remainder.isBlank())throw new NeedClarification("NEED_FILTER","未能确认筛选名称“"+remainder.substring(0,Math.min(60,remainder.length()))+"”，请提供准确物料、分类或仓库名称/编码。",List.of());
    }
    private static String documentCode(String q){Matcher named=Pattern.compile("(?:单据编码|单号|单据|销售出库单?|采购入库单?|领料单|退货单|调拨单)[：:\\s]*([^\\s，。？！?]+)").matcher(q);if(named.find()){String value=named.group(1);if(value.matches("^[\"“].*[\"”]$"))return value.substring(1,value.length()-1);return value.replaceFirst("(?:的?库存流水|的?流水|的明细)$","");}Matcher code=CODE.matcher(q);String found=null;while(code.find()){if(found!=null)throw new NeedClarification("NEED_FILTER","请一次给出一个完整单据编码。",List.of());found=code.group(1);}if(found==null)throw new NeedClarification("NEED_FILTER","请提供完整单据编码以核验库存流水。",List.of());return found;}
    private Map<String,Object> clarify(String reason,String prompt,List<Map<String,Object>> choices,Map<String,Object> slots,String original){var internal=new ArrayList<Map<String,Object>>();var visible=new ArrayList<Map<String,Object>>();for(var choice:choices){String id=UUID.randomUUID().toString();var merged=new LinkedHashMap<>(slots);if(choice.get("slots") instanceof Map<?,?> changed)changed.forEach((key,value)->merged.put(key.toString(),value));internal.add(map("id",id,"label",choice.get("label"),"slots",merged));visible.add(map("id",id,"label",choice.get("label")));}String token=UUID.randomUUID().toString();String expires=OffsetDateTime.now(ZONE).plusMinutes(30).toString();return map("answer",prompt,"mode","RULES","topic",slots.get("intent"),"evidence",map(),"suggestions",List.of(),"generatedAt",stamp(),"status","CLARIFY","clarification",map("token",token,"reason",reason,"prompt",prompt,"expiresAt",expires,"choices",visible,"acceptsFreeText",true),"_pending",map("token",token,"expiresAt",expires,"slots",new LinkedHashMap<>(slots),"originalQuestion",original,"choices",internal));}
    private static Map<String,Object> outOfScope(String answer){return map("answer",answer,"mode","RULES","topic","scope","evidence",map(),"suggestions",List.of(),"generatedAt",stamp(),"status","OUT_OF_SCOPE","clarification",null);}
    @SuppressWarnings("unchecked") private static void addWarning(Map<String,Object> evidence,String code,String message){var warnings=new ArrayList<>((List<Map<String,Object>>)evidence.getOrDefault("warnings",List.of()));warnings.add(map("code",code,"message",message));evidence.put("warnings",warnings);}
    private static String inventoryAnswer(Map<String,Object> evidence){return "已按当前物料分类与计量单位查询全部实物库存月度收发存，共"+evidence.get("total")+"组。各单位分别汇总；期初、入库、出库、期末和证据缺口请以下表为准。";}
    private static String documentAnswer(Map<String,Object> evidence){return "已核验该编码的库存流水，共"+evidence.get("total")+"条。无已记账流水仅表示尚无库存变动，不能据此判断单据不存在。";}
    private static final class NeedClarification extends RuntimeException{final String reason;final List<Map<String,Object>> choices;NeedClarification(String reason,String prompt,List<Map<String,Object>> choices){super(prompt);this.reason=reason;this.choices=choices;}}
}

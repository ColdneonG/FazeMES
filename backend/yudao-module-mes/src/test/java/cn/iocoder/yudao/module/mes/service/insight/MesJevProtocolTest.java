package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MesJevProtocolTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode request() throws Exception {return (ObjectNode)json.readTree("{\"questions\":{\"category\":{\"criteria\":{\"QUALITY\":\"质量\",\"UNKNOWN\":\"未知\"}},\"department\":{\"criteria\":{\"D_1\":\"质量部门\",\"UNKNOWN\":\"未知\"}}}}");}
    private ObjectNode response() throws Exception {return (ObjectNode)json.readTree("{\"model\":\"jev-1.13.0\",\"answers\":{\"category\":{\"type\":\"choice\",\"choice\":\"QUALITY\",\"confidence\":0.8,\"probabilities\":{\"QUALITY\":1,\"UNKNOWN\":0}},\"department\":{\"type\":\"choice\",\"choice\":\"D_1\",\"confidence\":0.8,\"probabilities\":{\"D_1\":1,\"UNKNOWN\":0}}}}");}
    @Test void twoAtomicChoicesValidateAgainstTheActualRequestCriteria() throws Exception {assertDoesNotThrow(()->MesJevProtocol.validate(request(),response()));}
    @ParameterizedTest @ValueSource(strings={"nonTextModel","unknownCategory","confidenceOutOfRange","missingProbability","probabilitySum","selectedNotMaximum","foreignDepartment","departmentOverflow"})
    void malformedProviderDecisionsAreRejected(String failure) throws Exception {
        var request=request();var response=response();var category=(ObjectNode)response.path("answers").path("category");var department=(ObjectNode)response.path("answers").path("department");
        switch(failure){
            case "nonTextModel"->response.put("model",123);
            case "unknownCategory"->category.put("choice","INVENTED");
            case "confidenceOutOfRange"->category.put("confidence",1.1);
            case "missingProbability"->((ObjectNode)category.path("probabilities")).remove("UNKNOWN");
            case "probabilitySum"->((ObjectNode)category.path("probabilities")).put("QUALITY",.5);
            case "selectedNotMaximum"->{((ObjectNode)category.path("probabilities")).put("QUALITY",.2);((ObjectNode)category.path("probabilities")).put("UNKNOWN",.8);}
            case "foreignDepartment"->department.put("choice","D_2");
            case "departmentOverflow"->{String value="D_999999999999999999999";((ObjectNode)request.path("questions").path("department").path("criteria")).remove("D_1");((ObjectNode)request.path("questions").path("department").path("criteria")).put(value,"不可用编号");department.put("choice",value);((ObjectNode)department.path("probabilities")).remove("D_1");((ObjectNode)department.path("probabilities")).put(value,1);}
        }
        assertThrows(ServiceException.class,()->MesJevProtocol.validate(request,response));
    }
}

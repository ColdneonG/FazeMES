package cn.iocoder.yudao.module.mes.service.face;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

@Component
public class FaceWorkerClient {
    private final URI base;
    private final Path tokenFile;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public FaceWorkerClient(@Value("${mes.face-auth.base-url:http://127.0.0.1:8091}") String base,@Value("${mes.face-auth.token-file:.runtime/face-auth/service.key}") String tokenFile) {
        this.base=URI.create(base);
        if(!"http".equals(this.base.getScheme())||!"127.0.0.1".equals(this.base.getHost())||this.base.getPort()<1024
                ||this.base.getUserInfo()!=null||this.base.getQuery()!=null||this.base.getFragment()!=null
                ||!(this.base.getPath().isEmpty()||"/".equals(this.base.getPath())))throw new IllegalArgumentException("Face worker must use loopback HTTP");
        this.tokenFile=Path.of(tokenFile);
    }
    private HttpRequest.Builder request(String path) throws Exception {
        String token=Files.readString(tokenFile).trim();
        if(token.length()<40||token.length()>256)throw new IllegalStateException();
        return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
                .header("X-Faze-Face-Token",token).header("Content-Type","application/json");
    }
    public boolean ready() {
        try {var response=client.send(request("/health").GET().build(),HttpResponse.BodyHandlers.ofString());
            return response.statusCode()==200&&Boolean.TRUE.equals(JsonUtils.parseObject(response.body(),Map.class).get("ready"));}
        catch(Exception ex){return false;}
    }
    public float[] extract(String image) {
        if(image==null||image.length()<64||image.length()>2_000_000)throw new IllegalArgumentException("Invalid frame");
        try {
            var response=client.send(request("/extract").POST(HttpRequest.BodyPublishers.ofString(
                    JsonUtils.toJsonString(Map.of("imageBase64",image)))).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==422)throw new IllegalArgumentException("A single clear face is required");
            if(response.statusCode()!=200||response.body().length()>16_000)throw new IllegalStateException();
            var result=JsonUtils.parseObject(response.body(),Map.class);
            if(!(result.get("feature") instanceof List<?> list)||list.size()!=128)throw new IllegalStateException();
            float[] vector=new float[128];
            for(int i=0;i<128;i++){if(!(list.get(i) instanceof Number n))throw new IllegalStateException();vector[i]=n.floatValue();}
            FaceTemplateCipher.validate(vector);return vector;
        }catch(IllegalArgumentException ex){throw ex;}
        catch(Exception ex){throw new IllegalStateException("Local face worker unavailable");}
    }
}

package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MesJevSystemProxyTest extends MesAssistantTestDatabase {
    private static final URI JEV=URI.create("https://api.typesafe.ai/v1/systemone");
    @Test void readsSharedOrProtocolSpecificWindowsProxyAndHonorsDisabledAndBypass(){
        var registry=MesJevProxySettings.parseRegistry("HKEY_CURRENT_USER\\x\r\n    ProxyEnable    REG_DWORD    0x1\r\n    ProxyServer    REG_SZ    http=127.0.0.1:7890;https=127.0.0.1:7897\r\n    ProxyOverride    REG_SZ    <local>;localhost;*.internal.example\r\n");
        var settings=new MesJevProxySettings("system",true,()->registry);
        assertEquals(7897,settings.resolve(JEV).getPort());assertNull(settings.resolve(URI.create("https://device.internal.example")));
        assertNull(new MesJevProxySettings("system",true,()->Map.of("ProxyEnable","0x0","ProxyServer","127.0.0.1:7897")).resolve(JEV));
        assertEquals(7897,new MesJevProxySettings("system",true,()->Map.of("ProxyEnable","0x1","ProxyServer","127.0.0.1:7897")).resolve(JEV).getPort());
        assertNull(new MesJevProxySettings("direct",true,()->{throw new AssertionError("Direct must not read registry");}).resolve(JEV));
        assertNull(new MesJevProxySettings("system",false,()->{throw new AssertionError("Non-Windows must not read registry");}).resolve(JEV));
    }
    @Test void onlyLocalHttpProxyIsAcceptedAndNoCredentialOrRemoteProxyCanBeConfigured(){
        for(String invalid:List.of("http://192.168.1.2:7897","http://example.com:7897","https://127.0.0.1:7897","socks5://127.0.0.1:7897","http://user:key@127.0.0.1:7897","http://127.0.0.1:7897/path","http://127.0.0.1:7897?key=x","127.0.0.1:0"))assertThrows(ServiceException.class,()->MesJevProxySettings.parse(invalid),invalid);
        assertTrue(MesJevProxySettings.parse("http://localhost:7897").getAddress().isLoopbackAddress());
        assertTrue(new MesJevProxySettings("http://127.0.0.1:7897",false,Map::of).describe().contains("无需开启虚拟网卡"));
    }
    private void configure(String url,int deadline,int proxyPort){
        jdbc.execute("CREATE TABLE mes_insight_jev_config(tenant_id BIGINT PRIMARY KEY,model_name VARCHAR(100),secret_ciphertext VARCHAR(2048),enabled BOOLEAN)");
        jdbc.update("INSERT INTO mes_insight_jev_config VALUES(1,'jev-1.13.0',?,TRUE)",secrets.encrypt("PRIVATE-MODEL-KEY-MUST-NOT-LEAK",1));
        models=new MesAssistantModelGateway(jdbc,json,secrets,url,deadline);
        if(proxyPort>0)ReflectionTestUtils.setField(models,"jevProxySettings",new MesJevProxySettings("http://127.0.0.1:"+proxyPort,false,Map::of));
    }
    private void invoke(){models.evaluateJev(MesAssistantModelGateway.Purpose.JEV_CONNECTION_TEST,"{\"model\":\"jev-1.13.0\"}".getBytes(StandardCharsets.UTF_8));}
    @Test void connectPinsTheValidatedDestinationAndDoesNotLeakKeyOrFallBackToDirect() throws Exception {
        try(var listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            configure("https://203.0.113.10/v1",1500,listener.getLocalPort());var worker=Executors.newSingleThreadExecutor();
            try{
                var request=worker.submit(()->{try(var client=listener.accept()){client.setSoTimeout(2000);var reader=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.US_ASCII));var received=new StringBuilder();for(String line=reader.readLine();line!=null&&!line.isEmpty();line=reader.readLine())received.append(line).append('\n');client.getOutputStream().write("HTTP/1.1 502 Proxy failure\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));return received.toString();}});
                var error=assertThrows(ServiceException.class,this::invoke);String received=request.get(3,TimeUnit.SECONDS);
                assertTrue(received.startsWith("CONNECT 203.0.113.10:443 HTTP/1.1"));assertFalse(received.contains("PRIVATE-MODEL-KEY"));assertFalse(received.contains("Authorization"));assertTrue(error.getMessage().contains("系统HTTP代理"));
            }finally{worker.shutdownNow();}
        }
    }
    @Test void anAcceptedTunnelMustCompleteTlsBeforeTheKeyCanBeSent() throws Exception {
        try(var listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            configure("https://203.0.113.10/v1",1500,listener.getLocalPort());var worker=Executors.newSingleThreadExecutor();
            try{
                var tls=worker.submit(()->{try(var client=listener.accept()){client.setSoTimeout(2000);var in=client.getInputStream();int matched=0;while(matched<4){int value=in.read();if(value<0)throw new EOFException();matched=value=="\r\n\r\n".charAt(matched)?matched+1:0;}client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));return in.readNBytes(6);}});
                assertThrows(ServiceException.class,this::invoke);assertEquals(0x16,tls.get(3,TimeUnit.SECONDS)[0]&255,"First tunneled bytes must be TLS, never a plaintext model request");
            }finally{worker.shutdownNow();}
        }
    }
    @Test void officialRegionDenialIsExplainedWithoutSuggestingChangingTheKey() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/systemone",exchange->{exchange.getRequestBody().readAllBytes();byte[] response="{\"status\":451}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(451,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
        try{configure("http://127.0.0.1:"+server.getAddress().getPort()+"/v1",1500,0);var error=assertThrows(ServiceException.class,this::invoke);assertTrue(error.getMessage().contains("当前网络地区"));assertTrue(error.getMessage().contains("HTTP 451"));}
        finally{server.stop(0);}
    }
}

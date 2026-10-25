package cn.iocoder.yudao.module.mes.service.assistant;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Jev-only transport policy. Reads Windows static system proxy without changing OS or Clash settings. */
@Component
public class MesJevProxySettings {
    private final String mode;
    private final boolean windows;
    private final Supplier<Map<String,String>> system;
    private volatile Map<String,String> cached;
    private volatile long refreshAt;
    @org.springframework.beans.factory.annotation.Autowired
    public MesJevProxySettings(Environment environment){
        this(environment.getProperty("mes.jev.proxy-url","system"),System.getProperty("os.name","").startsWith("Windows"),MesJevProxySettings::readWindowsProxy);
    }
    MesJevProxySettings(String mode,boolean windows,Supplier<Map<String,String>> system){this.mode=mode.strip();this.windows=windows;this.system=system;}
    public InetSocketAddress resolve(URI destination){
        if(mode.equalsIgnoreCase("direct"))return null;
        if(!mode.equalsIgnoreCase("system"))return parse(mode);
        if(!windows)return null;
        var state=state();if(!"0x1".equalsIgnoreCase(state.get("ProxyEnable")))return null;
        for(String bypass:state.getOrDefault("ProxyOverride","").split(";")){
            String value=bypass.strip();if(value.isEmpty())continue;
            if(value.equalsIgnoreCase("<local>")&&!destination.getHost().contains("."))return null;
            String expression=Arrays.stream(value.split("\\*",-1)).map(Pattern::quote).collect(java.util.stream.Collectors.joining(".*"));
            if(destination.getHost().matches("(?i)"+expression))return null;
        }
        String server=state.getOrDefault("ProxyServer","").strip();
        if(server.contains("=")){
            String selected=null;
            for(String item:server.split(";")){String[] pair=item.strip().split("=",2);if(pair.length==2&&pair[0].equalsIgnoreCase(destination.getScheme()))selected=pair[1].strip();}
            if(selected==null)return null;server=selected;
        }
        if(server.isBlank())throw MesAssistantErrors.model("系统代理已启用但未设置服务器，请检查Clash的系统代理开关和HTTP端口");
        return parse(server);
    }
    private Map<String,String> state(){
        long now=System.nanoTime();if(cached!=null&&now<refreshAt)return cached;
        synchronized(this){if(cached==null||now>=refreshAt){cached=Map.copyOf(system.get());refreshAt=now+TimeUnit.SECONDS.toNanos(3);}return cached;}
    }
    static InetSocketAddress parse(String value){
        try{
            URI uri=URI.create(value.contains("://")?value:"http://"+value);
            String host=uri.getHost();if(!"http".equalsIgnoreCase(uri.getScheme())||host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!Set.of("","/").contains(uri.getPath())||uri.getPort()<1||uri.getPort()>65535)throw new IllegalArgumentException();
            String normalized=host.replace("[","").replace("]","");
            if(!Set.of("127.0.0.1","localhost","::1").contains(normalized.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException();
            return new InetSocketAddress(InetAddress.getByName(normalized.equalsIgnoreCase("localhost")?"127.0.0.1":normalized),uri.getPort());
        }catch(Exception ex){throw MesAssistantErrors.model("Jev系统代理配置无效：请使用本机HTTP代理，例如127.0.0.1:7897；不支持代理凭据或远程代理地址");}
    }
    public String describe(){
        try{var proxy=resolve(URI.create("https://api.typesafe.ai/v1/systemone"));return proxy==null?"直连（当前未使用系统HTTP代理）":"本机HTTP代理 "+proxy.getHostString()+":"+proxy.getPort()+"（无需开启虚拟网卡）";}
        catch(cn.iocoder.yudao.framework.common.exception.ServiceException ex){return ex.getMessage();}
    }
    static Map<String,String> parseRegistry(String output){
        var values=new LinkedHashMap<String,String>();var matcher=Pattern.compile("(?m)^\\s*(ProxyEnable|ProxyServer|ProxyOverride)\\s+REG_\\w+\\s+([^\\r\\n]+)").matcher(output);
        while(matcher.find())values.put(matcher.group(1),matcher.group(2).strip());return values;
    }
    private static Map<String,String> readWindowsProxy(){
        Process process=null;
        try{
            process=new ProcessBuilder("reg.exe","query","HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings").redirectErrorStream(true).start();
            if(!process.waitFor(2,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException();
            byte[] output=process.getInputStream().readNBytes(32769);if(output.length>32768)throw new IllegalStateException();
            return parseRegistry(new String(output,Charset.defaultCharset()));
        }catch(Exception ex){if(ex instanceof InterruptedException)Thread.currentThread().interrupt();throw MesAssistantErrors.model("无法读取Windows系统代理，请检查系统设置，或由管理员设置mes.jev.proxy-url为本机HTTP代理地址");}
        finally{if(process!=null&&process.isAlive())process.destroyForcibly();}
    }
}

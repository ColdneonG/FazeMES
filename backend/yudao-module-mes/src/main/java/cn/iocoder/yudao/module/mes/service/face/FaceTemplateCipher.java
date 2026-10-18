package cn.iocoder.yudao.module.mes.service.face;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/** Biometric feature templates are encrypted; raw photographs are never persisted. */
@Component
public class FaceTemplateCipher {
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();
    public FaceTemplateCipher(@Value("${mes.face-auth.master-key-file:.runtime/face-auth/master.key}") String keyFile) { this.keyFile=Path.of(keyFile); }
    private synchronized byte[] key() throws Exception {
        Path parent=keyFile.toAbsolutePath().getParent(); Files.createDirectories(parent);
        if(!Files.exists(keyFile)) {
            byte[] fresh=new byte[32]; random.nextBytes(fresh);
            try { Files.writeString(keyFile,Base64.getEncoder().encodeToString(fresh),StandardOpenOption.CREATE_NEW); }
            catch(FileAlreadyExistsException ignored) { }
        }
        byte[] result=Base64.getDecoder().decode(Files.readString(keyFile).trim());
        if(result.length!=32)throw new IllegalStateException("Invalid local face master key");
        return result;
    }
    public String encrypt(long tenant,long user,List<float[]> vectors) {
        if(vectors==null||vectors.isEmpty()||vectors.size()>3)throw new IllegalArgumentException("One to three face features required");
        try {
            ByteBuffer plain=ByteBuffer.allocate(4+vectors.size()*128*4).putInt(vectors.size());
            for(float[] v:vectors){ validate(v);for(float f:v)plain.putFloat(f); }
            byte[] iv=new byte[12];random.nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,iv));
            cipher.updateAAD((tenant+":"+user).getBytes(StandardCharsets.UTF_8));
            byte[] sealed=cipher.doFinal(plain.array());
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length+sealed.length).put(iv).put(sealed).array());
        } catch(Exception ex){throw new IllegalStateException("Face template encryption unavailable");}
    }
    public List<float[]> decrypt(long tenant,long user,String encrypted) {
        try {
            byte[] all=Base64.getDecoder().decode(encrypted);
            if(all.length<28||all.length>4096)throw new IllegalArgumentException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,Arrays.copyOf(all,12)));
            cipher.updateAAD((tenant+":"+user).getBytes(StandardCharsets.UTF_8));
            ByteBuffer plain=ByteBuffer.wrap(cipher.doFinal(Arrays.copyOfRange(all,12,all.length)));
            int count=plain.getInt();
            if(count<1||count>3||plain.remaining()!=count*128*4)throw new IllegalArgumentException();
            List<float[]> result=new ArrayList<>();
            for(int i=0;i<count;i++){float[] vector=new float[128];for(int j=0;j<128;j++)vector[j]=plain.getFloat();validate(vector);result.add(vector);}
            return result;
        }catch(Exception ex){throw new IllegalStateException("Face template cannot be decrypted");}
    }
    public static void validate(float[] vector) {
        if(vector==null||vector.length!=128)throw new IllegalArgumentException("Invalid face feature");
        double norm=0;for(float value:vector){if(!Float.isFinite(value))throw new IllegalArgumentException("Invalid face feature");norm+=value*value;}
        if(Math.abs(Math.sqrt(norm)-1)>0.02)throw new IllegalArgumentException("Invalid face feature");
    }
}

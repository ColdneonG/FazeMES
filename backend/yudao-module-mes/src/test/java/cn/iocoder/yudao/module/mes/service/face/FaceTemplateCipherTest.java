package cn.iocoder.yudao.module.mes.service.face;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FaceTemplateCipherTest {
    @TempDir Path directory;
    private FaceTemplateCipher cipher() { return new FaceTemplateCipher(directory.resolve("master.key").toString()); }
    private float[] vector(int dimension) { float[] value=new float[128];value[dimension]=1;return value; }

    @Test
    void roundTripsOneToThreeTemplatesAndUsesRandomizedAuthenticatedEncryption() throws Exception {
        FaceTemplateCipher cipher=cipher();
        for(int count=1;count<=3;count++) {
            List<float[]> vectors=java.util.stream.IntStream.range(0,count).mapToObj(this::vector).toList();
            String first=cipher.encrypt(1,42,vectors),second=cipher.encrypt(1,42,vectors);
            assertNotEquals(first,second);assertEquals(count,cipher.decrypt(1,42,first).size());
            for(int index=0;index<count;index++)assertArrayEquals(vectors.get(index),cipher.decrypt(1,42,first).get(index));
        }
        assertEquals(32,Base64.getDecoder().decode(Files.readString(directory.resolve("master.key"))).length);
        // A fresh service instance uses the same persisted key, while IVs remain unique per encryption.
        String sealed=cipher.encrypt(1,42,List.of(vector(7)));
        assertArrayEquals(vector(7),cipher().decrypt(1,42,sealed).get(0));
    }
    @Test
    void aadPreventsTemplateExchangeAcrossTenantsAndAccountsAndRejectsBitFlips() {
        FaceTemplateCipher cipher=cipher();String sealed=cipher.encrypt(1,42,List.of(vector(0)));
        assertThrows(IllegalStateException.class,()->cipher.decrypt(2,42,sealed));
        assertThrows(IllegalStateException.class,()->cipher.decrypt(1,43,sealed));
        byte[] modified=Base64.getDecoder().decode(sealed);modified[modified.length-1]^=1;
        assertThrows(IllegalStateException.class,()->cipher.decrypt(1,42,Base64.getEncoder().encodeToString(modified)));
        assertThrows(IllegalStateException.class,()->cipher.decrypt(1,42,"not base64"));
        assertThrows(IllegalStateException.class,()->cipher.decrypt(1,42,Base64.getEncoder().encodeToString(new byte[5000])));
    }
    @Test
    void rejectsNonFiniteWrongDimensionAndNonUnitVectors() {
        FaceTemplateCipher cipher=cipher();
        float[] nan=vector(0);nan[2]=Float.NaN;
        float[] infinity=vector(0);infinity[2]=Float.POSITIVE_INFINITY;
        for(float[] invalid:List.of(new float[127],new float[129],new float[128],nan,infinity)) {
            assertThrows(IllegalArgumentException.class,()->FaceTemplateCipher.validate(invalid));
            assertThrows(RuntimeException.class,()->cipher.encrypt(1,42,List.of(invalid)));
        }
        assertThrows(IllegalArgumentException.class,()->FaceTemplateCipher.validate(null));
    }
    @Test
    void wrongKeyCannotDecryptAndNeverFallsBackToPlaintext() throws Exception {
        FaceTemplateCipher cipher=cipher();String sealed=cipher.encrypt(1,42,List.of(vector(0)));
        byte[] replacement=new byte[32];new java.security.SecureRandom().nextBytes(replacement);
        Files.writeString(directory.resolve("master.key"),Base64.getEncoder().encodeToString(replacement));
        assertThrows(IllegalStateException.class,()->cipher.decrypt(1,42,sealed));
        Files.writeString(directory.resolve("master.key"),"broken key");
        assertThrows(IllegalStateException.class,()->cipher.encrypt(1,42,List.of(vector(0))));
    }
}

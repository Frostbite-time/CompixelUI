package dev.composemc.sync;

import java.io.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnumCodecTest {
    enum Mode { OFF, ON }
    enum Other { OFF, ON }
    @Test void distinctEnumDefinitionsCannotShareTheWireIdentity() {
        assertNotEquals(SyncCodecs.enumeration(Mode.class).id(), SyncCodecs.enumeration(Other.class).id());
        assertEquals(SyncCodecs.enumeration(Mode.class).id(), SyncCodecs.enumeration(Mode.class).id());
    }
    @Test void aCompleteValueRoundTripsAndInvalidInputIsRejected() throws Exception {
        var codec = SyncCodecs.enumeration(Mode.class);
        var bytes = new ByteArrayOutputStream();
        codec.write(new DataOutputStream(bytes), Mode.ON);
        assertEquals(1, bytes.size());
        assertSame(Mode.ON, codec.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        assertThrows(IOException.class, () -> codec.read(new DataInputStream(new ByteArrayInputStream(new byte[]{2}))));
        assertThrows(EOFException.class, () -> codec.read(new DataInputStream(new ByteArrayInputStream(new byte[0]))));
    }
}

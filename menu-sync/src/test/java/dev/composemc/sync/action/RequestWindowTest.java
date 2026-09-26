package dev.composemc.sync.action;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestWindowTest {
    @Test void oldSessionCannotConsumeTheNewSessionsSequence() {
        UUID current = UUID.randomUUID();
        var window = new RequestWindow(current, 2);
        assertEquals(RequestWindow.Admission.STALE, window.admit(UUID.randomUUID(), 1, 20));
        assertEquals(RequestWindow.Admission.ACCEPT, window.admit(current, 1, 20));
        assertEquals(RequestWindow.Admission.STALE, window.admit(current, 1, 21));
        assertEquals(RequestWindow.Admission.STALE, window.admit(current, Long.MAX_VALUE, 21));
        assertEquals(RequestWindow.Admission.ACCEPT, window.admit(current, 2, 21));
    }
    @Test void exhaustedRequestsCannotReplayOnTheNextTick() {
        UUID token = UUID.randomUUID(); var window = new RequestWindow(token, 2);
        assertEquals(RequestWindow.Admission.ACCEPT, window.admit(token, 1, 1));
        assertEquals(RequestWindow.Admission.ACCEPT, window.admit(token, 2, 1));
        assertEquals(RequestWindow.Admission.THROTTLED, window.admit(token, 3, 1));
        assertEquals(RequestWindow.Admission.STALE, window.admit(token, 3, 2));
        assertEquals(RequestWindow.Admission.ACCEPT, window.admit(token, 4, 2));
    }
}

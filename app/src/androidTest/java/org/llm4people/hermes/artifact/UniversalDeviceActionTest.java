package org.llm4people.hermes.artifact;

import android.content.Intent;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import junit.framework.TestCase;

import java.io.IOException;
import java.util.List;

public final class UniversalDeviceActionTest extends TestCase {
    public void testAlarmAndTimerActionsKeepSkipUiFalse() throws Exception {
        DeviceAction alarm = DeviceAction.parseJson(
                "{\"kind\":\"set_alarm\",\"hour\":7,\"minute\":30,\"label\":\"Réveil\",\"days\":[2,3,4,5,6]}");
        assertEquals(DeviceAction.Kind.ALARM, alarm.kind());
        assertTrue(alarm.preview().contains("07 h 30"));
        assertTrue(alarm.preview().contains("Lun"));
        Intent alarmIntent = alarm.target();
        assertEquals(AlarmClock.ACTION_SET_ALARM, alarmIntent.getAction());
        assertEquals(7, alarmIntent.getIntExtra(AlarmClock.EXTRA_HOUR, -1));
        assertEquals(30, alarmIntent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1));
        assertFalse(alarmIntent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true));
        assertEquals(List.of(2, 3, 4, 5, 6), alarmIntent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS));

        DeviceAction timer = DeviceAction.parseJson(
                "{\"kind\":\"set_timer\",\"seconds\":900,\"label\":\"Pâtes\"}");
        assertEquals(DeviceAction.Kind.TIMER, timer.kind());
        assertTrue(timer.preview().contains("15 min"));
        Intent timerIntent = timer.target();
        assertEquals(AlarmClock.ACTION_SET_TIMER, timerIntent.getAction());
        assertEquals(900, timerIntent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1));
        assertFalse(timerIntent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true));
    }

    public void testDialSmsEmailContactNavigationAndClipboardActions() throws Exception {
        DeviceAction dial = DeviceAction.parseJson("{\"kind\":\"dial_draft\",\"number\":\"+1 514-555-0100\"}");
        assertEquals(Intent.ACTION_DIAL, dial.target().getAction());
        assertTrue(dial.target().getDataString().startsWith("tel:"));

        DeviceAction sms = DeviceAction.parseJson(
                "{\"kind\":\"sms_draft\",\"recipient\":\"+1 514-555-0100\",\"message\":\"J’arrive dans 5 min.\"}");
        assertEquals(Intent.ACTION_SENDTO, sms.target().getAction());
        assertTrue(sms.target().getDataString().startsWith("smsto:"));
        assertEquals("J’arrive dans 5 min.", sms.target().getStringExtra("sms_body"));

        DeviceAction email = DeviceAction.parseJson(
                "{\"kind\":\"email_draft\",\"recipient\":\"equipe@exemple.fr\",\"subject\":\"Compte rendu\",\"body\":\"Bonjour\"}");
        assertEquals(Intent.ACTION_SENDTO, email.target().getAction());
        assertEquals("mailto:", email.target().getDataString());
        assertEquals("Compte rendu", email.target().getStringExtra(Intent.EXTRA_SUBJECT));

        DeviceAction contact = DeviceAction.parseJson(
                "{\"kind\":\"insert_contact\",\"name\":\"Camille Tremblay\",\"phone\":\"514-555-0199\",\"company\":\"Atelier\"}");
        assertEquals(ContactsContract.Intents.Insert.ACTION, contact.target().getAction());
        assertEquals("Camille Tremblay", contact.target().getStringExtra(ContactsContract.Intents.Insert.NAME));

        DeviceAction nav = DeviceAction.parseJson(
                "{\"kind\":\"map_navigation\",\"destination\":\"Gare Centrale, Montréal\",\"mode\":\"walking\"}");
        assertEquals(Intent.ACTION_VIEW, nav.target().getAction());
        assertTrue(nav.target().getDataString().contains("mode=w"));
        assertTrue(nav.preview().contains("À pied"));

        DeviceAction clip = DeviceAction.parseJson("{\"kind\":\"copy_sensitive\",\"text\":\"CODE-1234\"}");
        assertEquals(DeviceAction.Kind.CLIPBOARD, clip.kind());
        assertNull(clip.target());
        assertEquals("CODE-1234", clip.preview());
    }

    public void testRejectsInvalidOrUnsafeActionPayloads() {
        for (String bad : new String[]{
                "{\"kind\":\"set_alarm\",\"hour\":24,\"minute\":0}",
                "{\"kind\":\"set_alarm\",\"hour\":7,\"minute\":60}",
                "{\"kind\":\"set_alarm\",\"hour\":7,\"minute\":0,\"days\":[0]}",
                "{\"kind\":\"set_alarm\",\"hour\":7,\"minute\":0,\"days\":[2,2]}",
                "{\"kind\":\"set_timer\",\"seconds\":0}",
                "{\"kind\":\"set_timer\",\"seconds\":86401}",
                "{\"kind\":\"dial_draft\",\"number\":\"not-a-phone\"}",
                "{\"kind\":\"email_draft\",\"recipient\":\"bad-email\"}",
                "{\"kind\":\"insert_contact\"}",
                "{\"kind\":\"map_navigation\",\"destination\":\"Lyon\",\"mode\":\"teleport\"}",
                "{\"kind\":\"copy_sensitive\",\"text\":\"   \"}",
                "{\"kind\":\"set_timer\",\"seconds\":10,\"extra\":\"nope\"}"
        }) {
            try {
                DeviceAction.parseJson(bad);
                fail("Should have rejected: " + bad);
            } catch (IOException expected) {
                // ok
            }
        }
    }
}

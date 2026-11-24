package org.llm4people.hermes.customization;

import junit.framework.TestCase;

import org.json.JSONObject;
import org.llm4people.hermes.JsonValues;

/** Android instrumentation tests verifying Spec.fromJson delegates to fromMap with JSONObject.NULL & ttl parity. */
public final class InteractiveCardJsonParityTest extends TestCase {
    private static final String VALID_LINK =
            "hermes://pair?host=https%3A%2F%2Fhermes.example.com&code=InviteCode987654&profile=alex&mode=invite";

    public void testFromJsonHandlesJsonObjectNullFallbacksAndTtlParityWithFromMap() throws Exception {
        JSONObject pairing = new JSONObject()
                .put("mode", JSONObject.NULL)
                .put("deep_link", JSONObject.NULL)
                .put("link", VALID_LINK)
                .put("profile_slug", "alex")
                .put("display_name", JSONObject.NULL)
                .put("expires_at", JSONObject.NULL)
                .put("ttl_seconds", JSONObject.NULL);
        JSONObject card = new JSONObject()
                .put("id", "card_json_1")
                .put("kind", "pairing_invite")
                .put("title", "Inviter Alex")
                .put("subtitle", JSONObject.NULL)
                .put("pairing", pairing);

        InteractiveCardView.Spec fromJson = InteractiveCardView.Spec.fromJson(card);
        InteractiveCardView.Spec fromMap = InteractiveCardView.Spec.fromMap(JsonValues.toMap(card));

        assertEquals(fromMap.cardId, fromJson.cardId);
        assertEquals("", fromJson.subtitle);
        assertEquals("device", fromJson.pairingInvite.mode());
        assertEquals(VALID_LINK, fromJson.pairingInvite.deepLink());
        assertEquals("", fromJson.pairingInvite.displayName());
        assertEquals(300, fromJson.pairingInvite.ttlSeconds());
        assertEquals(fromMap.pairingInvite, fromJson.pairingInvite);

        // Explicit ttl_seconds integer matches identically across fromJson and fromMap.
        pairing.put("ttl_seconds", 120);
        assertEquals(120, InteractiveCardView.Spec.fromJson(card).pairingInvite.ttlSeconds());

        // Customization preview with accent: JSONObject.NULL falls back cleanly to accent_preset.
        JSONObject cust = new JSONObject()
                .put("accent_hex", JSONObject.NULL)
                .put("accent", JSONObject.NULL)
                .put("accent_preset", "indigo")
                .put("density", "compact");
        JSONObject custCard = new JSONObject()
                .put("id", "card_json_2")
                .put("kind", "customization_preview")
                .put("title", "Indigo")
                .put("customization", cust);

        InteractiveCardView.Spec parsedCust = InteractiveCardView.Spec.fromJson(custCard);
        assertEquals(AppCustomization.PalettePreset.INDIGO, parsedCust.customizationDelta.palette());
        assertEquals(AppCustomization.Density.COMPACT, parsedCust.customizationDelta.density());

        // Null JSON fails closed.
        try {
            InteractiveCardView.Spec.fromJson(null);
            fail("Expected null JSONObject rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}

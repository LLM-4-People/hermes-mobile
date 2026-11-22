package org.llm4people.hermes.push;

import com.google.firebase.FirebaseOptions;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

final class PushConfig {
    final String applicationId;
    final String apiKey;
    final String projectId;
    final String senderId;

    PushConfig(String applicationId, String apiKey, String projectId, String senderId) {
        this.applicationId = applicationId;
        this.apiKey = apiKey;
        this.projectId = projectId;
        this.senderId = senderId;
    }

    static boolean offers(JSONObject discovery, String transport) {
        org.json.JSONArray providers = discovery.optJSONArray("providers");
        if (providers == null) return transport.equals(discovery.optString("provider"));
        for (int i = 0; i < providers.length(); i++)
            if (transport.equals(providers.optString(i))) return true;
        return false;
    }

    static PushConfig parse(JSONObject discovery) throws IOException {
        if (!offers(discovery, "fcm")) throw new IOException("provider");
        try {
            JSONObject firebase = discovery.getJSONObject("firebase");
            String app = firebase.getString("applicationId");
            String key = firebase.getString("apiKey");
            String project = firebase.getString("projectId");
            String sender = firebase.getString("gcmSenderId");
            if (!sender.matches("[0-9]{1,24}") || !app.startsWith("1:" + sender + ":android:")
                    || app.length() > 160 || key.isEmpty() || key.length() > 256
                    || project.isEmpty() || project.length() > 128) throw new IOException("discovery");
            return new PushConfig(app, key, project, sender);
        } catch (JSONException error) {
            throw new IOException("discovery", error);
        }
    }

    FirebaseOptions options() {
        return new FirebaseOptions.Builder().setApplicationId(applicationId).setApiKey(apiKey)
                .setProjectId(projectId).setGcmSenderId(senderId).build();
    }

    boolean matches(FirebaseOptions current) {
        return applicationId.equals(current.getApplicationId()) && projectId.equals(current.getProjectId())
                && senderId.equals(current.getGcmSenderId()) && apiKey.equals(current.getApiKey());
    }
}

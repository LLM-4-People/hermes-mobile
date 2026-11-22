# Prepare an Android action in a conversation

Ask Hermes in your own words. “Ajoute mon atelier de dessin à mon agenda”, “Prépare ce message pour mon équipe” and “Montre-moi le lieu sur la carte” are all requests Hermes can turn into proposals you review on the phone. Specify the date, start and end time, and time zone for a calendar event. A place name or street address is enough for a map search.

| Request | What happens after you review and tap |
| --- | --- |
| Calendar event | Android opens a calendar editor with the title, dates and optional place. You choose whether to save. |
| Text to share | Android opens the share sheet with the proposed text. You choose a destination and finish there. |
| Place on a map | Android asks a compatible maps app to search for the proposed place. The request contains the search text. |

The proposal appears in the conversation where you asked for it. Opening, pinning or comparing a page leaves its actions pending. The receiving app and its availability depend on the device. A phone that does not recognize the block can still display the assistant’s explanation and the proposal source. Model output alone does not confirm a save or delivery. [Android common intents](https://developer.android.com/guide/components/intents-common) and [Android Sharesheet](https://developer.android.com/training/sharing/send)

## One request for a workshop

> Prépare un rendez-vous pour l’atelier de dessin du 12 avril 2027, de 14 h à 15 h à Laval (UTC−04:00). J’aimerais aussi partager un rappel avec mon équipe et trouver la Maison des arts de Laval sur la carte.

A compatible assistant can return this page. The calendar action carries its own dates. The map and share actions each carry only the value shown in their review.

```hermes-workspace
{
  "version": 1,
  "surface": "page/atelier-dessin",
  "mode": "replace",
  "title": "Atelier de dessin",
  "blocks": [
    {
      "type": "device",
      "request": {
        "kind": "calendar_event",
        "title": "Atelier de dessin",
        "start": "2027-04-12T14:00:00-04:00",
        "end": "2027-04-12T15:00:00-04:00",
        "location": "Maison des arts de Laval"
      }
    },
    {
      "type": "device",
      "request": {
        "kind": "share_text",
        "text": "Rendez-vous pour l’atelier de dessin le 12 avril à 14 h à la Maison des arts de Laval."
      }
    },
    {
      "type": "device",
      "request": {"kind": "map_search", "query": "Maison des arts de Laval"}
    }
  ]
}
```

## What the gateway supplies

The mobile app sends its supported action shapes in the existing `instructions` field of `POST /v1/runs`. The pinned gateway passes those instructions and the selected conversation to the model. An assistant reply supplies the page document. Preparing or opening one of its device actions requires no new gateway route, plugin or provider credential. Other API clients retain their own instructions. [Pinned Hermes run admission](https://github.com/LLM-4-People/hermes-agent/blob/3f194698f9cb586d3302129e744ed30e18c3d6ff/gateway/platforms/api_server_runs.py#L949)

Requests to work on the gateway use the active profile’s tools. For example, ask Hermes to summarize research, review files in its workspace or schedule a recurring reminder. The pinned fork includes toolsets for web research, browsing, files, terminal work, skills, memory and scheduled tasks. Availability depends on configuration and the current run’s selections. Authenticated `GET /v1/capabilities` describes the API, `GET /v1/toolsets` lists each toolset’s configuration and enabled state, and `GET /v1/skills` lists installed skills visible to the profile. The phone’s **Modèle et outils** panel controls the model and tools used for a discussion. [Pinned discovery handlers](https://github.com/LLM-4-People/hermes-agent/blob/3f194698f9cb586d3302129e744ed30e18c3d6ff/gateway/platforms/api_server.py#L2358), [pinned toolsets](https://github.com/LLM-4-People/hermes-agent/blob/3f194698f9cb586d3302129e744ed30e18c3d6ff/toolsets.py#L72), [Hermes API server](https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server) and [Hermes tools](https://hermes-agent.nousresearch.com/docs/user-guide/features/tools)

<!-- Generated from docs/sources/docs/android-actions.md.in by scripts/docs.py. -->

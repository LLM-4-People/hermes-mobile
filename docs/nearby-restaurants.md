# Find restaurants near a place

Ask Hermes “Find restaurants near me.” You can share an approximate position for that message or enter an area such as “Rosemont, Montreal”. The location choice is separate from the words you send. If you use your device location, Android asks for permission in the foreground. For a one-time request, check that “Approximate location attached” is visible, then tap **Send**. Choose a single send or enable location for future sends on this profile. You can turn that preference off at any time.

The configured gateway and model receive the approved area with the request. A device sample includes the rounded position, its approximate accuracy and its age. The gateway can retain the approved run instructions in its admission records, and the model provider may retain requests under its own settings. Turning off future sharing does not erase earlier requests. A manual area is sent as plain place text. The restaurant lookup currently needs a rounded device position. When you enter a neighborhood in **Specify a neighborhood**, Hermes prepares an editable question. Send it to ask about the named area. The model can search using its configured live tools and cite the results. Without live search, it can prepare a map search such as “restaurants in Rosemont, Montreal”. Review it and tap **Open map** to see the search in your maps app. Hermes can suggest a native restaurant guide. The guide reads named restaurants from the gateway’s OpenStreetMap search and displays source links. Each item comes from an identified map record. A missing cuisine tag means the cuisine is unknown. Distance describes the separation from the rounded search point. For a precise destination, open the place in a map.

If the gateway cannot confine the area to this run, Hermes keeps the draft and offers **Send without location**. You can continue the conversation with your original message or update the gateway to share an area. The native restaurant list also requires the gateway’s nearby-places plugin and a configured data source. [Gateway setup and source contract](nearby-places.md)

## A neighborhood typed by hand

For a manual neighborhood lookup (“Find restaurants in Villeray, Montreal”), a local Qwen2.5-Coder-7B model returned a map search proposal without live search tools. The gateway transcript preserved its complete reply and the Android parser accepted the map proposal. This test used an HTTP client and did not open a maps app. [Verbatim model reply and native parser result](examples/nearby-restaurants/named-area.json)

## How the guide is proposed

The model can return a named page with one `nearby_places` block. Android recognizes the block and obtains the results separately. The proposal contains no coordinates or venue list.

```hermes-workspace
{"version":1,"surface":"page/restaurants","mode":"replace","title":"Nearby restaurants","blocks":[{"type":"nearby_places","category":"restaurant"}]}
```

The map data is © OpenStreetMap contributors, available under the Open Database License. Contributors supply restaurant names and may supply a `cuisine` tag. A source link identifies the node, way or relation behind a row. [OpenStreetMap attribution and license](https://www.openstreetmap.org/copyright), [restaurant tagging](https://wiki.openstreetmap.org/wiki/Tag:amenity%3Drestaurant) and [cuisine tagging](https://wiki.openstreetmap.org/wiki/Key:cuisine)

The gateway accepts the standard Overpass OpenStreetMap/ODbL copyright notice and the short “OpenStreetMap contributors” credit, optionally prefixed with © or followed by “, ODbL”. A missing or unrecognized notice makes the lookup unavailable. A valid response can contain zero nearby named restaurants. A response containing only malformed map objects makes the lookup unavailable.

<!-- Generated from docs/sources/docs/nearby-restaurants.md.in by scripts/docs.py. -->

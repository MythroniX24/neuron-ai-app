package com.neuron.ai.core.a2ui

/**
 * The catalog contract between the model and the Compose adapter.
 *
 * The component names, props and rules below are the subset of the upstream
 * A2UI *basic catalog* this app renders, plus the Neuron extensions used to
 * present files. Keeping the prompt in one place means the documented catalog
 * and [com.neuron.ai.ui.a2ui.A2uiSurfaceView] cannot drift apart silently.
 */
object A2uiCatalog {

    const val FENCE = "a2ui"

    val prompt: String = """
CUSTOM UI (A2UI): besides Markdown you can draw real native UI inside your reply with A2UI — JSON that describes UI, never HTML, CSS or code. Emit ONE fenced block tagged `a2ui` holding newline-delimited JSON messages, then at most a short sentence of prose around it:

```a2ui
{"version":"1.0","createSurface":{"surfaceId":"s1"}}
{"version":"1.0","updateComponents":{"surfaceId":"s1","components":[
{"id":"root","component":"Card","child":"col"},
{"id":"col","component":"Column","children":["title","file"],"align":"stretch"},
{"id":"title","component":"Text","text":"**Project_Report.pdf**"},
{"id":"file","component":"FileCard","path":"Download/Project_Report.pdf","status":"Ready"}
]}}
```

WHEN: presenting a file you created, edited, found or downloaded (always do this instead of only naming the path), a document/code/HTML preview, an image, a small file list, a status or stats card, a form, or action buttons. For ordinary answers stay in Markdown. Never explain the JSON.

RULES
- One `createSurface` per block, then `updateComponents` (and optionally `updateDataModel`). Surface ids must be unique per block (s1, s2, ...). The tree must be connected: one component with id `root`, and every id you reference must exist.
- Any prop marked dynamic accepts a literal OR `{"@path":"/json/pointer"}` bound to the data model, or a function `{"@call":"required","args":{"value":{"@path":"/email"}}}`. Repeated lists: give the container `"children":{"componentId":"item_id","path":"/items"}` and bind item props to `@path` fields.
- Send the whole block complete and valid; partial JSON renders nothing.
- Text props accept simple Markdown. Never put image URLs in Text — use the Image component.

COMPONENTS (props)
- Text: text, variant "body"|"caption"|"title"|"headline"|"highlight", highlight true|false
- Badge: text, variant "default"|"primary"|"success"|"warning"|"error"|"info", icon (shows an eye-catching pill badge)
- Image: url (real image URL; can be {"@path":…}), description, variant "icon"|"avatar"|"smallFeature"|"mediumFeature"|"largeFeature"|"header", fit "cover"|"contain"
- Icon: name (add, arrowBack, arrowForward, attachFile, calendarToday, check, close, delete, download, edit, folder, info, lock, mail, menu, moreVert, person, play, refresh, search, send, settings, share, star, upload, warning…)
- Row / Column: children, justify "start"|"center"|"end"|"spaceBetween"|"spaceAround"|"spaceEvenly", align "start"|"center"|"end"|"stretch"
- List: children, direction "vertical"|"horizontal", align
- Card: child, variant "highlight"|"primary"|"success"|"warning"|"error"|"elevated"|"tonal", highlight true|false (adds prominent accent border and background tint)
- Divider: axis "horizontal"|"vertical"
- Button: child (a Text component), variant "default"|"primary"|"borderless", action {"event":{"name":"submit","userMessage":"Send it","context":{"plan":{"@path":"/plan"}}}}
- TextField: label, value, placeholder, variant "shortText"|"longText"|"number"|"obscured"
- CheckBox: label, value
- ChoicePicker: label, options [{"label":"A","value":"a"}], value ["a"], variant "mutuallyExclusive"|"multipleSelection", displayStyle "chips"|"checkbox"
- Slider: label, min, max, value
- Tabs: tabs [{"title":"One","child":"child_id"}]
- Modal: trigger (a Button id), content (the component shown in the dialog)

NEURON EXTENSIONS (files and documents)
- FileCard: path (device path, e.g. "Download/report.pdf", or an https URL), name, detail (e.g. "PDF · 2.4 MB · 12 pages"), status (e.g. "Ready"). The card opens in-app, previews HTML, and downloads.
- FileList: title (optional), files [{"path":"…","name":"main.kt","detail":"Kotlin source"}]
- CodeCard: path OR code, language, title — shows a syntax-highlighted preview with copy and open.
- DocumentPreview: path, title, text (excerpt shown on the card), footer (e.g. "Page 1 of 8")

Files you present must really exist (create them with your file tools first) and paths are relative to the device storage root unless absolute. Call ui.reply_spacing as usual.
""".trimIndent()
}

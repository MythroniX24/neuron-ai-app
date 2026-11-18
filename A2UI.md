# A2UI in Neuron-AI

The chat can render agent-authored native UI, not just Markdown. That is
[A2UI](https://github.com/a2ui-project/a2ui) — the Agent-to-UI protocol from
`a2ui-project/a2ui` — ported into this app for Jetpack Compose.

Upstream is Apache-2.0. The protocol, message shapes, `Dynamic*` binding rules,
`ChildList` templates and the basic catalog are theirs; the Kotlin core and the
Compose adapter here are a port written for this codebase (no upstream Gradle
module is vendored, so no upstream Java/Kotlin sources are copied verbatim).
Component visuals follow our own design system rather than upstream's web
adapter.

## How the agent uses it

The agent emits a fenced block tagged `a2ui` containing newline-delimited JSON
messages. `MarkdownParser` turns the fence into `MdBlock.A2ui` and
`A2uiBlockView` renders it in place, where the surrounding prose sits:

````
```a2ui
{"version":"1.0","createSurface":{"surfaceId":"s1"}}
{"version":"1.0","updateComponents":{"surfaceId":"s1","components":[
{"id":"root","component":"Card","child":"col"},
{"id":"col","component":"Column","children":["title","file"],"align":"stretch"},
{"id":"title","component":"Text","text":"**Project_Report.pdf**"},
{"id":"file","component":"FileCard","path":"Download/Project_Report.pdf","status":"Ready"}
]}}
```
````

The fence is the transport: A2UI is transport-agnostic, and in a chat the
assistant's reply already is the ordered, framed message stream. A half-streamed
block parses to nothing and shows a compact placeholder, so raw JSON never
reaches the bubble.

## What is ported

| Upstream concept | Here |
|---|---|
| Message envelope (`createSurface`, `updateComponents`, `updateDataModel`, `deleteSurface`) | `core/a2ui/A2uiProtocol.kt` |
| v0.8/0.9 compatibility (`beginRendering`, `surfaceUpdate`, `dataModelUpdate`, nested components, `literal*`/`path` values) | same file |
| Surface state + component map | `A2uiSurface` / `A2uiComponent` |
| Data model, JSON Pointer, `@path` bindings | `A2uiValue` |
| Catalog functions (`required`, `email`, `numeric`, `length`, `regex`, `not`, `and`, `or`, `concat`) | `A2uiFunctions` |
| `ChildList` static arrays and template children | `A2uiResolver.children()` |
| Basic catalog → widgets | `ui/a2ui/A2uiSurfaceView.kt` |
| Catalog description given to the model | `core/a2ui/A2uiCatalog.kt` |

Not ported, deliberately: the agent SDKs (Python/TS/Kotlin), A2A/AG-UI/MCP
bindings, and remote renderers. This app *is* the renderer and the agent, so
neither side of a network transport is needed.

## Catalog

Basic catalog components: `Text`, `Image`, `Icon`, `Row`, `Column`, `List`,
`Card`, `Divider`, `Button`, `TextField`, `CheckBox`, `ChoicePicker`, `Slider`,
`Tabs`, `Modal`, `Video`/`AudioPlayer` (opened externally).

Neuron extensions for files and documents:

| Component | Props | Behaviour |
|---|---|---|
| `FileCard` | `path`, `name`, `detail`, `status` | File-type tile, size/type line, status chip; **Open** (in-app viewer) and, for HTML, **Preview**; download at the right |
| `FileList` | `title`, `files[] {path,name,detail}` | Grouped rows like a project file list, each row opens and downloads |
| `CodeCard` | `path` or `code`, `language`, `title` | Syntax-highlighted preview, copy, open |
| `DocumentPreview` | `path`, `title`, `text`, `footer` | Document header + excerpt, tapping opens the file |

`core/a2ui/A2uiCatalog.kt` is the single source of the prompt, so the documented
props and the renderer cannot drift apart.

## Files: open, preview, download

`ui/files/FileRefs.kt` resolves what the agent writes — a storage-relative path
(`Download/report.pdf`), an absolute path, `file://`, `content://`, or an https
URL — and `ui/files/FileViewer.kt` shows it full-screen above every route
(`FileViewerHost` in `MainActivity`):

- **Code / text:** monospace, selectable, syntax highlighting from
  `SyntaxHighlighter`, copy button.
- **HTML:** opens on a **Preview** tab (real WebView) with a **Code** tab beside
  it, so the page renders immediately and the source is one tap away.
- **Images:** full-width image.
- **Anything else (PDF, DOCX, ZIP…):** open with another app (via
  `FileProvider`) or download.
- Close cross top-left; open-in-another-app and download top-right. Downloads
  go to the public `Downloads` folder (MediaStore on Android 10+).

## Interactions

`Button` actions and form values are data, not code:

- `Button.action.event` with `name`, optional `userMessage` and `context`
  becomes one user turn in the chat (`A2uiActionRequest.toUserMessage()`), so the
  agent answers it with its normal tools.
- `TextField` / `CheckBox` / `ChoicePicker` / `Slider` write back through
  `@path` bindings into the surface's data model (`LocalA2uiWrite`), so a
  button's `context` sees what the user typed.
- `Modal.trigger` opens its `content` in a dialog.

## Extending

To add a component: add the `when` branch in `A2uiSurfaceView.kt`, document its
props in `A2uiCatalog.prompt`, and add a case to `A2uiProtocolTest`. Keep
props `Dynamic*` (read through `A2uiResolver`) so bindings and templates work
for the new component too.

## Tests

`app/src/test/java/com/neuron/ai/core/a2ui/A2uiProtocolTest.kt` covers v1.0
parsing and bindings, inline components, the v0.8 wire format, template
children, JSON Pointer writes, partially streamed blocks and catalog functions.

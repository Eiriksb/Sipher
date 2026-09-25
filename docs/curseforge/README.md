# CurseForge project page

Everything needed to create the CurseForge project. Paste [description.md](description.md) into the description using
the **Markdown** editor.

| Field | Value |
|---|---|
| Name | Sipher |
| Summary | Live, private captions and translation for Simple Voice Chat — everything runs on your own PC. |
| Logo | [logo.png](logo.png) (512×512 PNG) |
| Main category | Utility & QoL |
| Additional categories | Server Utility, Miscellaneous |
| Game version | 1.21.1 |
| Mod loader | NeoForge |
| Environment | Client and server (server optional) |
| Licence | MIT |
| Source | https://github.com/Eiriksb/Sipher |
| Issues | https://github.com/Eiriksb/Sipher/issues |

## Relations (set on each uploaded file)

- **Simple Voice Chat** — *Required Dependency* (the CurseForge app then installs it automatically).

## File upload notes

- Upload `build/libs/sipher-<version>.jar` (one file for client and server). Release type: Beta until the language packs
  have had wider testing.
- Suggested changelog for the first upload: mention that language packs are optional, opt-in downloads of data files
  only, with a link to the "Privacy and network use" section.

## Before the first upload

1. Send [../CURSEFORGE_TICKET.md](../CURSEFORGE_TICKET.md) and wait for CurseForge's answer.
2. Make sure the language-pack release (`models-v1` on GitHub) is published, so downloads work on day one.
3. Check that the sizes in the Languages table still match the catalogue.

## Modrinth (if you publish there too)

The same description works. In Modrinth's *Content disclosures*, tick **AI functionality** (on-device transcription
and translation) and describe the optional language-pack downloads under network use.

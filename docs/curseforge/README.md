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
| Game versions | 1.21.1, 1.21.4, 1.21.5, 1.21.6–1.21.8, 1.21.9–1.21.10, 1.21.11, 26.1–26.1.2, 26.2, 26.3 |
| Mod loaders | Fabric, NeoForge |
| Environment | Client and server (server optional) |
| Licence | MIT |
| Source | https://github.com/Eiriksb/Sipher |
| Issues | https://github.com/Eiriksb/Sipher/issues |

## Relations (set on each uploaded file)

- **Simple Voice Chat** — *Required Dependency* (the CurseForge app then installs it automatically).
- **Fabric API** — *Required Dependency*, on Fabric files only.

## File upload notes

- The release workflow uploads one file per loader and Minecraft version, `sipher-<loader>-<version>+<minecraft>.jar`
  (each for client and server). Release type: Beta until the language packs have had wider testing.
- Suggested changelog for the first upload: mention that language packs are optional, opt-in downloads of data files
  only, with a link to the "Privacy and network use" section.

## Before the first upload

1. Send [../CURSEFORGE_TICKET.md](../CURSEFORGE_TICKET.md) and wait for CurseForge's answer.
2. Make sure the language-pack release (`models-v1` on GitHub) is published, so downloads work on day one.
3. Check that the sizes in the Languages table still match the catalogue.

## Modrinth (if you publish there too)

The same description works. In Modrinth's *Content disclosures*, tick **AI functionality** (on-device transcription
and translation) and describe the optional language-pack downloads under network use.

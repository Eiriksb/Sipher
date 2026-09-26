# Modrinth project page

Paste [description.md](description.md) into the project description. It differs from the CurseForge one only where
Modrinth needs it:

- Simple Voice Chat links to its Modrinth page.
- The credits don't link to GitHub repositories. Modrinth checks every GitHub repository link through GitHub's public
  API from the editor's browser, which allows 60 requests an hour. A few saves with many repository links use that up,
  and every link then shows *"could not be verified"*. Links to files on GitHub (`…/blob/…`) aren't checked.

| Field | Value |
|---|---|
| Name | Sipher |
| Summary | Live, private captions and translation for Simple Voice Chat — everything runs on your own PC. |
| Icon | [../curseforge/logo.png](../curseforge/logo.png) |
| Categories | Utility, Social |
| Environment | Client: required · Server: optional |
| Licence | MIT |
| Source code | https://github.com/Eiriksb/Sipher |
| Issue tracker | https://github.com/Eiriksb/Sipher/issues |
| Content disclosures | Tick **AI functionality** (on-device speech recognition and translation) |

Versions are uploaded by the release workflow (see [../RELEASING.md](../RELEASING.md)), with Simple Voice Chat as a
required dependency. The project id is in the `MODRINTH_PROJECT_ID` repository variable.

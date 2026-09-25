# Language packs

Everything needed to rebuild Sipher's optional language packs from their upstream models. The mod itself never runs
any of this; it only downloads the resulting files listed in `src/main/resources/sipher/catalog.json`.

Work files go to `.cache/packs/` (git-ignored; several GB): `out/<component>/` holds finished components, `work/` holds
downloads, checkpoints and virtual environments.

## Pipeline

1. **Translation** — `translation/build.py` exports each OPUS-MT / Marian model listed in `translation/components.json`
   to int8 ONNX and validates it (tokenizer parity, sample translations). See `translation/README.md`.
2. **Speech, prebuilt** — `speech/prebuilt.py all` fetches sherpa-onnx exports pinned in `speech/prebuilt.json` and
   validates them on FLEURS clips.
3. **Speech, converted** — `speech/convert_nemo.py` and `speech/convert_whisper.py` convert the models in
   `speech/convert.json` with sherpa-onnx's export scripts and validate them. See `speech/README.md`.
4. **Licences** — `add_license_texts.py` appends full licence texts where the licence requires it (Apache-2.0).
5. **Catalogue** — `make_catalog.py` verifies every file against its manifest and writes the catalogue compiled into
   the mod (download URLs, SHA-256, sizes, licences).
6. **Test** — `./gradlew test -PpacksDir=.cache/packs/out -PpacksAudio=.cache/packs/work` loads every pack through the
   mod's own Java code and runs it on real speech.
7. **Publish** — `publish.py` uploads the files to the GitHub release named in the catalogue (`models-v1`).

Every component directory contains its model files, `LICENSE` (licence, attribution, upstream model and revision) and
`manifest.json` (type, licence, pinned upstream revision, file hashes and validation results).

Changing any file changes its hash, so a rebuilt pack needs a new catalogue and therefore a new mod version; published
release assets are never replaced in place.

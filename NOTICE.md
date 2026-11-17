# Licence a původ kódu

Pekey keyboard je šířená pod licencí **GNU GPL v3** (soubor `LICENSE`).

Části převzaté z Blackline Keyboard (vychází z HeliBoard/LeanType a AOSP LatinIME):

- `app/src/main/kotlin/cz/pekey/GestureTrail.kt` – kreslení stopy gesta (AOSP, Apache-2.0 AND GPL-3.0-only)
- `app/src/main/kotlin/cz/pekey/Shortcuts.kt` – značky a úpravy textových zkratek (GPL-3.0-only)

Slovníky a jejich zdroje viz `tools/slovnik/`.

Přibalené jsou jen slovníky češtiny a angličtiny. Další jazyky (němčina, francouzština, italština, polština,
ukrajinština) mají rozložení kláves a slovník se k nim nahraje v nastavení (např. experimentální slovníky
z projektu Helium314/aosp-dictionaries).

Hlasové zadávání používá knihovnu whisper.cpp (MIT, github.com/ggml-org/whisper.cpp); model Whisper si uživatel nahraje sám.

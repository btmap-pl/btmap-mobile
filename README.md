# btmap-mobile
Aplikacja mobilna BTMap – mapa stacji bazowych w Polsce

## Struktura

- `core/` – biblioteka Androida: odczyt komórek z modemu (wszystkie karty SIM, LTE/NR/UMTS/GSM,
  NSA, agregacja), pasma i częstotliwości z (E/NR/U)ARFCN, eNB/gNB:CID, odległość z TA, lokalizacja
  bez Google Play Services, log komórek sesji i klient API BTMap (wyszukiwanie stacji po eNB/gNB).
- `app/` – aplikacja (Jetpack Compose, motyw jasny i ciemny). Na razie ekran Live do testów core.

## Budowanie

```bash
./gradlew :core:testDebugUnitTest :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

Adres API ustawia `btmap.apiBaseUrl` w `gradle.properties` (domyślnie lokalny serwer btmapv2 na
porcie 3001). Z telefonem na USB: `adb reverse tcp:3001 tcp:3001`.

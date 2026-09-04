# Stereo Analog Recorder

**Stereo Analog Recorder** è un'app Android che ti permette di **misurare e controllare in tempo reale il guadagno (gain) del microfono**, di **registrarlo** su file, e di **regolare il volume di ascolto in cuffia** dei microfoni mentre "Ascolto diretto" è attivo — il tutto per evitare che le sorgenti forti vadano in clipping a 0 dB e le registrazioni restino pulite.

L'obiettivo principale è semplice: darti un controllo preciso sulla sensibilità del microfono — sia in **boost** (quando la sorgente è troppo silenzioso) sia, soprattutto, in **attenuazione** (quando la sorgente è forte e altrimenti distorcerebbe). Come bonus, lo stesso gain mantiene pulite le registrazioni anche quando apri la fotocamera, registri un memo vocale o fai una chiamata WhatsApp / Telegram, perché il gain viene applicato a livello di codec (root) o DSP **prima** che i campioni lascino il processo dell'app — e resta in vigore grazie alla cattura sempre attiva che sopravvive al cambio app da parte dell'utente.

## Script di installazione

**[`install-android-with-build-alsa-driver.sh`](install-android-with-build-alsa-driver.sh)** — *Script di build + install per sviluppatori*. Cross-compila `tinymix` dai sorgenti di tinyalsa inclusi (tramite il toolchain clang dell'NDK) **per tutte e quattro le ABI supportate** e scrive i binari direttamente dentro la cartella asset dell'APK, poi compila l'APK debug con Gradle (tramite l'SDK Android) in modo che le modifiche a `tinymix` finiscano nell'APK risultante, e infine installa l'APK sul dispositivo e grant dei permessi runtime. Entrambi si appoggiano a SDK Android + NDK installati localmente — Gradle usa l'SDK, il build C/C++ usa l'NDK. Richiede JDK 17+, Android SDK con `platform-tools`, `platforms;android-34`, `build-tools;34.0.0` e un NDK (famiglia r27).

Per gli utenti che non vogliono rebuildare nulla: scarica l'ultima APK da [Releases](../../releases) e installala con `adb install -r app-debug.apk`. **L'APK è già self-contained**: il binario `tinymix` per tutte e quattro le ABI è impacchettato come asset e l'app lo estrae automaticamente al primo avvio nella propria directory privata.

---

## Dipendenze incluse

Il repository contiene solo i sorgenti necessari alla compilazione dei binari `tinymix`:

| Dipendenza | Versione | Provenienza |
|---|---|---|
| `tinymix` / `libtinyalsa` (sorgenti) | tinyalsa **2.0.0**, commit `9fab97c` (master, 2026-07-27) | [github.com/tinyalsa/tinyalsa](https://github.com/tinyalsa/tinyalsa) |

L'APK finale, incluso il binario `tinymix` per tutte e quattro le ABI, viene pubblicato nelle [Releases di GitHub](../../releases) ad ogni release. Lo script di build ricompila `tinymix` da sorgente per le 4 ABI supportate (arm64-v8a, armeabi-v7a, x86_64, x86), li inserisce come asset dentro l'APK, builda l'APK e lo installa — senza fare affidamento su binari prebuilt committati.

I dettagli delle licenze dei componenti di terze parti inclusi (BSD-3-Clause per `tinymix` / `libtinyalsa`, Apache-2.0 per AndroidX e Google Material) sono in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

---

## Cosa permette di fare l'app

### Controllo del gain per singolo microfono
- Due slider orizzontali indipendenti: **Mic 1** e **Mic 2**, mappati sui canali sinistro e destro di una cattura microfonica stereo.
- Ogni slider controlla il guadagno in **dB**, con valori positivi per amplificare il segnale e valori negativi per attenuarlo.
- Uno switch **Collega** muove entrambi i microfoni insieme con un unico valore di gain condiviso, utile per regolarli in sincrono quando serve.
- L'escursione massima di boost e attenuazione è configurabile dalle impostazioni (lo slider "Scala del guadagno").

### Architettura del gain a due livelli
- **Attenuazione analogica pre-ADC (ALSA / tinyalsa)** — sui dispositivi rootati, l'app abbassa il guadagno del preamplificatore del codec *prima* del convertitore analogico-digitale. Questo è l'unico modo per evitare il clipping alla sorgente, perché un intervento solo digitale verrebbe applicato a campioni già clippati.
- **Gain DSP digitale** — uno stadio di moltiplica e clamp applicato ai campioni PCM catturati, usato come fallback quando il root o l'helper ALSA non sono disponibili.
- La riga di stato nell'interfaccia indica chiaramente quale percorso è attivo (analogico + digitale, oppure solo digitale).

### Monitoraggio in tempo reale
- Due **meter di livello**, uno per canale, che mostrano il livello in dB di ogni microfono in diretta durante la registrazione.
- Due **stili di meter** selezionabili:
  - **Digitale (stile DAW)**
  - **Analogico (stile tape)**
- **Indicatore di picco per canale** (numerico, in dB) che memorizza il livello massimo raggiunto dall'inizio della registrazione.
- Un marker **CLIP** si accende quando un canale raggiunge 0 dB.
- Un **contatore del tempo trascorso** durante la registrazione.

### Ascolto in cuffia in tempo reale
- Un interruttore "Ascolto diretto" che ti permette di sentire i microfoni tramite cuffie cablate o Bluetooth collegate, anche quando non stai registrando. Utile per verificare al volo cosa stanno captando i microfoni.
- Uno slider **Volume di ascolto** (-100 % … +100 %, 0 % = unity) compare nel momento in cui "Ascolto diretto" è acceso e le cuffie sono collegate. I valori negativi attenuano il volume di ascolto mentre quelli positivi lo amplificano. Sotto compare un secondo slider **Escursione massima** (0 – 75 dB, default ±20 dB) che imposta l'escursione in dB agli estremi dello slider del volume: il guadagno lineare applicato al tap del monitor è `10^((volumePercent / 100) × (maxDb / 20))`. Entrambi i valori sono memorizzati nelle `SharedPreferences` e ripristinati al riavvio.
- Il guadagno è distribuito su due stadi: la parte che sta dentro `AudioTrack.setVolume()` (per-instance, simmetrico sui due canali, **non tocca il volume di sistema media / suoneria / chiamate / sveglia**, e non tocca la registrazione che va a disco) viene data a `setVolume`; l'eventuale boost in eccesso viene applicato come moltiplicazione per campione dentro il tap del monitor, con arrotondamento e clamp a 16 bit. È proprio questa divisione su due stadi che rende il boost sopra unity effettivamente udibile sui dispositivi il cui `AudioTrack.getMaxVolume()` è bloccato a 1.0 (tutti gli Android moderni) — la sola `setVolume` su quei dispositivi si sarebbe clampata silenziosamente a unity.

### Registrazione
- Pulsanti **Registra** / **Stop** con un tocco (il classico cerchio rosso e il quadrato bianco).
- Tre formati di uscita:
  - **WAV 16-bit** (PCM non compresso).
  - **WAV 24-bit** (PCM non compresso, gamma dinamica maggiore).
  - **M4A (AAC)** — compresso, con bitrate selezionabile dal range supportato dal dispositivo (tipicamente 32–320 kbps).
- La **frequenza di campionamento** viene rilevata automaticamente in base a ciò che il telefono è effettivamente in grado di catturare, così il menu a tendina elenca solo frequenze garantite per funzionare end-to-end.
- Le registrazioni possono continuare in background con una notifica persistente che espone controlli rapidi −/+ per microfono e un'azione di avvio/stop.

### Impostazioni e personalizzazione
- **Tema**: Chiaro o Scuro, applicato a tutta l'app, inclusa la notifica.
- **Lingua**: English o Italiano, interfaccia completamente tradotta.
- **Scala del guadagno**: scegli l'escursione massima ± dB usata dagli slider.
- **Tipo di controllo**: scegli tra **Guadagno** analogico reale (solo root, l'unico percorso che previene davvero il clipping) e **Livello** (scalatura digitale del volume che funziona su qualsiasi dispositivo).
- **Stile notifica**: controlli classici (−/+ per mic, rec/stop) oppure una notifica minimale "registrazione in background".

### Il gain resta attivo passando da un'app all'altra (percorso root)
Quando **Tipo di controllo = "Guadagno (solo root)"** è selezionato e il root è disponibile, il dB richiesto viene suddiviso in una parte analogica applicata *prima* dell'ADC (ALSA, tramite l'helper `tinymix` incluso) e una parte digitale residua applicata al PCM. Il valore sul codec viene bloccato a livello hardware e il residuo è applicato dalla cattura foreground sempre attiva. **Finché l'app resta in esecuzione in background**, entrambi i numeri rimangono al livello che hai impostato — aprire un'altra app che registra dal microfono (fotocamera stock con audio, memo vocale, messaggi audio WhatsApp o Telegram, chiamate vocali / videochiamate WhatsApp o Telegram, …) continua a catturare con il tuo gain: una sorgente silenziosa che hai alzato di **+12 dB** resta alzata, una sorgente forte di un concerto che hai tagliato di **−18 dB** resta tagliata. Anche la notifica con i controlli classici continua a funzionare, così puoi affinare entrambi i gain dei microfoni dalla tendina senza lasciare l'altra app. Fermando il servizio foreground dalla notifica (o chiudendo Stereo Analog Recorder dall'app) il codec viene riportato al default di boot — le altre app tornano al loro normale livello del microfono.

### Avvertenza
- L'app ti ricorda che gain positivi elevati possono danneggiare l'udito e distorcere la registrazione. Con sorgenti forti, l'approccio consigliato è il **gain negativo** per restare sotto 0 dB.

---

## Come funziona, in sintesi

| Fase | Cosa succede |
|---|---|
| Cattura | `AudioRecord` legge i due microfoni interni come flusso PCM stereo (L = Mic 1, R = Mic 2). |
| Stadio analogico (solo root) | Il dB richiesto viene suddiviso: la parte che il codec può erogare viene applicata *prima* dell'ADC via ALSA; il residuo resta per il digitale. |
| Stadio digitale | Il dB residuo viene moltiplicato nei campioni PCM e clampato. |
| Meter / picco | Il livello di ogni buffer viene convertito in dB e inviato ai meter e alla memoria di picco. |
| Registrazione | Il PCM processato viene codificato nel formato scelto e scritto su disco. |
| Ascolto diretto | Un secondo consumer (`AudioTrack`) attinge allo stesso PCM processato in parallelo e lo riproduce nelle cuffie collegate. Il livello in cuffia è suddiviso in una porzione data a `AudioTrack.setVolume()` (per-instance, entrambi i canali insieme, nessuno stream di sistema toccato) più un moltiplicatore per campione per l'eventuale boost che supera il `AudioTrack.getMaxVolume()` della piattaforma. |
| Cattura sempre attiva | Finché il servizio foreground è in esecuzione (cioè non l'hai terminato dalla notifica, chiuso dall'app, o fermato il servizio), l'AudioRecord e lo stato ALSA del codec restano vivi in background — così qualsiasi altra app che apre il microfono eredita il gain che hai impostato. |

---

## Compatibilità

- **Android 8.0 (API 26)** o successivo.
- Funziona su qualsiasi telefono con due microfoni, senza root per il percorso digitale.
- Il **root (consigliato Magisk)** è necessario per l'attenuazione analogica pre-ADC che previene il clipping alla sorgente.
- "Ascolto diretto" richiede un'uscita audio cuffie cablate, cuffie Bluetooth A2DP, oppure un headset USB. Lo slider del volume di ascolto può amplificare fino alla **Escursione massima** scelta (0 – 75 dB) su qualsiasi dispositivo — il moltiplicatore per campione nel tap del monitor si prende carico di qualunque guadagno che `AudioTrack.setVolume()` non riesce a esprimere (tipicamente sopra 1.0), quindi i valori positivi del volume sono sempre udibili a prescindere dal cap di `getMaxVolume()` della piattaforma. Il volume degli stream AudioManager non viene mai modificato.

---

## Info sul progetto

- Linguaggio: **Kotlin**
- Min SDK: 26 — Target SDK: 34
- Package: `com.stereoanalogrecorder.app`


---

## Licenza

Copyright (C) 2026 Luigi De Paola

Stereo Analog Recorder è software libero: puoi redistribuirlo e/o modificarlo secondo i termini della GNU General Public License v3.0 (GPL-3.0).


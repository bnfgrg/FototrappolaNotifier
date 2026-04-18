# Fototrappola Notifier

App Android che mostra una notifica a schermo intero (tipo chiamata in arrivo, anche con schermo spento e bloccato) quando arriva una email sulla casella Gmail della fototrappola, con le miniature delle immagini allegate visibili tutte insieme.

## Funzionalità

- Connessione IMAP IDLE a Gmail (push in tempo reale, senza polling)
- Notifica full-screen con accensione schermo, anche da lockscreen
- Miniature delle immagini allegate tutte visibili in contemporanea
- Swipe orizzontale per scorrere tra più email non lette
- Tap su miniatura per vista a schermo intero
- Filtro mittente opzionale (solo email da un dato indirizzo/dominio)
- Avvio automatico al boot del telefono
- Credenziali cifrate localmente (EncryptedSharedPreferences)

## Requisiti

- Android 13+ (API 33)
- Account Gmail con **verifica in 2 passaggi attiva**
- **Password per app** generata da Google

## Come compilare l'APK via GitHub Actions

### 1. Carica il progetto su GitHub

1. Crea un nuovo repository su GitHub (es. `fototrappola-notifier`), **privato**.
2. Carica tutti i file e cartelle di questo progetto alla radice del repo. Puoi farlo via web (drag&drop del contenuto della cartella) oppure da terminale:
   ```bash
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/TUO_USERNAME/fototrappola-notifier.git
   git push -u origin main
   ```

### 2. Avvia la build

Il workflow parte automaticamente al push. Se vuoi rilanciarlo manualmente:

- Vai su **Actions** nel repository
- Seleziona **Build APK** nella barra laterale
- Clicca **Run workflow**

### 3. Scarica l'APK

Al termine (~3-5 minuti):

- Vai su **Actions** → clicca sulla run completata
- In basso nella sezione **Artifacts** trovi:
  - `FototrappolaNotifier-debug` → APK debug, **installabile direttamente**
  - `FototrappolaNotifier-release` → APK release (firmato con chiave debug per semplicità), anch'esso installabile e più performante del debug
- Scarica quello che preferisci (consiglio il release), estrai lo ZIP, trasferisci l'APK sul telefono e installalo (serve abilitare "Installa app sconosciute" per l'app dalla quale apri l'APK, es. Google Drive o il file manager)

### 4. Release taggate (opzionale)

Se vuoi una release scaricabile pubblicamente:
```bash
git tag v1.0
git push origin v1.0
```
Il workflow genererà una Release GitHub con l'APK allegato.

## Configurazione dell'app

### Ottenere la password per app Gmail

1. Vai su https://myaccount.google.com/security
2. Attiva **Verifica in 2 passaggi** (se non già attiva)
3. Torna su Sicurezza → cerca **Password per le app** (link diretto: https://myaccount.google.com/apppasswords)
4. Crea una nuova password con nome a scelta (es. "Fototrappola")
5. Google mostra una stringa di 16 caratteri: copiala

### Setup nell'app

1. Apri **Fototrappola Notifier**
2. Inserisci:
   - **Email Gmail**: `fototrappola60607@gmail.com`
   - **Password per app**: i 16 caratteri (spazi ignorati)
   - **Filtro mittente** (opzionale): se le fototrappole inviano sempre dallo stesso indirizzo, mettilo qui per ignorare altre mail (es. `@miodominio.com` oppure `fototrappola@`)
3. Premi **Salva e avvia servizio**
4. Premi **Disattiva ottimizzazione batteria** e concedi
5. Premi **Consenti notifiche schermo intero** e attiva il toggle

**Importante**: su Android 14+ il permesso `USE_FULL_SCREEN_INTENT` è ristretto e va concesso manualmente dall'utente in Impostazioni. Il pulsante apre la schermata corretta. Senza questo permesso la notifica apparirà in modo normale, non a schermo intero.

### Ottimizzazioni consigliate per affidabilità

Android tende a uccidere i servizi in background per risparmiare batteria. Per massima affidabilità:

- **Samsung/Xiaomi/OnePlus/Huawei**: nelle Impostazioni → App → Fototrappola Notifier → Batteria, imposta **Non limitato** o **Non ottimizzato**
- **Xiaomi/MIUI**: Impostazioni → App → Fototrappola Notifier → Autoavvio → ON
- Assicurati che l'app non sia messa "in sospensione" automaticamente

## Come funziona tecnicamente

- Un **Foreground Service** tiene aperta una connessione IMAP IDLE a `imap.gmail.com:993`
- IMAP IDLE è un push: Gmail notifica immediatamente quando arriva una mail, senza polling periodico
- Ogni ~25 minuti la connessione viene rinnovata per evitare timeout server (limite IMAP standard: 29 min)
- Alla nuova mail: download allegati immagine → salvataggio in memoria → notifica full-screen intent
- La `NotificationActivity` usa i flag `showWhenLocked` + `turnScreenOn` per apparire sopra il lockscreen accendendo lo schermo
- Se il service crasha, Android lo riavvia (`START_STICKY`). Al boot, il `BootReceiver` lo riavvia.

## Limitazioni note

- Le email vengono tenute **solo in memoria**, non salvate su disco. Se l'app viene uccisa e riavviata, l'elenco si svuota. (Le mail restano su Gmail, ovviamente.)
- All'avvio, il service esegue una scan delle email non lette già presenti in INBOX e le mostra tutte. Se non vuoi questo comportamento, leggi le mail su Gmail prima di avviare il servizio.
- Gmail ha rate limit sulle connessioni IMAP: evita riavvii in loop del servizio.
- Le immagini vengono tenute in memoria come bytes raw. Con fototrappole da 12MP, 5 foto sono ~20MB per email. Non tenere centinaia di email non lette aperte simultaneamente.

## Struttura del progetto

```
app/src/main/
├── AndroidManifest.xml
├── java/com/fototrappola/notifier/
│   ├── FototrappolaApp.kt          # Application class
│   ├── MainActivity.kt              # Schermata setup
│   ├── NotificationActivity.kt      # Schermata full-screen con swipe
│   ├── ImapListenerService.kt       # Foreground service IMAP IDLE
│   ├── EmailRepository.kt           # Stato in-memory delle email
│   ├── CredentialStore.kt           # Storage credenziali cifrato
│   └── BootReceiver.kt              # Autostart al boot
└── res/
    ├── drawable/        (icone)
    ├── mipmap-anydpi-v26/ (launcher adattivo)
    └── values/          (strings, colors, themes)
```

## Troubleshooting

**"La notifica appare ma non accende lo schermo"**
→ Permesso `USE_FULL_SCREEN_INTENT` non concesso. Impostazioni → App → Fototrappola Notifier → Notifiche → Notifiche a schermo intero → ON.

**"Il servizio si ferma dopo qualche ora"**
→ Ottimizzazione batteria non disattivata, oppure il produttore (Xiaomi, Huawei, ecc.) ha policy aggressive. Cerca `dontkillmyapp.com` + nome del tuo telefono per istruzioni specifiche.

**"Errore di autenticazione"**
→ Controlla che la password per app sia corretta (16 caratteri, senza spazi). La password dell'account Google normale **non funziona**, serve quella generata apposta.

**"Nessuna notifica arriva"**
→ Controlla la notifica persistente "Fototrappola attiva" in basso. Deve dire "In ascolto su xxx@gmail.com". Se dice "Errore..." c'è un problema di connessione/credenziali.
